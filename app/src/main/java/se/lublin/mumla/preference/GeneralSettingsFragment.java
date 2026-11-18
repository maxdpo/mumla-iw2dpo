package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    private static final String USE_TOR_KEY = "useTor";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));

        // IW2DPO: KEYLOCK CODE: numeric keyboard, max 4 digits, only exactly 4 digits accepted
        EditTextPreference keylockCode = findPreference(Settings.PREF_KEYLOCK_CODE);
        if (keylockCode != null) {
            keylockCode.setOnBindEditTextListener(editText -> {
                editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
                editText.selectAll();
            });
            keylockCode.setOnPreferenceChangeListener((preference, newValue) -> {
                if (Settings.isValidKeylockCode(String.valueOf(newValue))) {
                    return true;
                }
                Toast.makeText(requireContext(), R.string.keylock_code_invalid_iw2dpo,
                        Toast.LENGTH_LONG).show();
                return false;
            });
        }
    }
}
