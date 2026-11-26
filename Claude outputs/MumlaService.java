/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.lublin.humla.Constants;
import se.lublin.humla.HumlaService;
import se.lublin.humla.audio.MicGate;
import se.lublin.humla.exception.AudioException;
import se.lublin.humla.model.IMessage;
import se.lublin.humla.model.IUser;
import se.lublin.humla.model.Message;
import se.lublin.humla.model.Server;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.util.HumlaException;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.ServerConnectTask;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.service.ipc.TalkBroadcastReceiver;
import se.lublin.mumla.util.HtmlUtils;

/**
 * An extension of the Humla service with some added Mumla-exclusive non-standard Mumble features.
 * Created by andrew on 28/07/13.
 */
public class MumlaService extends HumlaService implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        MumlaConnectionNotification.OnActionListener,
        MumlaReconnectNotification.OnActionListener, IMumlaService {
    private static final String TAG = MumlaService.class.getName();

    /** Undocumented constant that permits a proximity-sensing wake lock. */
    public static final int PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32;
    public static final int TTS_THRESHOLD = 250; // Maximum number of characters to read
    public static final int RECONNECT_DELAY = 10000;
    /** IW2DPO: how often the reconnect watchdog checks whether we're still connected to the
     * last used server. */
    public static final long RECONNECT_WATCHDOG_INTERVAL_MS = 60000;

    private Settings mSettings;
    private MumlaConnectionNotification mNotification;
    private MumlaMessageNotification mMessageNotification;
    private MumlaReconnectNotification mReconnectNotification;
    /** IW2DPO: own database handle used by the reconnect watchdog to look up the last used
     * server; opened in onCreate(), closed in onDestroy(). */
    private MumlaDatabase mWatchdogDatabase;
    private Handler mReconnectWatchdogHandler;
    private final Runnable mReconnectWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                checkAutoReconnect();
            } finally {
                mReconnectWatchdogHandler.postDelayed(this, RECONNECT_WATCHDOG_INTERVAL_MS);
            }
        }
    };
    /** Channel view overlay. */
    private MumlaOverlay mChannelOverlay;
    /** Proximity lock for handset mode. */
    private PowerManager.WakeLock mProximityLock;
    /** IW2DPO: wake lock che ACCENDE il display quando arriva/parte una comunicazione
     * (RX da altri utenti o PTT locale). Se "Stay awake" e' selezionato lo tiene acceso
     * sempre, altrimenti per SCREEN_WAKE_TIMEOUT_MS. */
    private PowerManager.WakeLock mScreenWakeLock;
    /** IW2DPO: display acceso per 1 minuto dall'ultima attivita' vocale (Stay awake NON selezionato). */
    public static final long SCREEN_WAKE_TIMEOUT_MS = 60 * 1000L;
    /** Play sound when push to talk key is pressed */
    private boolean mPTTSoundEnabled;
    /** Try to shorten spoken messages when using TTS */
    private boolean mShortTtsMessagesEnabled;
    /**
     * True if an error causing disconnection has been dismissed by the user.
     * This should serve as a hint not to bother the user.
     */
    private boolean mErrorShown;
    private List<IChatMessage> mMessageLog;
    private boolean mSuppressNotifications;

    private TextToSpeech mTTS;
    private TextToSpeech.OnInitListener mTTSInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            if(status == TextToSpeech.ERROR)
                logWarning(getString(R.string.tts_failed));
        }
    };

    /** The view representing the hot corner. */
    private MumlaHotCorner mHotCorner;
    private MumlaHotCorner.MumlaHotCornerListener mHotCornerListener = new MumlaHotCorner.MumlaHotCornerListener() {
        @Override
        public void onHotCornerDown() {
            onTalkKeyDown();
        }

        @Override
        public void onHotCornerUp() {
            onTalkKeyUp();
        }
    };

    private BroadcastReceiver mTalkReceiver;

    /** IW2DPO: PTT release debouncing. */
    private final Handler mPttDebounceHandler = new Handler(Looper.getMainLooper());
    /** IW2DPO: last known physical state of the PTT button. */
    private boolean mPttKeyDown = false;
    /** IW2DPO: true while a debounced PTT release is waiting to be confirmed. */
    private boolean mPttReleasePending = false;
    /** IW2DPO: local "ready / not ready to talk" tones played on PTT press. */
    private LocalTonePlayer mLocalTonePlayer;
    /** IW2DPO 22-11-2026-3: sessions of the other users currently talking (RX).
     * The INCOMING TONE is played when this set goes from empty to non-empty. */
    private final java.util.Set<Integer> mIncomingTalkers = new java.util.HashSet<>();
    /**
     * IW2DPO 25-11-2026-1: BLUETOOTH OPTION KEPT ENABLED.
     * Before, the Bluetooth checkbox only reflected the momentary SCO state: when the BT
     * accessory went out of range, on server change and at power off Humla stopped SCO
     * and the option came back DISABLED. Now the user choice is saved in Settings and,
     * while it is enabled and we are connected to a server, SCO is restarted
     * automatically as soon as a Bluetooth headset (SCO device) is available again.
     */
    private static final long BT_SCO_RESTORE_DELAY_MS = 1500;
    private static final long BT_SCO_WATCHDOG_INTERVAL_MS = 10000;
    private static final long BT_SCO_MIN_RETRY_MS = 8000;
    private final Handler mBtScoHandler = new Handler(Looper.getMainLooper());
    private long mLastBtScoStartMs = 0;
    private final Runnable mBtScoRestoreRunnable = new Runnable() {
        @Override
        public void run() {
            restoreBluetoothScoIfWanted();
        }
    };
    private final Runnable mBtScoWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            restoreBluetoothScoIfWanted();
            mBtScoHandler.postDelayed(this, BT_SCO_WATCHDOG_INTERVAL_MS);
        }
    };
    private final AudioDeviceCallback mBtScoDeviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            for (AudioDeviceInfo d : addedDevices) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    Log.i(TAG, "BT SCO device back: restoring Bluetooth audio");
                    scheduleBluetoothScoRestore();
                    return;
                }
            }
        }
    };
    /** Restore also after an SCO drop (e.g. BT accessory out of range). */
    private final BroadcastReceiver mBtScoStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR);
            if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                scheduleBluetoothScoRestore();
            }
        }
    };

    /** IW2DPO 19-11-2026-2: Bluetooth speaker-microphone (UNIWA BM001...) PTT and volume keys. */
    private BluetoothPttMic mBtPttMic;
    private final BluetoothPttMic.Listener mBtPttMicListener = new BluetoothPttMic.Listener() {
        @Override
        public void onBtPttDown() {
            onTalkKeyDown();
        }

        @Override
        public void onBtPttUp() {
            onTalkKeyUp();
        }

        @Override
        public void onBtVolume(boolean raise) {
            // Any key closes the TX TIMEOUT popup (same as the radio keys in MumlaActivity)
            if (mTxTimeoutActive) {
                clearTxTimeout();
                return;
            }
            final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            try {
                am.adjustStreamVolume(getAudioStream(),
                        raise ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI);
            } catch (SecurityException e) {
                Log.w(TAG, "BT mic volume change refused", e);
            }
        }

        @Override
        public int getConfiguredPttKey() {
            return mSettings.getPushToTalkKey();
        }

        @Override
        public int getConfiguredVolumeUpKey() {
            return mSettings.getVolumeUpKey();
        }

        @Override
        public int getConfiguredVolumeDownKey() {
            return mSettings.getVolumeDownKey();
        }

        @Override
        public int getAudioStream() {
            return mSettings.isHandsetMode()
                    ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
        }
    };
    /** IW2DPO: number of local tones still playing (mic gate closed while > 0). */
    private int mLocalTonesPlaying = 0;
    /** IW2DPO: mic gate kept closed after the beep has ended on the loudspeaker
     * (covers mic capture latency and room echo). 16-11-2026-3: back to 200 ms. */
    public static final long MIC_GATE_TAIL_MS = 200;
    /** IW2DPO: safety limit, the mic gate is never closed longer than this per beep. */
    public static final long MIC_GATE_MAX_MS = 3000;
    /** IW2DPO: called on the main thread when a local tone has finished on the speaker:
     * the mic gate reopens after {@link #MIC_GATE_TAIL_MS}. */
    private final Runnable mLocalToneDoneRunnable = new Runnable() {
        @Override
        public void run() {
            if (mLocalTonesPlaying > 0) mLocalTonesPlaying--;
            if (mLocalTonesPlaying == 0) {
                // beep over: voice goes out after MIC_GATE_TAIL_MS (200 ms)
                MicGate.closeUntil(SystemClock.uptimeMillis() + MIC_GATE_TAIL_MS);
            }
        }
    };
    /** IW2DPO 19-11-2026-1: true after a TX TIMEOUT until any key is pressed
     * (popup "TX TIMEOUT" shown, PTT does not transmit). */
    private boolean mTxTimeoutActive = false;
    /** IW2DPO 19-11-2026-1: the PTT press that closed the TX TIMEOUT popup must not
     * transmit, and its release must not toggle TX. */
    private boolean mPttSwallowRelease = false;
    private TxTimeoutListener mTxTimeoutListener;
    /** IW2DPO 19-11-2026-1: fires when PTT has been transmitting for longer than
     * Settings > Audio > TX TIMEOUT. */
    private final Runnable mTxTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            onTxTimeout();
        }
    };
    /** IW2DPO: runs after the debounce delay and re-checks the PTT button state. */
    private final Runnable mPttReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            mPttReleasePending = false;
            if (!mPttKeyDown) {
                handleTalkKeyUp(); // still released after the delay: confirm release
            }
            // else: PTT pressed again during the delay (bounce): keep transmitting
        }
    };

    private HumlaObserver mObserver = new HumlaObserver() {
        @Override
        public void onConnecting() {
            // Remove old notification left from reconnect,
            if (mReconnectNotification != null) {
                mReconnectNotification.hide();
                mReconnectNotification = null;
            }

            final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
            mNotification = MumlaConnectionNotification.create(MumlaService.this,
                    getString(R.string.mumlaConnecting) + tor,
                    MumlaService.this);
            mNotification.show();

            mErrorShown = false;
        }

        @Override
        public void onConnected() {
            if (mNotification != null) {
                final String tor = mSettings.isTorEnabled() ? " (Tor)" : "";
                mNotification.setCustomContentText(getString(R.string.connected) + tor);
                mNotification.setActionsShown(true);
                mNotification.show();
            }

            // IW2DPO: remember this as the "last used server", so a future launch (or the
            // reconnect watchdog) can log back into it automatically.
            Server target = getTargetServer();
            if (target != null) {
                mSettings.setLastServerId(target.getId());
            }
        }

        @Override
        public void onDisconnected(HumlaException e) {
            synchronized (mIncomingTalkers) { mIncomingTalkers.clear(); } // IW2DPO 22-11-2026-3
            // IW2DPO: an unexpected drop from the last used server, with automatic
            // connect/reconnect enabled -- keep the foreground notification up (instead of
            // hiding it) so Android doesn't kill this service while the watchdog waits for
            // its next 60-second check to reconnect us.
            boolean watchdogWillRetry = e != null
                    && mSettings.isAutoconnectEnabled()
                    && isLastUsedServer(getTargetServer());
            if (mNotification != null) {
                if (watchdogWillRetry) {
                    mNotification.setCustomContentText(getString(R.string.autoconnectReconnecting));
                    mNotification.show();
                } else {
                    mNotification.hide();
                    mNotification = null;
                }
            }
            if (e != null && !mSuppressNotifications) {
                mReconnectNotification =
                        MumlaReconnectNotification.show(MumlaService.this,
                                e.getMessage() + (mSettings.isTorEnabled() ? " (Tor)" : ""),
                                isReconnecting(), MumlaService.this);
            }
        }

        /** IW2DPO 22-11-2026-3: a user who leaves is no longer talking. */
        @Override
        public void onUserRemoved(IUser user, String reason) {
            if (user != null) {
                synchronized (mIncomingTalkers) { mIncomingTalkers.remove(user.getSession()); }
            }
        }

        /** IW2DPO 22-11-2026-3: a user who changes channel is no longer talking here. */
        @Override
        public void onUserJoinedChannel(IUser user, se.lublin.humla.model.IChannel newChannel,
                                        se.lublin.humla.model.IChannel oldChannel) {
            if (user != null) {
                synchronized (mIncomingTalkers) { mIncomingTalkers.remove(user.getSession()); }
            }
        }

        @Override
        public void onUserConnected(IUser user) {
            if (user.getTextureHash() != null &&
                    user.getTexture() == null) {
                // Request avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onUserStateUpdated(IUser user) {
            if (user == null) {
                return;
            }

            int selfSession;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserStateUpdated: " + e);
                return;
            }

            if (user.getSession() == selfSession) {
                mSettings.setMutedAndDeafened(user.isSelfMuted(), user.isSelfDeafened()); // Update settings mute/deafen state
                if(mNotification != null) {
                    String contentText;
                    if (user.isSelfMuted() && user.isSelfDeafened())
                        contentText = getString(R.string.status_notify_muted_and_deafened);
                    else if (user.isSelfMuted())
                        contentText = getString(R.string.status_notify_muted);
                    else
                        contentText = getString(R.string.connected);
                    mNotification.setCustomContentText(contentText);
                    mNotification.show();
                }
            }

            if (user.getTextureHash() != null && user.getTexture() == null) {
                // Update avatar data if available.
                requestAvatar(user.getSession());
            }
        }

        @Override
        public void onMessageLogged(IMessage message) {
            // Split on / strip all HTML tags.
            Document parsedMessage = Jsoup.parseBodyFragment(message.getMessage());
            String strippedMessage = parsedMessage.text();

            String ttsMessage;
            if(mShortTtsMessagesEnabled) {
                for (Element anchor : parsedMessage.getElementsByTag("A")) {
                    // Get just the domain portion of links
                    String href = anchor.attr("href");
                    // Only shorten anchors without custom text
                    if (href != null && href.equals(anchor.text())) {
                        String urlHostname = HtmlUtils.getHostnameFromLink(href);
                        if (urlHostname != null) {
                            anchor.text(getString(R.string.chat_message_tts_short_link, urlHostname));
                        }
                    }
                }
                ttsMessage = parsedMessage.text();
            } else {
                ttsMessage = strippedMessage;
            }

            String formattedTtsMessage = getString(R.string.notification_message,
                    message.getActorName(), ttsMessage);

            // Read if TTS is enabled, the message is less than threshold, is a text message, and not deafened
            if(mSettings.isTextToSpeechEnabled() &&
                    mTTS != null &&
                    formattedTtsMessage.length() <= TTS_THRESHOLD &&
                    getSessionUser() != null &&
                    !getSessionUser().isSelfDeafened()) {
                mTTS.speak(formattedTtsMessage, TextToSpeech.QUEUE_ADD, null);
            }

            // TODO: create a customizable notification sieve
            if (mSettings.isChatNotifyEnabled()) {
                mMessageNotification.show(message);
            }

            mMessageLog.add(new IChatMessage.TextMessage(message));
        }

        @Override
        public void onLogInfo(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message));
        }

        @Override
        public void onLogWarning(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message));
        }

        @Override
        public void onLogError(String message) {
            mMessageLog.add(new IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message));
        }

        @Override
        public void onPermissionDenied(String reason) {
            if(mNotification != null && !mSuppressNotifications) {
                mNotification.show();
            }
        }

        @Override
        public void onUserTalkStateUpdated(IUser user) {
            int selfSession = -1;
            try {
                selfSession = getSessionId();
            } catch (IllegalStateException e) {
                Log.d(TAG, "exception in onUserTalkStateUpdated: " + e);
            }

            // IW2DPO: qualcuno parla (RX dal canale oppure il nostro PTT):
            // accende il display (sempre acceso se Stay awake, altrimenti 1 minuto).
            if (user != null && (user.getTalkState() == TalkState.TALKING
                    || user.getTalkState() == TalkState.SHOUTING
                    || user.getTalkState() == TalkState.WHISPERING)) {
                wakeScreen();
            }
            // IW2DPO 22-11-2026-3: INCOMING TONE when the channel becomes active
            handleIncomingTone(user, selfSession);

            if (isConnectionEstablished() &&
                    user.getSession() == selfSession &&
                    getTransmitMode() == Constants.TRANSMIT_PUSH_TO_TALK &&
                    user.getTalkState() == TalkState.TALKING &&
                    mPTTSoundEnabled) {
                AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
                audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, -1);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerObserver(mObserver);

        // Register for preference changes
        mSettings = Settings.getInstance(this);
        mPTTSoundEnabled = mSettings.isPttSoundEnabled();
        mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.registerOnSharedPreferenceChangeListener(this);

        // Manually set theme to style overlay views
        // XML <application> theme does NOT do this!
        setTheme(R.style.Theme_Mumla);

        mMessageLog = new ArrayList<>();
        mMessageNotification = new MumlaMessageNotification(MumlaService.this);

        // Instantiate overlay view
        mChannelOverlay = new MumlaOverlay(this);
        mHotCorner = new MumlaHotCorner(this, mSettings.getHotCornerGravity(), mHotCornerListener);

        // Set up TTS
        if(mSettings.isTextToSpeechEnabled())
            mTTS = new TextToSpeech(this, mTTSInitListener);

        mTalkReceiver = new TalkBroadcastReceiver(this);

        mLocalTonePlayer = new LocalTonePlayer(); // IW2DPO
        MicGate.open(); // IW2DPO

        // IW2DPO 19-11-2026-2: PTT / V+ / V- keys of the Bluetooth speaker-microphone
        mBtPttMic = new BluetoothPttMic(this, mBtPttMicListener);
        mBtPttMic.start();

        // IW2DPO 25-11-2026-1: keep the Bluetooth option enabled (auto restore of SCO)
        AudioManager btAm = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (btAm != null) {
            btAm.registerAudioDeviceCallback(mBtScoDeviceCallback, mBtScoHandler);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mBtScoStateReceiver,
                    new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), RECEIVER_EXPORTED);
        } else {
            registerReceiver(mBtScoStateReceiver,
                    new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        }
        mBtScoHandler.postDelayed(mBtScoWatchdogRunnable, BT_SCO_WATCHDOG_INTERVAL_MS);

        // IW2DPO: start the reconnect watchdog. checkAutoReconnect() itself does nothing
        // unless the user has enabled automatic connect/reconnect and has used a server
        // at least once before, so this is a no-op for everyone else.
        mWatchdogDatabase = new MumlaSQLiteDatabase(this);
        mWatchdogDatabase.open();
        mReconnectWatchdogHandler = new Handler(getMainLooper());
        mReconnectWatchdogHandler.postDelayed(mReconnectWatchdogRunnable, RECONNECT_WATCHDOG_INTERVAL_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MumlaBinder(this);
    }

    @Override
    public void onDestroy() {
        mPttDebounceHandler.removeCallbacks(mPttReleaseRunnable); // IW2DPO
        mPttDebounceHandler.removeCallbacks(mLocalToneDoneRunnable); // IW2DPO
        mPttDebounceHandler.removeCallbacks(mTxTimeoutRunnable); // IW2DPO 19-11-2026-1
        mTxTimeoutListener = null;
        if (mBtPttMic != null) { // IW2DPO 19-11-2026-2
            mBtPttMic.stop();
            mBtPttMic = null;
        }
        // IW2DPO 25-11-2026-1: stop the Bluetooth option auto restore
        mBtScoHandler.removeCallbacksAndMessages(null);
        AudioManager btAm = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (btAm != null) {
            btAm.unregisterAudioDeviceCallback(mBtScoDeviceCallback);
        }
        try {
            unregisterReceiver(mBtScoStateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        MicGate.open(); // IW2DPO
        if (mLocalTonePlayer != null) { // IW2DPO
            mLocalTonePlayer.release();
            mLocalTonePlayer = null;
        }
        // IW2DPO: rilascia il wake lock del display
        if (mScreenWakeLock != null && mScreenWakeLock.isHeld()) {
            mScreenWakeLock.release();
        }
        if (mReconnectWatchdogHandler != null) {
            mReconnectWatchdogHandler.removeCallbacks(mReconnectWatchdogRunnable);
        }
        if (mWatchdogDatabase != null) {
            mWatchdogDatabase.close();
        }

        if (mNotification != null) {
            mNotification.hide();
            mNotification = null;
        }
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }

        unregisterObserver(mObserver);
        if(mTTS != null) mTTS.shutdown();
        mMessageLog = null;
        mMessageNotification.dismiss();
        super.onDestroy();
    }

    /**
     * IW2DPO: checks, every {@link #RECONNECT_WATCHDOG_INTERVAL_MS}, whether we're connected
     * to the last used server; if not (and no connection attempt is already under way),
     * reconnects on its own. No-op unless automatic connect/reconnect is enabled and a
     * server has actually been used before.
     */
    private void checkAutoReconnect() {
        if (!mSettings.isAutoconnectEnabled()) {
            return;
        }
        long lastServerId = mSettings.getLastServerId();
        if (lastServerId < 0) {
            return;
        }
        if (isConnected() || getConnectionState() == ConnectionState.CONNECTING || isReconnecting()) {
            return; // already connected, already connecting, or the library's own auto-reconnect is already retrying
        }

        Server lastServer = null;
        for (Server server : mWatchdogDatabase.getServers()) {
            if (server.getId() == lastServerId) {
                lastServer = server;
                break;
            }
        }
        if (lastServer == null) {
            return; // last used server no longer exists (e.g. removed by the user)
        }

        Log.i(TAG, "Reconnect watchdog: not connected to last used server \"" + lastServer.getName()
                + "\", reconnecting automatically");
        new ServerConnectTask(this, mWatchdogDatabase).execute(lastServer);
    }

    /** IW2DPO: true if the given server is the last one we successfully connected to. */
    private boolean isLastUsedServer(Server server) {
        return server != null && server.getId() == mSettings.getLastServerId();
    }

    /**
     * IW2DPO: accende il display (anche se e' in sleep).
     * - Stay awake selezionato:     lo tiene acceso sempre (nessun timeout).
     * - Stay awake NON selezionato: lo tiene acceso per {@link #SCREEN_WAKE_TIMEOUT_MS}
     *   (1 minuto) dall'ultima attivita' vocale.
     * Chiamato sia in ricezione (qualcuno parla sul canale) sia col PTT.
     * FLAG_KEEP_SCREEN_ON da solo non basta: non riaccende uno schermo gia' spento
     * e funziona solo con l'Activity in primo piano. Questo wake lock invece
     * vive nel Service e funziona anche a schermo spento.
     */
    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            if (mScreenWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                // IW2DPO: niente ON_AFTER_RELEASE: al rilascio faceva ripartire il timeout
                // di sistema e lo schermo restava acceso 1 min + 1 min = 2 minuti.
                mScreenWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Mumla:VoiceWake");
                mScreenWakeLock.setReferenceCounted(false);
            }
            if (mSettings != null && mSettings.shouldStayAwake()) {
                // Stay awake selezionato: nessun timeout.
                mScreenWakeLock.acquire();
            } else {
                // Ogni nuova chiamata fa ripartire da zero il minuto.
                mScreenWakeLock.acquire(SCREEN_WAKE_TIMEOUT_MS);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "wakeScreen() failed", e);
        }
    }

    @Override
    public void onConnectionSynchronized() {
        // TODO? We seem to be getting a RuntimeException here, from the call
        //  to the superclass function (in HumlaService). In there,
        //  mConnect.getSession() finds that isSynchronized==false and throws
        //  NotSynchronizedException (which is re-thrown as the
        //  RuntimeException). But how can it be !isSynchronized? -- A server
        //  msg triggers HumlaConnection.messageServerSync(), which sets up
        //  mSession and mSynchronized==true and then proceeds to call us from
        //  a Runnable post()ed to a Handler. The reason could only be that
        //  HumlaConnect.connect() or disconnect() is called again in the
        //  middle of all this? And it's made possible by the Handler?
        try {
            super.onConnectionSynchronized();
        } catch (RuntimeException e) {
            Log.d(TAG, "exception in onConnectionSynchronized: " + e);
            return;
        }

        // Restore mute/deafen state
        if(mSettings.isMuted() || mSettings.isDeafened()) {
            setSelfMuteDeafState(mSettings.isMuted(), mSettings.isDeafened());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK), RECEIVER_EXPORTED);
        } else {
            registerReceiver(mTalkReceiver, new IntentFilter(TalkBroadcastReceiver.BROADCAST_TALK));
        }

        if (mSettings.isHotCornerEnabled()) {
            mHotCorner.setShown(true);
        }
        // Configure proximity sensor
        if (mSettings.isHandsetMode()) {
            setProximitySensorOn(true);
        }

        // IW2DPO 25-11-2026-1: re-enable Bluetooth audio after (re)connection, server
        // change or POC power on, if the user had enabled the Bluetooth option.
        scheduleBluetoothScoRestore();
    }

    /** IW2DPO 25-11-2026-1: restore SCO shortly (lets the BT stack settle). */
    private void scheduleBluetoothScoRestore() {
        mBtScoHandler.removeCallbacks(mBtScoRestoreRunnable);
        mBtScoHandler.postDelayed(mBtScoRestoreRunnable, BT_SCO_RESTORE_DELAY_MS);
    }

    /**
     * IW2DPO 25-11-2026-1: if the Bluetooth option is enabled (saved), we are connected
     * to a server, SCO is off and a Bluetooth headset is available, restart SCO.
     * Nothing is done when no BT headset is present (no useless audio restarts).
     */
    private void restoreBluetoothScoIfWanted() {
        if (mSettings == null || !mSettings.isBluetoothScoEnabled()) return;
        if (!isSynchronized()) return;
        try {
            if (usingBluetoothSco()) return;
        } catch (Exception e) {
            return;
        }
        if (!isBluetoothScoDeviceAvailable()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastBtScoStartMs < BT_SCO_MIN_RETRY_MS) return;
        mLastBtScoStartMs = now;
        try {
            Log.i(TAG, "Bluetooth option enabled: starting Bluetooth SCO");
            enableBluetoothSco();
        } catch (Exception e) {
            Log.w(TAG, "enableBluetoothSco failed: " + e);
        }
    }

    /** IW2DPO 25-11-2026-1: true if a Bluetooth headset (SCO) is connected to the POC. */
    private boolean isBluetoothScoDeviceAvailable() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
        }
        return false;
    }

    @Override
    public void onConnectionDisconnected(HumlaException e) {
        super.onConnectionDisconnected(e);
        try {
            unregisterReceiver(mTalkReceiver);
        } catch (IllegalArgumentException iae) {
        }

        // Remove overlay if present.
        mChannelOverlay.hide();

        mHotCorner.setShown(false);

        setProximitySensorOn(false);

        clearMessageLog();
        mMessageNotification.dismiss();
    }

    /**
     * Called when the user makes a change to their preferences.
     * Should update all preferences relevant to the service.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Bundle changedExtras = new Bundle();
        boolean requiresReconnect = false;
        switch (key) {
            case Settings.PREF_STAY_AWAKE:
                // IW2DPO: Stay awake deselezionato -> il wake lock senza timeout
                // (se tenuto) passa al timeout di 1 minuto.
                if (mScreenWakeLock != null && mScreenWakeLock.isHeld()
                        && !mSettings.shouldStayAwake()) {
                    mScreenWakeLock.acquire(SCREEN_WAKE_TIMEOUT_MS);
                }
                break;
            case Settings.PREF_INPUT_METHOD:
                /* Convert input method defined in settings to an integer format used by Humla. */
                int inputMethod = mSettings.getHumlaInputMethod();
                changedExtras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, inputMethod);
                mChannelOverlay.setPushToTalkShown(inputMethod == Constants.TRANSMIT_PUSH_TO_TALK);
                break;
            case Settings.PREF_HANDSET_MODE:
                setProximitySensorOn(isConnectionEstablished() && mSettings.isHandsetMode());
                changedExtras.putInt(HumlaService.EXTRAS_AUDIO_STREAM, mSettings.isHandsetMode() ?
                                     AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                if (mBtPttMic != null) mBtPttMic.updateAudioStream(); // IW2DPO 19-11-2026-2
                break;
            case Settings.PREF_THRESHOLD:
                changedExtras.putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD,
                        mSettings.getDetectionThreshold());
                break;
            case Settings.PREF_HOT_CORNER_KEY:
                mHotCorner.setGravity(mSettings.getHotCornerGravity());
                mHotCorner.setShown(isConnectionEstablished() && mSettings.isHotCornerEnabled());
                break;
            case Settings.PREF_USE_TTS:
                if (mTTS == null && mSettings.isTextToSpeechEnabled())
                    mTTS = new TextToSpeech(this, mTTSInitListener);
                else if (mTTS != null && !mSettings.isTextToSpeechEnabled()) {
                    mTTS.shutdown();
                    mTTS = null;
                }
                break;
            case Settings.PREF_SHORT_TTS_MESSAGES:
                mShortTtsMessagesEnabled = mSettings.isShortTextToSpeechMessagesEnabled();
                break;
            case Settings.PREF_AMPLITUDE_BOOST:
                changedExtras.putFloat(EXTRAS_AMPLITUDE_BOOST,
                        mSettings.getAmplitudeBoostMultiplier());
                break;
            case Settings.PREF_HALF_DUPLEX:
                changedExtras.putBoolean(EXTRAS_HALF_DUPLEX, mSettings.isHalfDuplex());
                break;
            case Settings.PREF_PREPROCESSOR_ENABLED:
                changedExtras.putBoolean(EXTRAS_ENABLE_PREPROCESSOR,
                        mSettings.isPreprocessorEnabled());
                break;
            case Settings.PREF_ECHO_CANCELLATION_METHOD:
                changedExtras.putString(EXTRAS_ECHO_CANCELLATION_METHOD,
                        mSettings.getEchoCancellationMethod());
                break;
            case Settings.PREF_RELEASE_BEEP:
                changedExtras.putBoolean(EXTRAS_RELEASE_BEEP, mSettings.isReleaseBeepEnabled());
                break;
            case Settings.PREF_RELEASE_BEEP_VOLUME:
                changedExtras.putInt(EXTRAS_RELEASE_BEEP_VOLUME, mSettings.getReleaseBeepVolume());
                break;
            case Settings.PREF_RELEASE_BEEP_SOUND:
                changedExtras.putString(EXTRAS_RELEASE_BEEP_SOUND, mSettings.getReleaseBeepSound());
                break;
            case Settings.PREF_PTT_SOUND:
                mPTTSoundEnabled = mSettings.isPttSoundEnabled();
                break;
            case Settings.PREF_INPUT_QUALITY:
                changedExtras.putInt(EXTRAS_INPUT_QUALITY, mSettings.getInputQuality());
                break;
            case Settings.PREF_INPUT_RATE:
                changedExtras.putInt(EXTRAS_INPUT_RATE, mSettings.getInputSampleRate());
                break;
            case Settings.PREF_FRAMES_PER_PACKET:
                changedExtras.putInt(EXTRAS_FRAMES_PER_PACKET, mSettings.getFramesPerPacket());
                break;
            case Settings.PREF_CERT_ID:
            case Settings.PREF_FORCE_TCP:
            case Settings.PREF_USE_TOR:
            case Settings.PREF_DISABLE_OPUS:
                // These are settings we flag as 'requiring reconnect'.
                requiresReconnect = true;
                break;
        }
        if (changedExtras.size() > 0) {
            try {
                // Reconfigure the service appropriately.
                requiresReconnect |= configureExtras(changedExtras);
            } catch (AudioException e) {
                e.printStackTrace();
            }
        }

        if (requiresReconnect && isConnectionEstablished()) {
            Toast.makeText(this, R.string.change_requires_reconnect, Toast.LENGTH_LONG).show();
        }
    }

    private void setProximitySensorOn(boolean on) {
        if(on) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            mProximityLock = pm.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "Mumla:Proximity");
            mProximityLock.acquire();
        } else {
            if(mProximityLock != null) mProximityLock.release();
            mProximityLock = null;
        }
    }

    @Override
    public void onMuteToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            boolean muted = !user.isSelfMuted();
            boolean deafened = user.isSelfDeafened() && muted;
            setSelfMuteDeafState(muted, deafened);
        }
    }

    @Override
    public void onDeafenToggled() {
        IUser user = getSessionUser();
        if (isConnectionEstablished() && user != null) {
            setSelfMuteDeafState(!user.isSelfDeafened(), !user.isSelfDeafened());
        }
    }

    @Override
    public void onOverlayToggled() {
        // Ditch notification shade/panel to make overlay presence/permission request visible.
        // But on Android 12 that's no longer allowed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Intent close = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            getApplicationContext().sendBroadcast(close);
        }

        if (!mChannelOverlay.isShown()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!android.provider.Settings.canDrawOverlays(getApplicationContext())) {
                    Intent showSetting = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    showSetting.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(showSetting);
                    Toast.makeText(this, R.string.grant_perm_draw_over_apps, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public void onReconnectNotificationDismissed() {
        mErrorShown = true;
    }

    @Override
    public void reconnect() {
        connect();
    }

    @Override
    public void cancelReconnect() {
        if (mReconnectNotification != null) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
        super.cancelReconnect();
    }

    @Override
    public void setOverlayShown(boolean showOverlay) {
        if(!mChannelOverlay.isShown()) {
            mChannelOverlay.show();
        } else {
            mChannelOverlay.hide();
        }
    }

    @Override
    public boolean isOverlayShown() {
        return mChannelOverlay.isShown();
    }

    @Override
    public void clearChatNotifications() {
        mMessageNotification.dismiss();
    }

    @Override
    public void markErrorShown() {
        mErrorShown = true;
        // Dismiss the reconnection prompt if a reconnection isn't in progress.
        if (mReconnectNotification != null && !isReconnecting()) {
            mReconnectNotification.hide();
            mReconnectNotification = null;
        }
    }

    @Override
    public boolean isErrorShown() {
        return mErrorShown;
    }

    /**
     * Called when a user presses a talk key down (i.e. when they want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyDown() {
        // IW2DPO: a new press (not a key auto-repeat, not a bounce during the debounce delay)
        final boolean freshPress = !mPttKeyDown && !mPttReleasePending;
        // IW2DPO: a press during the debounce delay cancels the pending release
        mPttKeyDown = true;
        wakeScreen(); // IW2DPO: il PTT accende subito il display
        mPttDebounceHandler.removeCallbacks(mPttReleaseRunnable);
        mPttReleasePending = false;
        // IW2DPO 19-11-2026-1: after a TX TIMEOUT the PTT does not transmit.
        // PTT still held (auto-repeat): ignored. A new PTT press closes the popup
        // (like any other key) and is swallowed: the NEXT press transmits again.
        if (mTxTimeoutActive) {
            if (freshPress) {
                mPttSwallowRelease = true;
                clearTxTimeout();
            }
            return;
        }
        if (mPttSwallowRelease) {
            return; // same press that closed the popup, still held
        }
        if (freshPress) {
            playLocalTone();
        }
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (!mSettings.isPushToTalkToggle() && !isTalking()) {
                // Start talking. IW2DPO: while the local beep plays the mic gate is
                // closed, so silence is transmitted instead of the beep.
                setTalkingState(true);
                startTxTimeout(); // IW2DPO 19-11-2026-1
            }
        }
    }

    /**
     * Called when a user releases a talk key (i.e. when they do not want to talk).
     * Accounts for talk logic if toggle PTT is on.
     */
    @Override
    public void onTalkKeyUp() {
        mPttKeyDown = false;
        // IW2DPO 19-11-2026-1: release after a TX TIMEOUT, or release of the press that
        // closed the popup: nothing to do (TX is already off, no toggle).
        if (mTxTimeoutActive) {
            return;
        }
        if (mPttSwallowRelease) {
            mPttSwallowRelease = false;
            return;
        }
        // IW2DPO: PTT release debouncing (only on release, only in hold-to-talk mode)
        int debounceMs = mSettings.getPttDebounceMs();
        if (debounceMs > 0 && !mSettings.isPushToTalkToggle() && isTalking()) {
            mPttDebounceHandler.removeCallbacks(mPttReleaseRunnable);
            mPttDebounceHandler.postDelayed(mPttReleaseRunnable, debounceMs);
            mPttReleasePending = true;
            return;
        }
        handleTalkKeyUp();
    }

    /**
     * IW2DPO: plays the local tone on the radio loudspeaker when PTT is pressed.
     * READY TO TALK = connected to a server (communication possible);
     * NOT READY TO TALK = not connected / server unavailable.
     * In toggle PTT mode the "ready" tone is played only on the press that starts TX.
     */
    private void playLocalTone() {
        if (mLocalTonePlayer == null || mSettings == null || !mSettings.isLocalTonesEnabled()) {
            return;
        }
        mLocalTonePlayer.setHandsetMode(useVoiceCallToneRoute()); // IW2DPO 19-11-2026-3
        final int volume = mSettings.getLocalTonesVolume();
        // When the tone has finished, continue on the main thread (reopens the mic gate).
        final Runnable onDone = () -> mPttDebounceHandler.post(mLocalToneDoneRunnable);
        // isConnected(): state CONNECTED = connected AND synchronized with the server.
        if (isConnected()) {
            if (!mSettings.isPushToTalkToggle() || !isTalking()) {
                closeMicGateForTone();
                mLocalTonePlayer.playReady(volume, onDone);
            }
        } else {
            closeMicGateForTone();
            mLocalTonePlayer.playNotReady(volume, onDone);
        }
    }

    /**
     * IW2DPO 19-11-2026-3: route of the local tones (READY / NOT READY TO TALK, TX TIMEOUT).
     * When a Bluetooth headset / speaker-microphone (UNIWA BM001...) is in use, Humla
     * plays the RX audio on STREAM_VOICE_CALL over Bluetooth SCO. A tone played as MEDIA
     * is NOT sent over SCO, so it was not heard on the Bluetooth microphone. In that case
     * the tones are played on the voice-call route too (same as handset mode), so they
     * come out of the Bluetooth speaker exactly like the RX audio.
     */
    private boolean useVoiceCallToneRoute() {
        return mSettings.isHandsetMode() || isBluetoothScoActive();
    }

    /** IW2DPO 19-11-2026-3: true if Bluetooth SCO (headset audio) is currently active. */
    @SuppressWarnings("deprecation")
    private boolean isBluetoothScoActive() {
        try {
            if (usingBluetoothSco()) {
                return true;
            }
        } catch (Exception ignored) {
            // not connected / not synchronized yet
        }
        final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.media.AudioDeviceInfo dev = am.getCommunicationDevice();
            if (dev != null && dev.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return true;
            }
        }
        return am.isBluetoothScoOn();
    }

    /** IW2DPO: closes the mic gate BEFORE the beep starts (TX = silence while it plays). */
    private void closeMicGateForTone() {
        mLocalTonesPlaying++;
        MicGate.closeUntil(SystemClock.uptimeMillis() + MIC_GATE_MAX_MS);
    }

    /**
     * IW2DPO 22-11-2026-3: INCOMING TONE (Settings > Audio > Incoming audio beep).
     * Plays 2300 Hz 90 ms, 90 ms pause, 2300 Hz 90 ms (22-11-2026-4) on the loudspeaker when the
     * channel simply becomes active, i.e. the first other user starts talking while
     * nobody else was talking. Not played for our own TX.
     */
    private void handleIncomingTone(IUser user, int selfSession) {
        if (user == null || user.getSession() == selfSession) {
            return;
        }
        final TalkState state = user.getTalkState();
        final boolean talking = state == TalkState.TALKING
                || state == TalkState.SHOUTING
                || state == TalkState.WHISPERING;
        final boolean channelBecameActive;
        synchronized (mIncomingTalkers) {
            if (!talking) {
                mIncomingTalkers.remove(user.getSession());
                return;
            }
            final boolean wasIdle = mIncomingTalkers.isEmpty();
            final boolean added = mIncomingTalkers.add(user.getSession());
            channelBecameActive = wasIdle && added;
        }
        if (!channelBecameActive || mLocalTonePlayer == null || mSettings == null
                || !mSettings.isIncomingToneEnabled()) {
            return;
        }
        try {
            if (isTalking()) {
                return; // we are transmitting: no incoming beep
            }
        } catch (Exception ignored) {
            // not connected / not synchronized yet
        }
        mLocalTonePlayer.setHandsetMode(useVoiceCallToneRoute());
        mLocalTonePlayer.playIncoming(mSettings.getLocalTonesVolume(), null);
    }

    /** IW2DPO: original PTT release logic, run immediately or after the debounce delay. */
    private void handleTalkKeyUp() {
        if(isConnectionEstablished()
                && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
            if (mSettings.isPushToTalkToggle()) {
                setTalkingState(!isTalking()); // Toggle talk state
                // IW2DPO 19-11-2026-1: TX TIMEOUT also in toggle mode
                if (isTalking()) startTxTimeout(); else stopTxTimeout();
            } else if (isTalking()) {
                setTalkingState(false); // Stop talking
            }
        }
        stopTxTimeout(); // IW2DPO 19-11-2026-1 (no-op if a toggle TX was just started)
    }

    /** IW2DPO 19-11-2026-1: (re)starts the TX TIMEOUT timer if enabled (not OFF). */
    private void startTxTimeout() {
        mPttDebounceHandler.removeCallbacks(mTxTimeoutRunnable);
        final int sec = mSettings.getTxTimeoutSec();
        if (sec > 0) {
            mPttDebounceHandler.postDelayed(mTxTimeoutRunnable, sec * 1000L);
        }
    }

    /** IW2DPO 19-11-2026-1: TX ended normally, the TX TIMEOUT timer is cancelled. */
    private void stopTxTimeout() {
        if (isTalking()) return; // toggle mode: TX still on, keep the timer
        mPttDebounceHandler.removeCallbacks(mTxTimeoutRunnable);
    }

    /**
     * IW2DPO 19-11-2026-1: PTT transmitting for longer than TX TIMEOUT:
     * TX is cut off, 4 x 300 Hz 500 ms beeps (500 ms pause) on the local loudspeaker,
     * popup "TX TIMEOUT" shown until any key is pressed.
     */
    private void onTxTimeout() {
        if (!isTalking()) return;
        Log.i(TAG, "TX TIMEOUT: transmission cut off");
        mPttDebounceHandler.removeCallbacks(mPttReleaseRunnable);
        mPttReleasePending = false;
        mTxTimeoutActive = true;
        setTalkingState(false);
        if (mLocalTonePlayer != null) {
            mLocalTonePlayer.setHandsetMode(useVoiceCallToneRoute()); // IW2DPO 19-11-2026-3
            // played even if "Local tones" is off: it is an alarm
            mLocalTonePlayer.playTxTimeout(mSettings.getLocalTonesVolume(), null);
        }
        wakeScreen();
        notifyTxTimeoutListener();
    }

    private void notifyTxTimeoutListener() {
        if (mTxTimeoutListener != null) {
            mTxTimeoutListener.onTxTimeoutChanged(mTxTimeoutActive);
        }
    }

    @Override
    public boolean isTxTimeoutActive() {
        return mTxTimeoutActive;
    }

    @Override
    public void clearTxTimeout() {
        if (!mTxTimeoutActive) return;
        mTxTimeoutActive = false;
        // PTT still held (popup closed by another key): TX restarts only after a release
        if (mPttKeyDown) mPttSwallowRelease = true;
        notifyTxTimeoutListener();
    }

    @Override
    public void setTxTimeoutListener(TxTimeoutListener listener) {
        mTxTimeoutListener = listener;
    }

    @Override
    public List<IChatMessage> getMessageLog() {
        return Collections.unmodifiableList(mMessageLog);
    }

    @Override
    public void clearMessageLog() {
        if (mMessageLog != null) {
            mMessageLog.clear();
        }
    }

    /**
     * Sets whether or not notifications should be suppressed.
     *
     * It's typically a good idea to do this when the main activity is foreground, so that the user
     * is not bombarded with redundant alerts.
     *
     * <b>Chat notifications are NOT suppressed.</b> They may be if a chat indicator is added in the
     * activity itself. For now, the user may disable chat notifications manually.
     *
     * @param suppressNotifications true if Mumla is to disable notifications.
     */
    @Override
    public void setSuppressNotifications(boolean suppressNotifications) {
        mSuppressNotifications = suppressNotifications;
    }

    public static class MumlaBinder extends Binder {
        private final MumlaService mService;

        private MumlaBinder(MumlaService service) {
            mService = service;
        }

        public IMumlaService getService() {
            return mService;
        }
    }

    @Override
    public Message sendUserTextMessage(int session, String message) {
        Message msg = super.sendUserTextMessage(session, message);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }

    @Override
    public Message sendChannelTextMessage(int channel, String message, boolean tree) {
        Message msg = super.sendChannelTextMessage(channel, message, tree);

        mMessageLog.add(new IChatMessage.TextMessage(msg));
        return msg;
    }
}
