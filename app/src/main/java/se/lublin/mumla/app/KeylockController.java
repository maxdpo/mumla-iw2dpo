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

package se.lublin.mumla.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

/**
 * IW2DPO: KEYLOCK screen for POC radios with a touch screen.
 *
 * <ul>
 *   <li>Locked at app start and after {@link #AUTO_LOCK_MS} without touches.</li>
 *   <li>Unlocked by typing {@link #UNLOCK_CODE} on the on-screen keypad
 *       ("*" or "#" clear what was typed).</li>
 *   <li>While locked the keypad covers the whole Mumla screen and eats every touch;
 *       hardware keys (PTT, volume) are NOT intercepted, so they keep working.
 *       Back and Menu are blocked.</li>
 *   <li>Active only on radios with a touch screen (Settings > General >
 *       "Screen lock (KEYLOCK)": Auto / On / Off).</li>
 * </ul>
 */
public class KeylockController {
    public static final String UNLOCK_CODE = "1234";
    public static final long AUTO_LOCK_MS = 60_000;

    private static final String[][] KEYS = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {"#", "0", "*"},
    };

    private final AppCompatActivity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final OnBackPressedCallback mBackBlocker;
    private final StringBuilder mTyped = new StringBuilder();
    private final Runnable mAutoLock = this::lock;
    private View mOverlay;
    private TextView mCodeView;
    private boolean mEnabled;
    private boolean mLocked;

    public KeylockController(AppCompatActivity activity) {
        mActivity = activity;
        // Registered after Mumla's own callback, so it has priority: Back does nothing
        // while locked.
        mBackBlocker = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // locked: ignore Back
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, mBackBlocker);
    }

    /** @return true if the device reports a real finger touch screen. */
    public static boolean hasTouchScreen(Context context) {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            return false;
        }
        return context.getResources().getConfiguration().touchscreen
                != Configuration.TOUCHSCREEN_NOTOUCH;
    }

    /** @return true if the KEYLOCK screen must be used on this radio. */
    public static boolean isEnabledFor(Context context, Settings settings) {
        String mode = settings.getKeylockMode();
        if (Settings.KEYLOCK_MODE_ON.equals(mode)) return true;
        if (Settings.KEYLOCK_MODE_OFF.equals(mode)) return false;
        return hasTouchScreen(context); // auto
    }

    public void setEnabled(boolean enabled) {
        if (enabled == mEnabled) return;
        mEnabled = enabled;
        if (enabled) {
            restartTimer();
        } else {
            mHandler.removeCallbacks(mAutoLock);
            hide();
        }
    }

    public boolean isLocked() {
        return mLocked;
    }

    /** Shows the KEYLOCK screen (no effect if the lock is disabled on this radio). */
    public void lock() {
        if (!mEnabled) return;
        mHandler.removeCallbacks(mAutoLock);
        if (mOverlay == null) build();
        mTyped.setLength(0);
        updateCode();
        mOverlay.setVisibility(View.VISIBLE);
        mOverlay.bringToFront();
        mLocked = true;
        mBackBlocker.setEnabled(true);
    }

    /** Call on every touch on the Mumla screen: restarts the 1 minute timer. */
    public void onUserActivity() {
        if (mEnabled && !mLocked) restartTimer();
    }

    public void release() {
        mHandler.removeCallbacks(mAutoLock);
    }

    private void unlock() {
        hide();
        restartTimer();
    }

    private void hide() {
        mLocked = false;
        mBackBlocker.setEnabled(false);
        if (mOverlay != null) mOverlay.setVisibility(View.GONE);
    }

    private void restartTimer() {
        mHandler.removeCallbacks(mAutoLock);
        if (mEnabled) mHandler.postDelayed(mAutoLock, AUTO_LOCK_MS);
    }

    private void onKey(String key) {
        if ("*".equals(key) || "#".equals(key)) {
            mTyped.setLength(0);
            updateCode();
            return;
        }
        mTyped.append(key);
        if (mTyped.length() >= UNLOCK_CODE.length()) {
            if (UNLOCK_CODE.contentEquals(mTyped)) {
                unlock();
                return;
            }
            mTyped.setLength(0);
            Toast.makeText(mActivity, R.string.keylock_wrong_code_iw2dpo, Toast.LENGTH_SHORT).show();
        }
        updateCode();
    }

    /** Shows "● ● _ _" for the digits typed so far. */
    private void updateCode() {
        if (mCodeView == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < UNLOCK_CODE.length(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(i < mTyped.length() ? '●' : '_');
        }
        mCodeView.setText(sb);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                mActivity.getResources().getDisplayMetrics()));
    }

    /** Builds the KEYLOCK screen: title box on top, 3 x 4 keypad below. */
    private void build() {
        Context ctx = mActivity;
        int background = MaterialColors.getColor(ctx, android.R.attr.colorBackground, Color.BLACK);
        int primary = MaterialColors.getColor(ctx,
                androidx.appcompat.R.attr.colorPrimary, Color.BLUE);
        int onPrimary = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setClickable(true); // eats every touch that misses a key
        root.setFocusable(false);
        root.setTranslationZ(dp(100)); // above the toolbar and the drawer

        // Title box: "KEYLOCK" + typed digits
        LinearLayout title = new LinearLayout(ctx);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setGravity(Gravity.CENTER);
        title.setBackgroundColor(primary);
        TextView label = new TextView(ctx);
        label.setText(R.string.keylock_title_iw2dpo);
        label.setTextColor(onPrimary);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        label.setGravity(Gravity.CENTER);
        mCodeView = new TextView(ctx);
        mCodeView.setTextColor(onPrimary);
        mCodeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mCodeView.setTypeface(Typeface.MONOSPACE);
        mCodeView.setGravity(Gravity.CENTER);
        title.addView(label);
        title.addView(mCodeView);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.3f);
        titleLp.setMargins(dp(4), dp(4), dp(4), dp(8));
        root.addView(title, titleLp);

        for (String[] rowKeys : KEYS) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : rowKeys) {
                MaterialButton b = new MaterialButton(ctx);
                b.setText(key);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                b.setInsetTop(0);
                b.setInsetBottom(0);
                b.setCornerRadius(dp(4));
                b.setFocusable(false); // hardware keys never press a keypad button
                b.setOnClickListener(v -> onKey(key));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(b, lp);
            }
            root.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        root.setVisibility(View.GONE);
        mActivity.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOverlay = root;
        updateCode();
    }
}
