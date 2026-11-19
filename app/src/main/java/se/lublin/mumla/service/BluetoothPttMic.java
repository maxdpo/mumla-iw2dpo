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

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import android.view.KeyEvent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * IW2DPO 19-11-2026-2: external Bluetooth speaker-microphones with PTT and
 * volume keys (UNIWA BM001, Inrico B01/B02 and clones).
 *
 * These devices do NOT send their keys as normal Android key events to the
 * foreground Activity (that is why MumlaActivity.onKeyDown() never saw them).
 * They use two other channels, both handled here:
 *
 *  1. Bluetooth SPP (serial port, UUID 00001101-...): the PTT key is sent as
 *     text, "+PTT=P" when pressed and "+PTT=R" when released (also the Zello
 *     variant "+PTTS=P/R"). Some models send the volume keys the same way.
 *
 *  2. AVRCP (media remote control): the V+/V- keys (and Play/Pause) are sent
 *     to the ACTIVE MEDIA SESSION, not to the Activity. Mumla had no media
 *     session, so the keys went to another app (or nowhere). Mumla now owns an
 *     active MediaSession and receives them in onMediaButtonEvent().
 *
 * Both paths work also with Mumla in background / screen off.
 * Every received SPP line and media key is written to logcat with tag
 * "BtPttMic" (adb logcat -s BtPttMic) to support other microphone models.
 */
public class BluetoothPttMic {

    public interface Listener {
        /** PTT pressed (called on the main thread). */
        void onBtPttDown();
        /** PTT released (called on the main thread). */
        void onBtPttUp();
        /** Volume key (called on the main thread). */
        void onBtVolume(boolean raise);
        /** Returns the configured PTT key code (Settings), 0 if none. */
        int getConfiguredPttKey();
        /** Returns the configured Vol-up key code (Settings), 0 if none. */
        int getConfiguredVolumeUpKey();
        /** Returns the configured Vol-down key code (Settings), 0 if none. */
        int getConfiguredVolumeDownKey();
        /** Audio stream used by Mumla for RX (STREAM_MUSIC or STREAM_VOICE_CALL). */
        int getAudioStream();
    }

    /**
     * Optional hook used by the key-select dialog in Settings: while set, the
     * keys received from the Bluetooth microphone through the media session
     * are passed here, so they can be assigned as PTT / Vol-up / Vol-down keys.
     */
    public interface KeyCaptureListener {
        void onBtKeyCaptured(int keyCode);
    }

    private static volatile KeyCaptureListener sKeyCaptureListener;

    public static void setKeyCaptureListener(KeyCaptureListener listener) {
        sKeyCaptureListener = listener;
    }

    private static final String TAG = "BtPttMic";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    /** Retry interval of the SPP connection while no microphone is connected. */
    private static final long SPP_RETRY_MS = 5000;

    private final Context mContext;
    private final Listener mListener;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private MediaSession mMediaSession;
    private volatile boolean mRunning;
    private Thread mSppThread;
    private volatile BluetoothSocket mSocket;
    /** True while the PTT is held according to the SPP channel. */
    private boolean mSppPttDown;
    /** True while the PTT is held according to the media session channel. */
    private boolean mMediaPttDown;

    public BluetoothPttMic(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        startMediaSession();
        mSppThread = new Thread(this::sppLoop, "BtPttMic-SPP");
        mSppThread.setDaemon(true);
        mSppThread.start();
    }

    public void stop() {
        mRunning = false;
        closeSocket();
        if (mSppThread != null) {
            mSppThread.interrupt();
            mSppThread = null;
        }
        if (mMediaSession != null) {
            try {
                mMediaSession.setActive(false);
                mMediaSession.release();
            } catch (Exception e) {
                Log.w(TAG, "MediaSession release", e);
            }
            mMediaSession = null;
        }
        releasePttIfHeld();
    }

    /** Re-applies the audio stream used for RX (handset mode changed). */
    public void updateAudioStream() {
        if (mMediaSession != null) {
            mMediaSession.setPlaybackToLocal(new android.media.AudioAttributes.Builder()
                    .setLegacyStreamType(mListener.getAudioStream()).build());
        }
    }

