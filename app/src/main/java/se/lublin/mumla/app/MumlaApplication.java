package se.lublin.mumla.app;

import static androidx.appcompat.app.AppCompatDelegate.setApplicationLocales;
import static androidx.core.os.LocaleListCompat.forLanguageTags;
import static androidx.core.os.LocaleListCompat.getEmptyLocaleList;
import static se.lublin.mumla.Settings.PREF_LANGUAGE;
import static se.lublin.mumla.Settings.PREF_THEME;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

public class MumlaApplication extends Application implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override
    public void onCreate() {
        super.onCreate();
        // IW2DPO: registrazione crash su file (Download/MumlaLog)
        installCrashLogger();
        saveLastExitReasons();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        applyTheme(preferences);
        preferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, @Nullable String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case PREF_LANGUAGE:
                String language = preferences.getString(PREF_LANGUAGE, "system");
                setApplicationLocales(language.equals("system") ? getEmptyLocaleList() : forLanguageTags(language));
                break;
            case PREF_THEME:
                applyTheme(preferences);
                break;
        }
    }

    private static void applyTheme(SharedPreferences preferences) {
        // The "system" and "force*" values are new (see preference_notranslate.xml).
        // We let other (older) value result in system default theme, and write that
        // to the preference store.
        switch (preferences.getString(PREF_THEME, "system")) {
            case "forceLight":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "forceDark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "system":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                preferences.edit().putString(PREF_THEME, "system").apply();
                break;
        }
    }

    // ======================================================================
    // IW2DPO crash logger
    // Salva i crash in:  Download/MumlaLog/            (Android 10+)
    //            e in:   Android/data/<package>/files/MumlaLog/
    // ======================================================================

    private static final String LOG_DIR = "MumlaLog";
    private static final String PREF_LAST_EXIT_TS = "iw2dpo_last_exit_ts";

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("=== CRASH JAVA ===");
                pw.println(deviceInfo());
                pw.println("Thread: " + thread.getName());
                pw.println();
                ex.printStackTrace(pw);
                pw.println();
                pw.println("=== LOGCAT (ultime righe di questo processo) ===");
                pw.print(readOwnLogcat());
                pw.flush();
                writeLog("crash", sw.toString());
            } catch (Throwable ignored) {
                // il logger non deve mai bloccare la chiusura
            }
            if (previous != null) {
                previous.uncaughtException(thread, ex);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /**
     * Android 11+ (API 30): all'avvio legge i motivi delle chiusure precedenti.
     * Cattura anche crash nativi (librerie audio opus/celt/speex) e ANR,
     * che il gestore Java sopra non vede.
     */
    private void saveLastExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            List<ApplicationExitInfo> infos = am.getHistoricalProcessExitReasons(getPackageName(), 0, 5);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            long lastSaved = prefs.getLong(PREF_LAST_EXIT_TS, 0);
            long newest = lastSaved;
            StringBuilder sb = new StringBuilder();
            for (ApplicationExitInfo info : infos) {
                if (info.getTimestamp() <= lastSaved) continue;
                int r = info.getReason();
                if (r != ApplicationExitInfo.REASON_CRASH
                        && r != ApplicationExitInfo.REASON_CRASH_NATIVE
                        && r != ApplicationExitInfo.REASON_ANR
                        && r != ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                        && r != ApplicationExitInfo.REASON_PERMISSION_CHANGE
                        && r != ApplicationExitInfo.REASON_SIGNALED
                        && r != ApplicationExitInfo.REASON_LOW_MEMORY) {
                    continue;
                }
                newest = Math.max(newest, info.getTimestamp());
                sb.append("----------------------------------------\n");
                sb.append("Quando:      ").append(new Date(info.getTimestamp())).append('\n');
                sb.append("Motivo:      ").append(reasonName(r)).append(" (").append(r).append(")\n");
                sb.append("Processo:    ").append(info.getProcessName()).append('\n');
                sb.append("Status:      ").append(info.getStatus()).append('\n');
                sb.append("Descrizione: ").append(info.getDescription()).append('\n');
                if (r == ApplicationExitInfo.REASON_ANR) {
                    try (InputStream is = info.getTraceInputStream()) {
                        if (is != null) {
                            sb.append("\nTraccia ANR:\n");
                            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                            String line;
                            int n = 0;
                            while ((line = br.readLine()) != null && n++ < 400) {
                                sb.append(line).append('\n');
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            }
            if (sb.length() > 0) {
                writeLog("exit", "=== CHIUSURE PRECEDENTI DELL'APP ===\n" + deviceInfo() + "\n" + sb);
                prefs.edit().putLong(PREF_LAST_EXIT_TS, newest).apply();
            }
        } catch (Throwable ignored) { }
    }

    private static String reasonName(int r) {
        switch (r) {
            case 4:  return "CRASH (eccezione Java)";
            case 5:  return "CRASH_NATIVE (libreria nativa)";
            case 6:  return "ANR (app bloccata)";
            case 7:  return "INITIALIZATION_FAILURE";
            case 8:  return "PERMISSION_CHANGE";
            case 3:  return "LOW_MEMORY";
            case 2:  return "SIGNALED";
            default: return "altro";
        }
    }

    private String deviceInfo() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) { }
        return "App:     " + getPackageName() + " " + version + "\n"
                + "Device:  " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Build:   " + Build.DISPLAY + "\n"
                + "Data:    " + new Date() + "\n";
    }

    private static String readOwnLogcat() {
        StringBuilder sb = new StringBuilder();
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "threadtime", "-t", "800",
                    "--pid=" + android.os.Process.myPid()});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            sb.append("logcat non disponibile: ").append(t).append('\n');
        }
        return sb.toString();
    }

    private void writeLog(String prefix, String text) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = "mumla_" + prefix + "_" + ts + ".txt";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);

        // 1) Cartella pubblica Download/MumlaLog (visibile da qualsiasi file manager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + LOG_DIR);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) os.write(data);
                    }
                }
            } catch (Throwable ignored) { }
        }

        // 2) Copia nella cartella dell'app (Android/data/<package>/files/MumlaLog)
        try {
            File base = getExternalFilesDir(null);
            if (base != null) {
                File dir = new File(base, LOG_DIR);
                if (dir.exists() || dir.mkdirs()) {
                    try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
                        fos.write(data);
                    }
                }
            }
        } catch (Throwable ignored) { }
    }
}
