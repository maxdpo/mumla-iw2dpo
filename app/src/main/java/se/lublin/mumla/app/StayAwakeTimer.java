/*
 * IW2DPO - Hytera P50pro
 * Stay awake:
 *  - opzione "Stay awake" SELEZIONATA     -> schermo sempre acceso (nessun timeout)
 *    finche' Mumla e' in primo piano.
 *  - opzione "Stay awake" NON selezionata -> schermo acceso per STAY_AWAKE_TIMEOUT_MS
 *    (1 minuto) dall'ultima attivita' (tocco, tasto, PTT, voce in arrivo), poi lascia
 *    che il sistema spenga lo schermo con il suo timeout normale.
 */
package se.lublin.mumla.app;

import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;

public class StayAwakeTimer {
    /** Tempo di schermo acceso dopo l'ultima attivita' (Stay awake non selezionato): 1 minuto. */
    public static final long STAY_AWAKE_TIMEOUT_MS = 60 * 1000L;

    private final Window mWindow;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** true = Stay awake selezionato: schermo sempre acceso, nessun timeout. */
    private boolean mAlwaysOn;
    /** true tra start (onResume) e stop (onPause). */
    private boolean mActive;

    private final Runnable mReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (mAlwaysOn) return;
            mWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    private final Runnable mPokeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mActive) return;
            mWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mHandler.removeCallbacks(mReleaseRunnable);
            if (!mAlwaysOn) {
                mHandler.postDelayed(mReleaseRunnable, STAY_AWAKE_TIMEOUT_MS);
            }
        }
    };

    public StayAwakeTimer(Window window) {
        mWindow = window;
    }

    /**
     * Avvia lo stay awake (onResume / opzione cambiata).
     * @param alwaysOn true se l'opzione Impostazioni > Generali > Stay awake e' selezionata.
     */
    public void setEnabled(boolean alwaysOn) {
        mAlwaysOn = alwaysOn;
        mActive = true;
        poke();
    }

    /** Fa ripartire il conteggio di 1 minuto. Sicuro da qualsiasi thread. */
    public void poke() {
        if (!mActive) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mPokeRunnable.run();
        } else {
            mHandler.post(mPokeRunnable);
        }
    }

    /** Ferma il timer e toglie il flag (onPause). */
    public void stop() {
        mActive = false;
        mHandler.removeCallbacks(mPokeRunnable);
        mHandler.removeCallbacks(mReleaseRunnable);
        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
