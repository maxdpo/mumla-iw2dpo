/*
 * IW2DPO - Hytera P50pro
 * Stay awake con timeout: tiene lo schermo acceso per STAY_AWAKE_TIMEOUT_MS
 * dall'ultima attivita' (tocco, tasto, PTT, voce in arrivo), poi lascia
 * che il sistema spenga lo schermo con il suo timeout normale.
 */
package se.lublin.mumla.app;

import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

public class StayAwakeTimer {
    /** Tempo di schermo acceso dopo l'ultima attivita': 5 minuti. */
    public static final long STAY_AWAKE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final Window mWindow;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mEnabled;

    private final Runnable mReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            mWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    private final Runnable mPokeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mEnabled) return;
            mWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mHandler.removeCallbacks(mReleaseRunnable);
            mHandler.postDelayed(mReleaseRunnable, STAY_AWAKE_TIMEOUT_MS);
        }
    };

    public StayAwakeTimer(Window window) {
        mWindow = window;
    }

    /** Attiva/disattiva lo stay awake (opzione Impostazioni > Generali). */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (enabled) {
            poke();
        } else {
            stop();
        }
    }

    /** Fa ripartire il conteggio dei 5 minuti. Sicuro da qualsiasi thread. */
    public void poke() {
        if (!mEnabled) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mPokeRunnable.run();
        } else {
            mHandler.post(mPokeRunnable);
        }
    }

    /** Ferma il timer e toglie il flag (onPause / opzione disattivata). */
    public void stop() {
        mHandler.removeCallbacks(mPokeRunnable);
        mHandler.removeCallbacks(mReleaseRunnable);
        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
