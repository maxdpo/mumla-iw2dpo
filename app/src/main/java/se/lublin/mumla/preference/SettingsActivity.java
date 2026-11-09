package se.lublin.mumla.preference;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.app.StayAwakeTimer;

public class SettingsActivity extends AppCompatActivity {

    /** IW2DPO: stesso stay awake di 5 minuti anche nella schermata Impostazioni. */
    private StayAwakeTimer mStayAwakeTimer;

    @Override
    protected void onResume() {
        super.onResume();
        if (mStayAwakeTimer == null) {
            mStayAwakeTimer = new StayAwakeTimer(getWindow());
        }
        mStayAwakeTimer.setEnabled(Settings.getInstance(this).shouldStayAwake());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mStayAwakeTimer != null) mStayAwakeTimer.stop();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (mStayAwakeTimer != null) mStayAwakeTimer.poke();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.action_settings);
        }

        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!getSupportFragmentManager().popBackStackImmediate()) {
                    // Finish activity if nothing was popped from stack
                    finish();
                }
            }
        });

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, new RootPreferenceFragment())
                    .commit();
        }

        getSupportFragmentManager().setFragmentResultListener("launchFragment", this, (requestKey, result) -> {
            String fragmentClassName = result.getString("fragmentClassName");
            if (fragmentClassName != null) {
                try {
                    Class<?> fragmentClass = Class.forName(fragmentClassName);
                    Fragment fragment = (Fragment) fragmentClass.newInstance();
                    // Pass it on to the fragment so its onResume can set the title, see MumlaPreferenceFragment
                    fragment.setArguments(result);
                    getSupportFragmentManager()
                            .beginTransaction()
                            .replace(R.id.settings_container, fragment)
                            .addToBackStack(null)
                            .commit();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    public static class RootPreferenceFragment extends MumlaPreferenceFragment {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preference_headers, rootKey);
        }
    }
}
