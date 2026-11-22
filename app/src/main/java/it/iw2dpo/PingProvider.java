package it.iw2dpo;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

/**
 * IW2DPO - Risponde al "ping" dell'app MumlaMonitor.
 *
 * Un ContentProvider viene creato da Android automaticamente all'avvio del processo
 * di Mumla (prima di Application.onCreate), quindi qui registriamo a runtime un
 * receiver che esiste SOLO finche' il processo di Mumla e' vivo. Se Mumla viene
 * chiusa o crasha, il ping resta senza risposta e MumlaMonitor la riapre.
 *
 * 22-11-2026-2: la risposta contiene anche lo stato delle schermate di Mumla
 *   - "activities": schermate aperte (0 = tolta dalle recenti / chiusa col tasto Indietro,
 *                   ma processo ancora vivo per il servizio di connessione)
 *   - "visible":    schermate visibili (0 = un'altra app o la home e' in primo piano)
 * Cosi' MumlaMonitor sa se Mumla e' in primo piano senza permessi speciali.
 * Non serve modificare nessun altro file Java di Mumla.
 */
public class PingProvider extends ContentProvider {

    public static final String ACTION_PING = "it.iw2dpo.mumla.PING";
    public static final String EXTRA_ACTIVITIES = "activities";
    public static final String EXTRA_VISIBLE = "visible";

    private static int createdActivities = 0;
    private static int startedActivities = 0;

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx == null) return false;
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;

        if (app instanceof Application) {
            ((Application) app).registerActivityLifecycleCallbacks(new LifecycleCounter());
        }

        BroadcastReceiver pong = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (!isOrderedBroadcast()) return;
                setResultCode(Activity.RESULT_OK);
                Bundle extras = getResultExtras(true);
                extras.putInt(EXTRA_ACTIVITIES, createdActivities);
                extras.putInt(EXTRA_VISIBLE, startedActivities);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_PING);
        if (Build.VERSION.SDK_INT >= 33) {
            // deve essere EXPORTED: il ping arriva da un'altra app
            app.registerReceiver(pong, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(pong, filter);
        }
        return true;
    }

    /** Conta le schermate di Mumla aperte e visibili (tutto sul main thread). */
    private static class LifecycleCounter implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity a, Bundle b) { createdActivities++; }
        @Override public void onActivityStarted(Activity a) { startedActivities++; }
        @Override public void onActivityResumed(Activity a) { }
        @Override public void onActivityPaused(Activity a) { }
        @Override public void onActivityStopped(Activity a) {
            if (startedActivities > 0) startedActivities--;
        }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        @Override public void onActivityDestroyed(Activity a) {
            if (createdActivities > 0) createdActivities--;
        }
    }

    // --- Metodi obbligatori del ContentProvider, non usati ---
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
