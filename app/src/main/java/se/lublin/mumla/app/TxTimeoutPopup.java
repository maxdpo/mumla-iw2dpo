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

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import se.lublin.mumla.R;

/**
 * IW2DPO 19-11-2026-1: "TX TIMEOUT" popup.
 *
 * Shown on top of the whole Mumla screen (also above the KEYLOCK screen) when the PTT has
 * been transmitting for longer than Settings &gt; Audio &gt; TX TIMEOUT. It stays until any
 * key is pressed (MumlaActivity.onKeyDown) or the popup is touched.
 *
 * It is a view inside the activity (not a Dialog), so hardware key events keep arriving
 * at MumlaActivity: the PTT key keeps going to MumlaService, which decides what to do.
 */
public class TxTimeoutPopup {
    private final AppCompatActivity mActivity;
    private final Runnable mOnUserDismiss;
    private View mOverlay;

    /** @param onUserDismiss run when the user touches the popup. */
    public TxTimeoutPopup(AppCompatActivity activity, Runnable onUserDismiss) {
        mActivity = activity;
        mOnUserDismiss = onUserDismiss;
    }

    public boolean isShown() {
        return mOverlay != null && mOverlay.getVisibility() == View.VISIBLE;
    }

    public void setShown(boolean shown) {
        if (shown) {
            if (mOverlay == null) build();
            mOverlay.setVisibility(View.VISIBLE);
            mOverlay.bringToFront();
        } else if (mOverlay != null) {
            mOverlay.setVisibility(View.GONE);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                mActivity.getResources().getDisplayMetrics()));
    }

    private void build() {
        FrameLayout root = new FrameLayout(mActivity);
        root.setBackgroundColor(0xB0000000); // dark, semi-transparent
        root.setClickable(true); // eats every touch
        root.setFocusable(false);
        root.setTranslationZ(dp(200)); // above toolbar, drawer and KEYLOCK screen
        root.setOnClickListener(v -> {
            if (mOnUserDismiss != null) mOnUserDismiss.run();
        });

        TextView text = new TextView(mActivity);
        text.setText(R.string.tx_timeout_popup_iw2dpo);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(32), dp(24), dp(32));
        GradientDrawable box = new GradientDrawable();
        box.setColor(0xFFC62828); // red
        box.setCornerRadius(dp(8));
        box.setStroke(dp(3), Color.WHITE);
        text.setBackground(box);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        lp.setMargins(dp(16), 0, dp(16), 0);
        root.addView(text, lp);

        root.setVisibility(View.GONE);
        mActivity.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mOverlay = root;
    }
}
