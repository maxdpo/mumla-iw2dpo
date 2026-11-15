/*
 * Copyright (C) 2026 IW2DPO
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IW2DPO: "local tones" played ONLY on the radio's own loudspeaker when PTT is pressed
 * (nothing is transmitted to the channel).
 *
 * <ul>
 *   <li>READY TO TALK (connected to a server): 1568 Hz, 1046 Hz, 1568 Hz, 1318 Hz,
 *       40 ms each.</li>
 *   <li>NOT READY TO TALK (no server / server unavailable): 300 Hz for 1000 ms.</li>
 * </ul>
 *
 * Tones are synthesized as PCM 16 bit and played with a static {@link AudioTrack} on a
 * background thread, so the PTT key handler never blocks.
 *
 * The optional "onDone" callback runs (on the player thread) when the tone has really
 * finished on the loudspeaker: playback head at the end, then the AudioTrack timestamp
 * (= sample presented at the speaker, includes output latency) at the end, then
 * {@link #TAIL_MS}. MumlaService uses it to keep the mic gate closed while the local beep
 * is playing, so the beep is not picked up by the mic and sent to the channel.
 */
public class LocalTonePlayer {
    private static final String TAG = LocalTonePlayer.class.getName();

    private static final int SAMPLE_RATE = 48000;
    /** Short fade in/out on every tone segment to avoid clicks. */
    private static final int FADE_MS = 3;
    /** Extra wait after the last sample has left the loudspeaker (acoustic decay). */
    private static final int TAIL_MS = 30;
    /** Max wait for the output latency (AudioTrack timestamp) after the playback head end. */
    private static final int OUTPUT_LATENCY_MAX_MS = 250;

    /** READY TO TALK: {frequency Hz, duration ms} */
    private static final int[][] READY_TONES = {
            {1568, 40},
            {1046, 40},
            {1568, 40},
            {1318, 40},
    };
    /** NOT READY TO TALK: {frequency Hz, duration ms} */
    private static final int[][] NOT_READY_TONES = {
            {300, 1000},
    };

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Object mLock = new Object();
    private AudioTrack mTrack;
    private int mUsage = AudioAttributes.USAGE_MEDIA;

    /** @param handsetMode true to play on the voice-call stream (earpiece), like RX audio. */
    public void setHandsetMode(boolean handsetMode) {
        mUsage = handsetMode ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                : AudioAttributes.USAGE_MEDIA;
    }

    /** @param volumePercent 1-100, percentage of full scale. */
    public void playReady(int volumePercent, Runnable onDone) {
        play(READY_TONES, volumePercent, onDone);
    }

    /** @param volumePercent 1-100, percentage of full scale. */
    public void playNotReady(int volumePercent, Runnable onDone) {
        play(NOT_READY_TONES, volumePercent, onDone);
    }

    private void play(final int[][] tones, int volumePercent, final Runnable onDone) {
        final int volume = Math.max(1, Math.min(100, volumePercent));
        final int usage = mUsage;
        try {
            mExecutor.execute(() -> {
                try {
                    playBlocking(tones, volume, usage);
                } finally {
                    runDone(onDone);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Local tone not played", e);
            runDone(onDone);
        }
    }

    private static void runDone(Runnable onDone) {
        if (onDone == null) return;
        try {
            onDone.run();
        } catch (Exception e) {
            Log.w(TAG, "Local tone onDone failed", e);
        }
    }

    /** Synthesizes the tone sequence (exposed for tests). */
    static short[] synthesize(int[][] tones, int volumePercent) {
        int total = 0;
        for (int[] tone : tones) {
            total += (tone[1] * SAMPLE_RATE) / 1000;
        }
        final short[] pcm = new short[total];
        final double amplitude = Short.MAX_VALUE * (volumePercent / 100.0);
        final int fade = Math.max(1, (FADE_MS * SAMPLE_RATE) / 1000);
        int pos = 0;
        for (int[] tone : tones) {
            final int n = (tone[1] * SAMPLE_RATE) / 1000;
            for (int i = 0; i < n; i++) {
                double env = 1.0;
                if (i < fade) env = (double) i / fade;
                else if (i >= n - fade) env = (double) (n - 1 - i) / fade;
                final double s = Math.sin(2.0 * Math.PI * tone[0] * i / SAMPLE_RATE);
                pcm[pos++] = (short) Math.round(s * env * amplitude);
            }
        }
        return pcm;
    }

    private void playBlocking(int[][] tones, int volume, int usage) {
        final short[] pcm = synthesize(tones, volume);
        AudioTrack track = null;
        try {
            // API 21 constructor (minSdk 21; AudioTrack.Builder needs API 23).
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    pcm.length * 2,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.write(pcm, 0, pcm.length);
            synchronized (mLock) {
                mTrack = track;
            }
            track.play();
            // Wait until the last sample has been played (playback head at the end),
            // with a safety timeout, then the tail for output latency.
            final long durationMs = (pcm.length * 1000L) / SAMPLE_RATE;
            final long deadline = System.currentTimeMillis() + durationMs + 500;
            while (track.getPlaybackHeadPosition() < pcm.length
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            // Wait until the last sample has really been presented at the loudspeaker
            // (output latency). If the device gives no timestamp, wait the max time.
            final AudioTimestamp ts = new AudioTimestamp();
            final long latencyDeadline = System.currentTimeMillis() + OUTPUT_LATENCY_MAX_MS;
            while (System.currentTimeMillis() < latencyDeadline) {
                if (track.getTimestamp(ts) && ts.framePosition >= pcm.length) {
                    break;
                }
                Thread.sleep(5);
            }
            Thread.sleep(TAIL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play local tone", e);
        } finally {
            synchronized (mLock) {
                if (mTrack == track) mTrack = null;
            }
            if (track != null) {
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                }
                track.release();
            }
        }
    }

    /** Stops any tone and frees resources. Call from the service's onDestroy(). */
    public void release() {
        synchronized (mLock) {
            if (mTrack != null) {
                try {
                    mTrack.stop();
                } catch (IllegalStateException ignored) {
                }
            }
        }
        mExecutor.shutdownNow();
    }
}
