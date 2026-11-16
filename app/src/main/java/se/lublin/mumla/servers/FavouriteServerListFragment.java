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

package se.lublin.mumla.servers;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.GridView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;
import se.lublin.mumla.db.DatabaseProvider;
import se.lublin.mumla.db.PublicServer;

/**
 * Displays a list of servers, and allows the user to connect and edit them.
 * @author morlunk
 *
 */
public class FavouriteServerListFragment extends Fragment implements OnItemClickListener, FavouriteServerAdapter.FavouriteServerAdapterMenuListener {

    private ServerConnectHandler mConnectHandler;
    private DatabaseProvider mDatabaseProvider;
    private GridView mServerGrid;
    private ServerAdapter<Server> mServerAdapter;

    /** IW2DPO: default name of the servers backup file (export / import). */
    private static final String SERVERS_BACKUP_FILE = "mumla-servers.json";
    private static final String TAG = "FavouriteServerList";

    /** IW2DPO: "Export servers": the user chooses where to save the backup file. */
    private final ActivityResultLauncher<String> mExportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"),
            uri -> { if (uri != null) exportServers(uri); });

    /** IW2DPO: "Import servers": the user chooses the backup file to load. */
    private final ActivityResultLauncher<String[]> mImportLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) importServers(uri); });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mConnectHandler = (ServerConnectHandler)activity;
            mDatabaseProvider = (DatabaseProvider) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()+" must implement ServerConnectHandler!");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_server_list, container, false);
        mServerGrid = (GridView) view.findViewById(R.id.server_list_grid);
        mServerGrid.setOnItemClickListener(this);
        mServerGrid.setEmptyView(view.findViewById(R.id.server_list_grid_empty));

        registerForContextMenu(mServerGrid);
        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.fragment_server_list, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateServers();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_add_server_item) {
            addServer();
            return true;
        } else if (itemId == R.id.menu_quick_connect) {
            ServerEditFragment.createServerEditDialog(getActivity(), null, ServerEditFragment.Action.CONNECT_ACTION, true)
                    .show(getFragmentManager(), "serverInfo");
            return true;
        } else if (itemId == R.id.menu_export_servers) { // IW2DPO
            mExportLauncher.launch(SERVERS_BACKUP_FILE);
            return true;
        } else if (itemId == R.id.menu_import_servers) { // IW2DPO
            mImportLauncher.launch(new String[]{"application/json", "text/plain",
                    "application/octet-stream"});
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void addServer() {
        ServerEditFragment.createServerEditDialog(getActivity(), null, ServerEditFragment.Action.ADD_ACTION, false)
                .show(getFragmentManager(), "serverInfo");
    }

    public void editServer(Server server) {
        ServerEditFragment.createServerEditDialog(getActivity(), server, ServerEditFragment.Action.EDIT_ACTION, false)
                .show(getFragmentManager(), "serverInfo");
    }

    public void shareServer(Server server) {
        // Build Mumble server URL
        String serverUrl = "mumble://" + server.getHost()
            + (server.getPort() == 0 ? "" : ":" + server.getPort()) + "/";

        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_TEXT, getString(R.string.shareMessage, serverUrl));
        intent.setType("text/plain");
        startActivity(intent);
    }

    public void deleteServer(final Server server) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.confirm_delete_server)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    mDatabaseProvider.getDatabase().removeServer(server);
                    mServerAdapter.remove(server);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void updateServers() {
        List<Server> servers = getServers();
        mServerAdapter = new FavouriteServerAdapter(getActivity(), servers, this);
        mServerGrid.setAdapter(mServerAdapter);
    }



    public List<Server> getServers() {
        List<Server> servers = mDatabaseProvider.getDatabase().getServers();
        return servers;
    }

    /**
     * IW2DPO: writes all favourite servers (label, address, port, username, password)
     * to a JSON file chosen by the user, e.g. in Download. The file survives an app
     * uninstall / reinstall and can be loaded again with "Import servers".
     */
    private void exportServers(Uri uri) {
        try {
            JSONArray array = new JSONArray();
            for (Server s : getServers()) {
                JSONObject o = new JSONObject();
                o.put("label", s.getName());
                o.put("address", s.getHost());
                o.put("port", s.getPort());
                o.put("username", s.getUsername());
                o.put("password", s.getPassword() == null ? "" : s.getPassword());
                array.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("app", "mumla-iw2dpo");
            root.put("version", 1);
            root.put("servers", array);
            try (OutputStream out = requireContext().getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("Cannot open " + uri);
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(getActivity(), getString(R.string.servers_exported_iw2dpo, array.length()),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Export servers failed", e);
            Toast.makeText(getActivity(), getString(R.string.servers_export_failed_iw2dpo, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * IW2DPO: loads the servers from a file written by "Export servers". Servers already
     * present (same address, port and username) are not added twice.
     */
    private void importServers(Uri uri) {
        try {
            String text;
            try (InputStream in = requireContext().getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Cannot open " + uri);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONArray array = new JSONObject(text).getJSONArray("servers");
            List<Server> existing = getServers();
            int added = 0, skipped = 0;
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String host = o.optString("address", "").trim();
                if (host.isEmpty()) continue;
                int port = o.optInt("port", 0);
                String username = o.optString("username", "");
                boolean duplicate = false;
                for (Server e : existing) {
                    if (host.equalsIgnoreCase(e.getHost()) && port == e.getPort()
                            && username.equals(e.getUsername())) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    skipped++;
                    continue;
                }
                Server server = new Server(-1, o.optString("label", host), host, port,
                        username, o.optString("password", ""));
                mDatabaseProvider.getDatabase().addServer(server);
                existing.add(server);
                added++;
            }
            updateServers();
            Toast.makeText(getActivity(), getString(R.string.servers_imported_iw2dpo, added, skipped),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Import servers failed", e);
            Toast.makeText(getActivity(), getString(R.string.servers_import_failed_iw2dpo, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
        mConnectHandler.connectToServer(mServerAdapter.getItem(arg2));
    }

    public static interface ServerConnectHandler {
        public void connectToServer(Server server);
        public void connectToPublicServer(PublicServer server);
    }
}