    // ---------------------------------------------------------------- AVRCP

    private void startMediaSession() {
        try {
            mMediaSession = new MediaSession(mContext, "MumlaBtPttMic");
            mMediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null && handleMediaKey(event)) {
                        return true;
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent);
                }
            }, mMainHandler);
            //noinspection deprecation
            mMediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            // STATE_PLAYING: Android (and the Bluetooth AVRCP service) sends the
            // media keys of headsets/microphones to the most recent "playing" session.
            mMediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build());
            updateAudioStream();
            mMediaSession.setActive(true);
            Log.i(TAG, "MediaSession active");
        } catch (Exception e) {
            Log.w(TAG, "MediaSession not available", e);
            mMediaSession = null;
        }
    }

    /** @return true if the key was used. Called on the main thread. */
    private boolean handleMediaKey(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        Log.i(TAG, "media key " + KeyEvent.keyCodeToString(keyCode)
                + (down ? " DOWN" : " UP") + " repeat=" + event.getRepeatCount());

        KeyCaptureListener capture = sKeyCaptureListener;
        if (capture != null) {
            if (down && event.getRepeatCount() == 0) {
                capture.onBtKeyCaptured(keyCode);
            }
            return true;
        }

        final int pttKey = mListener.getConfiguredPttKey();
        if (pttKey > 0 && keyCode == pttKey) {
            if (down && !mMediaPttDown) {
                mMediaPttDown = true;
                mListener.onBtPttDown();
            } else if (!down && mMediaPttDown) {
                mMediaPttDown = false;
                mListener.onBtPttUp();
            }
            return true;
        }
        final int volUpKey = mListener.getConfiguredVolumeUpKey();
        final int volDownKey = mListener.getConfiguredVolumeDownKey();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || (volUpKey > 0 && keyCode == volUpKey)) {
            if (down) mListener.onBtVolume(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || (volDownKey > 0 && keyCode == volDownKey)) {
            if (down) mListener.onBtVolume(false);
            return true;
        }
        // Other media keys (Play/Pause = power key short press, Next/Previous =
        // long press of V+/V- on some models): consumed, so they do not start
        // a music player while Mumla is in use.
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ SPP

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter getAdapter() {
        BluetoothManager bm = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    private void sppLoop() {
        while (mRunning) {
            try {
                connectAndRead();
            } catch (Exception e) {
                Log.d(TAG, "SPP: " + e);
            }
            closeSocket();
            releasePttIfHeld();
            if (!mRunning) {
                break;
            }
            try {
                Thread.sleep(SPP_RETRY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Is this bonded device a PTT speaker-microphone with a serial port? */
    @SuppressLint("MissingPermission")
    private static boolean isCandidate(BluetoothDevice device) {
        ParcelUuid[] uuids = device.getUuids();
        boolean hasSpp = false;
        if (uuids != null) {
            for (ParcelUuid u : uuids) {
                if (SPP_UUID.equals(u.getUuid())) {
                    hasSpp = true;
                    break;
                }
            }
        }
        String name = device.getName();
        String n = name != null ? name.toUpperCase(Locale.ROOT) : "";
        boolean nameMatch = n.contains("PTT") || n.contains("BM001") || n.contains("UNIWA")
                || n.contains("B01") || n.contains("B02") || n.contains("MIC");
        // SPP + headset/audio device, or a known name (UUID list may be empty before SDP).
        return nameMatch || (hasSpp && device.getBluetoothClass() != null
                && device.getBluetoothClass().getMajorDeviceClass()
                == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO);
    }

    @SuppressLint("MissingPermission")
    private void connectAndRead() throws IOException {
        if (!hasConnectPermission()) {
            Log.d(TAG, "SPP: BLUETOOTH_CONNECT permission not granted");
            return;
        }
        BluetoothAdapter adapter = getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) {
            return;
        }
        for (BluetoothDevice device : bonded) {
            if (!mRunning) {
                return;
            }
            if (!isCandidate(device)) {
                continue;
            }
            BluetoothSocket socket = openSocket(adapter, device);
            if (socket == null) {
                continue;
            }
            mSocket = socket;
            Log.i(TAG, "SPP connected to " + device.getName() + " " + device.getAddress());
            readLoop(socket.getInputStream());
            Log.i(TAG, "SPP disconnected from " + device.getName());
            return;
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket openSocket(BluetoothAdapter adapter, BluetoothDevice device) {
        try {
            adapter.cancelDiscovery();
        } catch (Exception ignored) {
        }
        // 1) standard secure SPP, 2) insecure SPP (many cheap microphones need it)
        for (int attempt = 0; attempt < 2; attempt++) {
            BluetoothSocket s = null;
            try {
                s = attempt == 0
                        ? device.createRfcommSocketToServiceRecord(SPP_UUID)
                        : device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                s.connect();
                return s;
            } catch (IOException e) {
                Log.d(TAG, "SPP connect to " + device.getName() + " failed (" + attempt + "): " + e);
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return null;
    }

    private void readLoop(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        byte[] buf = new byte[256];
        while (mRunning) {
            int n = in.read(buf);
            if (n < 0) {
                break;
            }
            for (int i = 0; i < n; i++) {
                char c = (char) (buf[i] & 0xFF);
                if (c == '\r' || c == '\n' || c == 0) {
                    flushToken(line);
                } else {
                    line.append(c);
                    // Some models send "+PTT=P" without a line terminator.
                    if (line.length() >= 3 && isCompleteToken(line.toString())) {
                        flushToken(line);
                    } else if (line.length() > 64) {
                        flushToken(line);
                    }
                }
            }
        }
    }

    private static boolean isCompleteToken(String s) {
        String t = s.toUpperCase(Locale.ROOT);
        return t.matches(".*=[PRUD]$") || t.endsWith("VOL+") || t.endsWith("VOL-")
                || t.endsWith("VOLUP") || t.endsWith("VOLDOWN");
    }

    private void flushToken(StringBuilder line) {
        if (line.length() == 0) {
            return;
        }
        final String token = line.toString().trim();
        line.setLength(0);
        if (token.isEmpty()) {
            return;
        }
        Log.i(TAG, "SPP rx: \"" + token + "\"");
        mMainHandler.post(() -> handleSppToken(token));
    }

    /** Called on the main thread. */
    private void handleSppToken(String token) {
        if (!mRunning) {
            return;
        }
        final String t = token.toUpperCase(Locale.ROOT).replace(" ", "");
        // SOS / emergency key: not handled (it must not key the transmitter).
        if (t.contains("PTTE")) {
            return;
        }
        // Group/channel keys of the Zello protocol (+PTTB1 / +PTTB2): not PTT.
        if (t.contains("PTTB")) {
            return;
        }
        if (t.contains("PTT")) {
            if (t.endsWith("=P") || t.endsWith("=1") || t.endsWith("PRESS") || t.endsWith("DOWN")) {
                if (!mSppPttDown) {
                    mSppPttDown = true;
                    mListener.onBtPttDown();
                }
                return;
            }
            if (t.endsWith("=R") || t.endsWith("=0") || t.endsWith("RELEASE") || t.endsWith("UP")) {
                if (mSppPttDown) {
                    mSppPttDown = false;
                    mListener.onBtPttUp();
                }
                return;
            }
        }
        if (t.contains("VOL") || t.startsWith("+VG")) {
            if (t.contains("UP") || t.endsWith("+") || t.endsWith("=U") || t.endsWith("=P")) {
                mListener.onBtVolume(true);
                return;
            }
            if (t.contains("DOWN") || t.contains("DN") || t.endsWith("-") || t.endsWith("=D")) {
                mListener.onBtVolume(false);
                return;
            }
        }
        Log.i(TAG, "SPP unknown token: \"" + token + "\"");
    }

    private void releasePttIfHeld() {
        mMainHandler.post(() -> {
            if (mSppPttDown) {
                mSppPttDown = false;
                mListener.onBtPttUp();
            }
            if (mMediaPttDown) {
                mMediaPttDown = false;
                mListener.onBtPttUp();
            }
        });
    }

    private void closeSocket() {
        BluetoothSocket s = mSocket;
        mSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
