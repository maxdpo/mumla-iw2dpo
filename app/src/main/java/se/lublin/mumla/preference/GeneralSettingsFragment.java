package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import java.util.List;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    private static final String USE_TOR_KEY = "useTor";
    private static final String AUTOCONNECT_SERVER_KEY = "autoconnect_server_id";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));

        // IW2DPO: populate the "preferred server" dropdown with the servers already saved
        // on this device, since they aren't known when the XML is built.
        ListPreference autoconnectServerPreference = getPreferenceScreen().findPreference(AUTOCONNECT_SERVER_KEY);
        if (autoconnectServerPreference != null) {
            populateAutoconnectServerList(autoconnectServerPreference);
        }
    }

    private void populateAutoconnectServerList(ListPreference preference) {
        MumlaDatabase database = new MumlaSQLiteDatabase(requireContext());
        database.open();
        List<Server> servers;
        try {
            servers = database.getServers();
        } finally {
            database.close();
        }

        CharSequence[] entries = new CharSequence[servers.size() + 1];
        CharSequence[] entryValues = new CharSequence[servers.size() + 1];
        entries[0] = getString(R.string.autoconnectServerNone);
        entryValues[0] = "-1";
        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            entries[i + 1] = server.getName() + " (" + server.getHost() + ")";
            entryValues[i + 1] = String.valueOf(server.getId());
        }
        preference.setEntries(entries);
        preference.setEntryValues(entryValues);

        if (preference.getValue() == null) {
            preference.setValue("-1");
        }
        preference.setSummary(preference.getEntry() != null
                ? preference.getEntry()
                : getString(R.string.autoconnectServerSum));
        preference.setOnPreferenceChangeListener((pref, newValue) -> {
            int index = preference.findIndexOfValue((String) newValue);
            preference.setSummary(index >= 0 ? entries[index] : getString(R.string.autoconnectServerSum));
            return true;
        });
    }
}
