package com.github.derakula;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements VpnForegroundService.StatusListener {

    private static final int REQ_WIZARD = 201;
    private static final int REQ_NOTIF = 100;

    private ActivityResultLauncher<Intent>  vpnPermLauncher;
    private ActivityResultLauncher<String>  importFileLauncher;
    private ActivityResultLauncher<Intent> wizardLauncher;

    private VpnForegroundService vpnService;
    private boolean              bound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            vpnService = ((VpnForegroundService.LocalBinder) b).getService();
            vpnService.setListener(MainActivity.this);
            bound = true;
            syncUiWithServiceState();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            bound = false;
        }
    };

    private ProfileRepository repo;
    private List<VpnProfile>  profiles;
    private VpnProfile        activeProfile;

    private TextView     tvProfileName, tvStatusLabel, tvListenAddr, tvLog, tvUptime;
    private View         btnConnect;
    private ImageButton  btnEditProfile, btnProfileMenu;
    private ScrollView   svLog;
    private LinearLayout llProfileChips;
    private Switch       swTunnel;

    private final StringBuilder log = new StringBuilder();

    private long connectTime = 0;
    private final android.os.Handler uptimeHandler = new android.os.Handler();
    private final Runnable uptimeTick = new Runnable() {
        @Override public void run() {
            if (isRunning()) {
                long sec = (System.currentTimeMillis() - connectTime) / 1000;
                tvUptime.setText(String.format("%02d:%02d:%02d", sec/3600, (sec%3600)/60, sec%60));
                uptimeHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        // ── VPN permission launcher ───────────────────────────
        vpnPermLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        startTunnelService();
                    } else {
                        swTunnel.setChecked(false);
                        Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        // ── Import file launcher ──────────────────────────────
        importFileLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    try {
                        InputStream is  = getContentResolver().openInputStream(uri);
                        byte[]      buf = is.readAllBytes();
                        is.close();
                        importProfileFromString(new String(buf, "UTF-8").trim());
                    } catch (Exception e) {
                        Toast.makeText(this, "Failed to read file: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }
        );

        wizardLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String pid = result.getData().getStringExtra(ProfileWizardActivity.EXTRA_PROFILE_ID);
                        // reload profiles from repo
                        profiles = repo.loadAll();
                        if (pid != null) {
                            for (VpnProfile p : profiles)
                                if (p.id.equals(pid)) { activeProfile = p; break; }
                        }
                        repo.setActiveId(activeProfile.id);
                        renderProfileChips();
                        updateProfileHeader();
                        appendLog("Profile saved: " + activeProfile.name);
                    }
                }
        );


        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, ins) -> {
            Insets sb = ins.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return ins;
        });

        requestNotificationPermission();

        repo     = new ProfileRepository(this);
        profiles = repo.loadAll();
        if (profiles.isEmpty()) {
            VpnProfile def = new VpnProfile();
            def.name = "Profile 1";
            profiles.add(def);
            repo.saveAll(profiles);
        }

        String activeId = repo.getActiveId();
        activeProfile = profiles.get(0);
        if (activeId != null)
            for (VpnProfile p : profiles)
                if (p.id.equals(activeId)) { activeProfile = p; break; }
        repo.setActiveId(activeProfile.id);

        bindViews();

        boolean tunnelEnabled = getSharedPreferences("xvpn_prefs", MODE_PRIVATE)
                .getBoolean("tunnel_enabled", false);
        swTunnel.setChecked(tunnelEnabled);

        setupButtons();
        renderProfileChips();
        updateProfileHeader();

        bindService(new Intent(this, VpnForegroundService.class), connection, Context.BIND_AUTO_CREATE);
    }

    private void bindViews() {
        tvProfileName  = findViewById(R.id.tvProfileName);
        tvStatusLabel  = findViewById(R.id.tvStatusLabel);
        tvListenAddr   = findViewById(R.id.tvListenAddr);
        tvLog          = findViewById(R.id.tvLog);
        tvUptime       = findViewById(R.id.tvUptime);
        btnConnect     = findViewById(R.id.btnConnect);
        btnEditProfile = findViewById(R.id.btnEditProfile);
        btnProfileMenu = findViewById(R.id.btnProfileMenu);
        svLog          = findViewById(R.id.svLog);
        llProfileChips = findViewById(R.id.llProfileChips);
        swTunnel       = findViewById(R.id.swTunnel);
    }

    private void setupButtons() {
        btnConnect.setOnClickListener(v -> {
            if (isRunning()) stopVpn();
            else             startVpn();
        });

        btnEditProfile.setOnClickListener(v -> showEditProfileDialog(activeProfile));
        btnProfileMenu.setOnClickListener(v -> showProfileMenu());

        swTunnel.setOnCheckedChangeListener((btn, checked) -> {
            getSharedPreferences("xvpn_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("tunnel_enabled", checked)
                    .apply();

            appendLog("Tunnel mode: " + (checked ? "ON" : "OFF"));

            if (!checked && isRunning()) {
                stopTunnelService();
            }
        });
    }

    private void renderProfileChips() {
        llProfileChips.removeAllViews();
        for (VpnProfile p : profiles) {
            TextView chip = new TextView(this);
            chip.setText(p.name);
            chip.setPadding(dp(16), dp(8), dp(16), dp(8));
            chip.setTextSize(13f);
            chip.setTextColor(Color.WHITE);
            chip.setBackgroundResource(p.id.equals(activeProfile.id)
                    ? R.drawable.chip_active : R.drawable.chip_inactive);
            chip.setOnClickListener(v -> switchProfile(p));
            chip.setOnLongClickListener(v -> { showProfileLongPressMenu(p); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            llProfileChips.addView(chip);
        }

        TextView addChip = new TextView(this);
        addChip.setText("+ New");
        addChip.setPadding(dp(16), dp(8), dp(16), dp(8));
        addChip.setTextSize(13f);
        addChip.setTextColor(Color.parseColor("#80FFFFFF"));
        addChip.setBackgroundResource(R.drawable.chip_add);
        addChip.setOnClickListener(v -> createNewProfile());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.setMarginEnd(dp(8));
        addChip.setLayoutParams(lp2);
        llProfileChips.addView(addChip);
    }

    private void switchProfile(VpnProfile p) {
        if (isRunning()) {
            Toast.makeText(this, "Stop the service first", Toast.LENGTH_SHORT).show();
            return;
        }
        activeProfile = p;
        repo.setActiveId(p.id);
        renderProfileChips();
        updateProfileHeader();
        appendLog("-> Profile: " + p.name);
    }

    private void updateProfileHeader() {
        tvProfileName.setText(activeProfile.name);
        tvListenAddr.setText(activeProfile.listenAddr != null ? activeProfile.listenAddr : "---");
    }

    private void createNewProfile() {
        VpnProfile p = new VpnProfile();
        p.name = "Profile " + (profiles.size() + 1);
        profiles.add(p);
        repo.saveAll(profiles);
        // open wizard directly (no switchProfile needed — wizard saves on finish)
        Intent i = new Intent(this, ProfileWizardActivity.class);
        i.putExtra(ProfileWizardActivity.EXTRA_PROFILE_ID, p.id);
        wizardLauncher.launch(i);
    }


    private void showEditProfileDialog(VpnProfile profile) {
        repo.save(profile); // make sure it's persisted before wizard opens
        Intent i = new Intent(this, ProfileWizardActivity.class);
        i.putExtra(ProfileWizardActivity.EXTRA_PROFILE_ID, profile.id);
        wizardLauncher.launch(i);
    }


    private void showProfileLongPressMenu(VpnProfile p) {
        String[] items = {"Edit", "Duplicate", "Export", "Delete"};
        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle(p.name)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: showEditProfileDialog(p); break;
                        case 1: duplicateProfile(p);      break;
                        case 2: exportAsLink(p);          break;
                        case 3: confirmDeleteProfile(p);  break;
                    }
                }).show();
    }

    private void showProfileMenu() {
        String[] items = {
                "Edit Current Profile", "Duplicate", "Delete",
                "New Profile", "Import / Export"
        };
        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Profiles")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: showEditProfileDialog(activeProfile); break;
                        case 1: duplicateProfile(activeProfile);      break;
                        case 2: confirmDeleteProfile(activeProfile);  break;
                        case 3: createNewProfile();                   break;
                        case 4: showImportExportMenu();               break;
                    }
                }).show();
    }

    private void duplicateProfile(VpnProfile src) {
        VpnProfile copy = new VpnProfile();
        copy.name          = src.name + " (copy)";
        copy.clientId      = src.clientId;
        copy.clientSecret  = src.clientSecret;
        copy.projectId     = src.projectId;
        copy.refreshToken  = src.refreshToken;
        copy.listenAddr    = src.listenAddr;
        copy.folderId      = src.folderId;
        copy.refreshRateMs = src.refreshRateMs;
        copy.flushRateMs   = src.flushRateMs;
        copy.targetIp      = src.targetIp;
        copy.sni           = src.sni;
        copy.hostHeader    = src.hostHeader;
        profiles.add(copy);
        repo.saveAll(profiles);
        renderProfileChips();
        appendLog("Duplicated: " + copy.name);
    }

    private void confirmDeleteProfile(VpnProfile p) {
        if (profiles.size() <= 1) {
            Toast.makeText(this, "At least one profile required", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Delete Profile")
                .setMessage("Delete \"" + p.name + "\"?")
                .setPositiveButton("Delete", (d, w) -> {
                    profiles.remove(p);
                    repo.saveAll(profiles);
                    if (activeProfile.id.equals(p.id)) {
                        activeProfile = profiles.get(0);
                        repo.setActiveId(activeProfile.id);
                    }
                    renderProfileChips();
                    updateProfileHeader();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Import / Export ─────────────────────────────────────────────────────

    private void showImportExportMenu() {
        String[] items = {
                "Export — Share Link (xvpn://)",
                "Export — Save as JSON File",
                "Import — Paste Link / Text",
                "Import — Pick JSON File"
        };
        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Import / Export Profile")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: exportAsLink(activeProfile);       break;
                        case 1: exportAsFile(activeProfile);       break;
                        case 2: showImportFromTextDialog();        break;
                        case 3: importFileLauncher.launch("*/*"); break;
                    }
                }).show();
    }

    /** Shares profile as xvpn:// link via Android share sheet */
    private void exportAsLink(VpnProfile p) {
        String link = p.toShareLink();
        if (link.isEmpty()) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, link);
        intent.putExtra(Intent.EXTRA_SUBJECT, "xVPN Profile: " + p.name);
        startActivity(Intent.createChooser(intent, "Share Profile"));
        appendLog("Exported link: " + p.name);
    }

    /** Exports profile as a .json file via Android share sheet */
    private void exportAsFile(VpnProfile p) {
        try {
            String json     = p.toJson().toString(2);
            String safeName = p.name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String filename = "xvpn_" + safeName + ".json";

            File file = new File(getCacheDir(), filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.getBytes("UTF-8"));
            }

            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Export Profile"));
            appendLog("Exported file: " + filename);

        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Dialog for pasting an xvpn:// link or raw JSON */
    private void showImportFromTextDialog() {
        EditText et = new EditText(this);
        et.setHint("Paste xvpn://... or raw JSON here");
        et.setMinLines(4);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        new AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Import Profile")
                .setView(et)
                .setPositiveButton("Import", (d, w) ->
                        importProfileFromString(et.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Core import — accepts both xvpn:// links and raw JSON */
    private void importProfileFromString(String content) {
        if (content == null || content.trim().isEmpty()) {
            Toast.makeText(this, "Content is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            VpnProfile p;
            if (content.startsWith("xvpn://")) {
                p = VpnProfile.fromShareLink(content);
            } else {
                JSONObject o = new JSONObject(content);
                p    = VpnProfile.fromJson(o);
                p.id = java.util.UUID.randomUUID().toString();
            }
            if (p.name == null || p.name.trim().isEmpty()) p.name = "Imported Profile";

            profiles.add(p);
            repo.saveAll(profiles);
            renderProfileChips();
            appendLog("Imported: " + p.name);
            Toast.makeText(this, "Profile imported: " + p.name,
                    Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ─── VPN Control ─────────────────────────────────────────────────────────

    private void startVpn() {
        if (activeProfile.clientId == null || activeProfile.clientId.isEmpty()
                || activeProfile.clientSecret == null || activeProfile.clientSecret.isEmpty()
                || activeProfile.refreshToken == null || activeProfile.refreshToken.isEmpty()
                || activeProfile.folderId == null || activeProfile.folderId.isEmpty()) {
            Toast.makeText(this, "Please complete the profile first", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, VpnForegroundService.class);
        intent.setAction(VpnForegroundService.ACTION_START);
        intent.putExtra("credJSON",    activeProfile.buildCredentialsJSON());
        intent.putExtra("tokenJSON",   activeProfile.buildTokenJSON());
        intent.putExtra("cfgJSON",     activeProfile.buildConfigJSON());
        intent.putExtra("listenAddr",  activeProfile.listenAddr);
        intent.putExtra("profileName", activeProfile.name);
        startForegroundService(intent);
        bindService(new Intent(this, VpnForegroundService.class), connection, BIND_AUTO_CREATE);

        setStatusConnecting();
        appendLog("-- Connecting [" + activeProfile.name + "] --");
    }

    private void stopVpn() {
        stopTunnelService();
        Intent intent = new Intent(this, VpnForegroundService.class);
        intent.setAction(VpnForegroundService.ACTION_STOP);
        startService(intent);
    }

    // ─── Callbacks from VpnForegroundService ─────────────────────────────────

    @Override
    public void onStarted(String listenAddr) {
        runOnUiThread(() -> {
            connectTime = System.currentTimeMillis();
            setStatusConnected();
            uptimeHandler.post(uptimeTick);
            appendLog("Connected -> " + listenAddr);
            appendLog("SOCKS5 " + listenAddr);

            if (swTunnel.isChecked()) {
                uptimeHandler.postDelayed(this::startTunnelService, 1500);
            }
        });
    }

    @Override
    public void onStopped() {
        runOnUiThread(() -> {
            uptimeHandler.removeCallbacksAndMessages(null);
            tvUptime.setText("00:00:00");
            setStatusDisconnected();
            appendLog("-- Disconnected --");
        });
    }

    @Override
    public void onError(String msg) {
        runOnUiThread(() -> {
            uptimeHandler.removeCallbacksAndMessages(null);
            setStatusDisconnected();
            appendLog("Error: " + msg);
        });
    }

    private void syncUiWithServiceState() {
        if (vpnService != null && vpnService.isRunning()) {
            setStatusConnected();
            connectTime = System.currentTimeMillis();
            uptimeHandler.post(uptimeTick);
        } else {
            setStatusDisconnected();
        }
    }

    private boolean isRunning() { return vpnService != null && vpnService.isRunning(); }

    private void setStatusConnected() {
        btnConnect.setBackgroundResource(R.drawable.btn_connect_on);
        tvStatusLabel.setText("CONNECTED");
        tvStatusLabel.setTextColor(Color.parseColor("#43A047"));
    }

    private void setStatusConnecting() {
        btnConnect.setBackgroundResource(R.drawable.btn_connect_mid);
        tvStatusLabel.setText("CONNECTING...");
        tvStatusLabel.setTextColor(Color.parseColor("#FB8C00"));
    }

    private void setStatusDisconnected() {
        btnConnect.setBackgroundResource(R.drawable.btn_connect_off);
        tvStatusLabel.setText("TAP TO CONNECT");
        tvStatusLabel.setTextColor(Color.parseColor("#546E7A"));
    }

    private void startTunnelService() {
        String addr = (activeProfile.listenAddr != null && !activeProfile.listenAddr.isEmpty())
                ? activeProfile.listenAddr : "127.0.0.1:1080";

        String host = "127.0.0.1";
        int    port = 1080;
        try {
            int colon = addr.lastIndexOf(':');
            if (colon > 0) {
                host = addr.substring(0, colon);
                port = Integer.parseInt(addr.substring(colon + 1));
            }
        } catch (Exception ignored) {}

        Intent i = new Intent(this, TunnelVpnService.class);
        i.setAction(TunnelVpnService.ACTION_START);
        i.putExtra("socks5Host", host);
        i.putExtra("socks5Port", port);
        startService(i);

        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            vpnPermLauncher.launch(vpnIntent);
            return;
        }

        appendLog("Tunnel -> " + host + ":" + port);
    }

    private void stopTunnelService() {
        Intent i = new Intent(this, TunnelVpnService.class);
        i.setAction(TunnelVpnService.ACTION_STOP);
        startService(i);
        appendLog("Tunnel stopped");
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
            }
        }
    }

    private void appendLog(String msg) {
        if (log.length() > 2000) {
            int cut = log.indexOf("\n", 400);
            if (cut > 0) log.delete(0, cut + 1);
        }
        log.append(msg).append("\n");
        tvLog.setText(log.toString());
        svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uptimeHandler.removeCallbacksAndMessages(null);
        if (bound) { unbindService(connection); bound = false; }
    }
}