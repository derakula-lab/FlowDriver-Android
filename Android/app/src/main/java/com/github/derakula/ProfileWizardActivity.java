package com.github.derakula;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

import androidlib.Androidlib;

/**
 * Step-by-step wizard for creating or editing a VPN profile.
 *
 * Steps:
 *   0 — Profile Name
 *   1 — Google OAuth Credentials  (client_id, client_secret, project_id)
 *   2 — Get Refresh Token         (Authorize button → browser → paste redirect URL → auto-exchange)
 *
 * Advanced settings (listenAddr, folderID, transport, rates) live in a collapsible
 * section at the bottom of Step 1 so power users can still reach them.
 *
 * Launching:
 *   Intent i = new Intent(this, ProfileWizardActivity.class);
 *   i.putExtra(ProfileWizardActivity.EXTRA_PROFILE_ID, profile.id); // omit for new profile
 *   startActivityForResult(i, REQ_WIZARD);
 *
 * Result:
 *   RESULT_OK  → profile was saved to ProfileRepository; EXTRA_PROFILE_ID contains its id
 *   RESULT_CANCELED → user bailed
 */
public class ProfileWizardActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    // ── colours (dark theme) ──────────────────────────────────────────────────
    private static final int BG        = 0xFF12121F;
    private static final int CARD      = 0xFF1E1E30;
    private static final int ACCENT    = 0xFF5C6BC0;
    private static final int ACCENT_LT = 0xFF9FA8DA;
    private static final int TEXT_PRI  = 0xFFECEFF1;
    private static final int TEXT_SEC  = 0xFF90A4AE;
    private static final int GREEN     = 0xFF43A047;
    private static final int RED_LT    = 0xFFEF9A9A;
    private static final int WARN      = 0xFFFFB74D;

    // ── state ─────────────────────────────────────────────────────────────────
    private int           currentStep = 0;
    private VpnProfile    profile;
    private ProfileRepository repo;
    private boolean       isNew;

    // step 0
    private EditText etName;

    // step 1 – credentials
    private EditText etClientId, etClientSecret, etProjectId;
    // step 1 – advanced (collapsible)
    private LinearLayout llAdvanced;
    private EditText etListenAddr, etFolderID, etTargetIP, etSNI, etHostHeader;
    private EditText etRefreshRate, etFlushRate;

    // step 2 – token
    private TextView  tvTokenHint, tvTokenStatus;
    private EditText  etCodeInput;   // user pastes redirect URL or raw code here
    private EditText  etTokenDirect; // or paste token directly
    private Button    btnAuthorize, btnExchange;
    private View      dividerOr;

    // navigation
    private static final int STEP_COUNT = 3;
    private LinearLayout[]   stepContainers;
    private TextView          tvStepBadge, tvStepTitle, tvStepSub;
    private View[]            dotViews;
    private Button            btnBack, btnNext, btnSave;

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), true);

        repo = new ProfileRepository(this);

        String pid = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (pid != null) {
            isNew = false;
            profile = null;
            for (VpnProfile p : repo.loadAll())
                if (p.id.equals(pid)) { profile = p; break; }
            if (profile == null) { finish(); return; }
        } else {
            isNew   = true;
            profile = new VpnProfile();
        }

        setContentView(buildRoot());
        showStep(0);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private View buildRoot() {
        // Root: dark background, full-height scroll
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true); 

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));
        sv.addView(root);

        // ── Header ────────────────────────────────────────────────────────
        root.addView(buildHeader());
        root.addView(space(16));

        // ── Step dot indicators ───────────────────────────────────────────
        root.addView(buildDots());
        root.addView(space(20));

        // ── Step title area ───────────────────────────────────────────────
        tvStepTitle = text(null, 20f, TEXT_PRI, Typeface.BOLD);
        tvStepSub   = text(null, 13f, TEXT_SEC, Typeface.NORMAL);
        tvStepSub.setPadding(0, dp(4), 0, 0);
        root.addView(tvStepTitle);
        root.addView(tvStepSub);
        root.addView(space(20));

        // ── Step containers ───────────────────────────────────────────────
        stepContainers = new LinearLayout[STEP_COUNT];
        for (int i = 0; i < STEP_COUNT; i++) {
            stepContainers[i] = new LinearLayout(this);
            stepContainers[i].setOrientation(LinearLayout.VERTICAL);
            stepContainers[i].setVisibility(View.GONE);
            root.addView(stepContainers[i]);
        }
        buildStep0(stepContainers[0]);
        buildStep1(stepContainers[1]);
        buildStep2(stepContainers[2]);

        // ── Navigation ────────────────────────────────────────────────────
        root.addView(space(24));
        root.addView(buildNavRow());

        return sv;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // back / close button
        TextView btnClose = new TextView(this);
        btnClose.setText("✕");
        btnClose.setTextColor(TEXT_SEC);
        btnClose.setTextSize(18f);
        btnClose.setPadding(0, 0, dp(16), 0);
        btnClose.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        row.addView(btnClose);

        tvStepBadge = text(isNew ? "New Profile" : "Edit Profile", 16f, ACCENT_LT, Typeface.BOLD);
        row.addView(tvStepBadge);

        return row;
    }

    private View buildDots() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        dotViews = new View[STEP_COUNT];
        String[] labels = {"Name", "Credentials", "Token"};
        for (int i = 0; i < STEP_COUNT; i++) {
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(0xFF2E2E4E);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(2), 1f);
                row.addView(line, lp);
            }
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);

            View dot = new View(this);
            LinearLayout.LayoutParams dpLP = new LinearLayout.LayoutParams(dp(12), dp(12));
            dot.setLayoutParams(dpLP);
            dot.setBackgroundColor(0xFF2E2E4E); // updated in showStep()
            dotViews[i] = dot;
            col.addView(dot);

            TextView lbl = text(labels[i], 10f, TEXT_SEC, Typeface.NORMAL);
            lbl.setPadding(0, dp(4), 0, 0);
            col.addView(lbl);

            LinearLayout.LayoutParams colLP = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            colLP.setMarginEnd(dp(4));
            row.addView(col, colLP);
        }
        return row;
    }

    private View buildNavRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        btnBack = navButton("← Back");
        btnBack.setOnClickListener(v -> navigate(-1));
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpB.setMarginEnd(dp(8));
        row.addView(btnBack, lpB);

        btnNext = navButton("Next →");
        btnNext.setBackgroundColor(ACCENT);
        btnNext.setOnClickListener(v -> navigate(+1));
        row.addView(btnNext, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnSave = navButton("✓  Save");
        btnSave.setBackgroundColor(GREEN);
        btnSave.setVisibility(View.GONE);
        btnSave.setOnClickListener(v -> saveAndFinish());
        row.addView(btnSave, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    // ── Step 0: Name ──────────────────────────────────────────────────────────

    private void buildStep0(LinearLayout c) {
        etName = field("e.g.  Work Profile", profile.name, false);
        c.addView(etName);

        // hint card
        c.addView(space(16));
        c.addView(infoCard(
                "ℹ️  This name is just a label — it won't affect how the tunnel works."
        ));
    }

    // ── Step 1: Google Credentials ────────────────────────────────────────────

    private void buildStep1(LinearLayout c) {
        // Google Cloud link
        c.addView(actionCard(
                "Google Cloud Console  ↗",
                "Open to get your Client ID & Secret",
                () -> openUrl("https://console.cloud.google.com/apis/credentials")
        ));
        c.addView(space(4));
        c.addView(infoCard(
                "In Cloud Console:\n" +
                        "  1. APIs & Services → Credentials\n" +
                        "  2. Create Credentials → OAuth 2.0 Client ID\n" +
                        "  3. Application type: Desktop app\n" +
                        "  4. Copy Client ID and Client Secret below"
        ));
        c.addView(space(16));

        c.addView(label("Client ID"));
        etClientId = field("123456-xxxx.apps.googleusercontent.com", profile.clientId, false);
        c.addView(etClientId);

        c.addView(space(12));
        c.addView(label("Client Secret"));
        etClientSecret = field("GOCSPX-…", profile.clientSecret, true);
        c.addView(etClientSecret);

        c.addView(space(12));
        c.addView(label("Project ID  (optional)"));
        etProjectId = field("my-project-123", profile.projectId, false);
        c.addView(etProjectId);


        // ── Advanced collapsible ──────────────────────────────────────────
        c.addView(space(20));

        TextView tvAdv = new TextView(this);
        tvAdv.setText("⚙  Advanced settings ▾");
        tvAdv.setTextColor(ACCENT_LT);
        tvAdv.setTextSize(13f);
        tvAdv.setPadding(0, dp(8), 0, dp(8));
        c.addView(tvAdv);

        llAdvanced = new LinearLayout(this);
        llAdvanced.setOrientation(LinearLayout.VERTICAL);
        llAdvanced.setVisibility(View.GONE);
        c.addView(llAdvanced);

        buildAdvanced(llAdvanced);

        tvAdv.setOnClickListener(v -> {
            boolean open = llAdvanced.getVisibility() == View.VISIBLE;
            llAdvanced.setVisibility(open ? View.GONE : View.VISIBLE);
            tvAdv.setText(open ? "⚙  Advanced settings ▾" : "⚙  Advanced settings ▴");
        });
    }

    private void buildAdvanced(LinearLayout c) {

        // ── Rates ─────────────────────────────────────────────────────────
        c.addView(space(16));

        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout colA = new LinearLayout(this);
        colA.setOrientation(LinearLayout.VERTICAL);
        colA.addView(label("Poll Rate (ms)"));
        etRefreshRate = field("200", String.valueOf(profile.refreshRateMs), false);
        etRefreshRate.setInputType(InputType.TYPE_CLASS_NUMBER);
        colA.addView(etRefreshRate);
        rateRow.addView(colA, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View hSpacer = new View(this);
        hSpacer.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
        rateRow.addView(hSpacer);

        LinearLayout colB = new LinearLayout(this);
        colB.setOrientation(LinearLayout.VERTICAL);
        colB.addView(label("Flush Rate (ms)"));
        etFlushRate = field("200", String.valueOf(profile.flushRateMs), false);
        etFlushRate.setInputType(InputType.TYPE_CLASS_NUMBER);
        colB.addView(etFlushRate);
        rateRow.addView(colB, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        c.addView(rateRow);
        //c.addView(infoCard(""));


        c.addView(space(8));

        c.addView(space(10));
        c.addView(label("Drive Folder ID"));
        etFolderID = field("", profile.folderId, false);
        c.addView(etFolderID);


        c.addView(label("SOCKS5 Listen Address"));
        etListenAddr = field("127.0.0.1:1080", profile.listenAddr, false);
        c.addView(etListenAddr);

        c.addView(space(10));
        c.addView(label("Domain Fronting — Target IP:Port"));
        etTargetIP = field("216.239.38.120:443", profile.targetIp, false);
        c.addView(etTargetIP);

        c.addView(space(10));
        c.addView(label("SNI"));
        etSNI = field("google.com", profile.sni, false);
        c.addView(etSNI);

        c.addView(space(10));
        c.addView(label("Host Header"));
        etHostHeader = field("www.googleapis.com", profile.hostHeader, false);
        c.addView(etHostHeader);

        c.addView(space(8));
    }

    // ── Step 2: Token ─────────────────────────────────────────────────────────

    private void buildStep2(LinearLayout c) {

        // Status badge
        tvTokenStatus = new TextView(this);
        tvTokenStatus.setPadding(dp(12), dp(8), dp(12), dp(8));
        tvTokenStatus.setTextSize(13f);
        tvTokenStatus.setVisibility(View.GONE);
        c.addView(tvTokenStatus);
        c.addView(space(8));

        // ── Option A: browser flow ────────────────────────────────────────
        c.addView(sectionLabel("Option A  —  Authorize via browser"));

        tvTokenHint = new TextView(this);
        tvTokenHint.setText(
                "1. Tap Authorize → Google login opens in your browser\n" +
                        "2. Accept the permissions\n" +
                        "3. The browser shows a blank/error page — that's fine\n" +
                        "4. Copy the FULL URL from the address bar and paste below\n" +
                        "5. Tap 'Get Token'");
        tvTokenHint.setTextColor(TEXT_SEC);
        tvTokenHint.setTextSize(13f);
        tvTokenHint.setLineSpacing(dp(3), 1f);
        c.addView(tvTokenHint);
        c.addView(space(12));

        btnAuthorize = new Button(this);
        btnAuthorize.setText("🔑  Authorize with Google");
        btnAuthorize.setBackgroundColor(ACCENT);
        btnAuthorize.setTextColor(Color.WHITE);
        btnAuthorize.setOnClickListener(v -> startOAuth());
        c.addView(btnAuthorize);
        c.addView(space(10));

        c.addView(label("Paste redirect URL (or just the code):"));
        etCodeInput = field("http://localhost/?code=4/0AX4...", "", false);
        etCodeInput.setMinLines(2);
        c.addView(etCodeInput);
        c.addView(space(10));

        btnExchange = new Button(this);
        btnExchange.setText("⇄  Get Token");
        btnExchange.setBackgroundColor(0xFF37474F);
        btnExchange.setTextColor(Color.WHITE);
        btnExchange.setOnClickListener(v -> exchangeCode());
        c.addView(btnExchange);

        // ── Divider ───────────────────────────────────────────────────────
        c.addView(space(20));
        LinearLayout divRow = new LinearLayout(this);
        divRow.setOrientation(LinearLayout.HORIZONTAL);
        divRow.setGravity(Gravity.CENTER_VERTICAL);
        View line1 = new View(this);
        line1.setBackgroundColor(0xFF2E2E4E);
        View line2 = new View(this);
        line2.setBackgroundColor(0xFF2E2E4E);
        TextView tvOr = text("  OR  ", 12f, TEXT_SEC, Typeface.NORMAL);
        divRow.addView(line1, new LinearLayout.LayoutParams(0, dp(1), 1f));
        divRow.addView(tvOr);
        divRow.addView(line2, new LinearLayout.LayoutParams(0, dp(1), 1f));
        c.addView(divRow);
        c.addView(space(20));

        // ── Option B: paste token directly ───────────────────────────────
        c.addView(sectionLabel("Option B  —  Paste refresh token directly"));
        c.addView(label("Refresh Token:"));
        etTokenDirect = field("1//0gXx…", profile.refreshToken != null ? profile.refreshToken : "", true);
        c.addView(etTokenDirect);
        c.addView(space(6));
        TextView tvDirectHint = text(
                "Already have a refresh_token? Paste it here and skip Option A.",
                12f, TEXT_SEC, Typeface.NORMAL);
        c.addView(tvDirectHint);

        // Pre-fill if profile already has token
        if (profile.refreshToken != null && !profile.refreshToken.isEmpty()) {
            setTokenStatus(true, "✓  Token already saved — you can update it or keep as-is.");
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigate(int dir) {
        int next = currentStep + dir;
        if (next < 0) { setResult(RESULT_CANCELED); finish(); return; }
        if (next >= STEP_COUNT) { saveAndFinish(); return; }

        // validate before advancing
        if (dir > 0 && !validateStep(currentStep)) return;

        // commit current step's data into profile object
        commitStep(currentStep);

        showStep(next);
    }

    private void showStep(int step) {
        currentStep = step;

        for (int i = 0; i < STEP_COUNT; i++) {
            stepContainers[i].setVisibility(i == step ? View.VISIBLE : View.GONE);
            dotViews[i].setBackgroundColor(i == step ? ACCENT : (i < step ? GREEN : 0xFF2E2E4E));
        }

        String[] titles = {
                "Profile Name",
                "Google Credentials",
                "Authorization Token"
        };
        String[] subs = {
                "Give this profile a name so you can recognize it.",
                "Enter your Google OAuth 2.0 Client credentials.",
                "Authorize the app to access your Google Drive."
        };
        tvStepTitle.setText(titles[step]);
        tvStepSub.setText(subs[step]);
        tvStepBadge.setText(isNew ? "New Profile" : "Edit Profile");

        btnBack.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        btnNext.setVisibility(step < STEP_COUNT - 1 ? View.VISIBLE : View.GONE);
        btnSave.setVisibility(step == STEP_COUNT - 1 ? View.VISIBLE : View.GONE);
    }

    private boolean validateStep(int step) {
        switch (step) {
            case 0:
                if (etName.getText().toString().trim().isEmpty()) {
                    etName.setError("Please enter a name");
                    return false;
                }
                return true;
            case 1:
                if (etClientId.getText().toString().trim().isEmpty()) {
                    etClientId.setError("Required");
                    return false;
                }
                if (etClientSecret.getText().toString().trim().isEmpty()) {
                    etClientSecret.setError("Required");
                    return false;
                }
                return true;
            case 2:
                // Token is technically optional at save time but warn user
                String direct = etTokenDirect.getText().toString().trim();
                if (direct.isEmpty() && (profile.refreshToken == null || profile.refreshToken.isEmpty())) {
                    // Show a warning but allow saving — user might not have token yet
                    Toast.makeText(this,
                            "⚠ No token set — you won't be able to connect until you add one.",
                            Toast.LENGTH_LONG).show();
                }
                return true;
        }
        return true;
    }

    private void commitStep(int step) {
        switch (step) {
            case 0:
                profile.name = etName.getText().toString().trim();
                break;
            case 1:
                profile.clientId     = etClientId.getText().toString().trim();
                profile.clientSecret = etClientSecret.getText().toString().trim();
                profile.projectId    = etProjectId.getText().toString().trim();
                if (llAdvanced.getVisibility() == View.VISIBLE) commitAdvanced();
                break;
            case 2:
                String direct = etTokenDirect.getText().toString().trim();
                if (!direct.isEmpty()) profile.refreshToken = direct;
                break;
        }
    }

    private void commitAdvanced() {
        String la = etListenAddr.getText().toString().trim();
        if (!la.isEmpty()) profile.listenAddr = la;
        String fid = etFolderID.getText().toString().trim();
        if (!fid.isEmpty()) profile.folderId = fid;
        String tip = etTargetIP.getText().toString().trim();
        if (!tip.isEmpty()) profile.targetIp = tip;
        String sni = etSNI.getText().toString().trim();
        if (!sni.isEmpty()) profile.sni = sni;
        String hh = etHostHeader.getText().toString().trim();
        if (!hh.isEmpty()) profile.hostHeader = hh;
        try { profile.refreshRateMs = Integer.parseInt(etRefreshRate.getText().toString().trim()); }
        catch (Exception ignored) {}
        try { profile.flushRateMs = Integer.parseInt(etFlushRate.getText().toString().trim()); }
        catch (Exception ignored) {}
    }

    private void saveAndFinish() {
        commitStep(currentStep);

        // اگه token داریم ولی folderID نداریم، بساز
        if ((profile.folderId == null || profile.folderId.isEmpty())
                && profile.refreshToken != null && !profile.refreshToken.isEmpty()) {

            btnSave.setEnabled(false);
            btnSave.setText("⏳ Created Folder...");
            setTokenStatus(null, "Create Flow-Data folder...");

            String cid = etClientId.getText().toString().trim();
            String sec = etClientSecret.getText().toString().trim();
            String rt  = profile.refreshToken;

            new AsyncTask<Void, Void, String[]>() {
                @Override
                protected String[] doInBackground(Void... v) {
                    try {
                        String accessToken = getAccessToken(cid, sec, rt);
                        String fid = findDriveFolder(accessToken, "Flow-Data");
                        if (fid == null || fid.isEmpty())
                            fid = createDriveFolder(accessToken, "Flow-Data");
                        return new String[]{"ok", fid};
                    } catch (Exception e) {
                        return new String[]{"err", e.getMessage()};
                    }
                }

                @Override
                protected void onPostExecute(String[] res) {
                    btnSave.setEnabled(true);
                    btnSave.setText("✓  Save");
                    if ("ok".equals(res[0])) {
                        profile.folderId = res[1];
                        if (etFolderID != null) etFolderID.setText(res[1]);
                    }
                    // حالا save کن
                    repo.save(profile);
                    Intent result = new Intent();
                    result.putExtra(EXTRA_PROFILE_ID, profile.id);
                    setResult(RESULT_OK, result);
                    finish();
                }
            }.execute();

            return; // منتظر AsyncTask بمون
        }

        repo.save(profile);
        Intent result = new Intent();
        result.putExtra(EXTRA_PROFILE_ID, profile.id);
        setResult(RESULT_OK, result);
        finish();
    }

    // ── OAuth flow ────────────────────────────────────────────────────────────

    private void startOAuth() {
        // Make sure credentials are committed from step 1
        String cid = etClientId != null ? etClientId.getText().toString().trim() : profile.clientId;
        String sec = etClientSecret != null ? etClientSecret.getText().toString().trim() : profile.clientSecret;
        String pid = etProjectId != null ? etProjectId.getText().toString().trim() : profile.projectId;

        if (cid.isEmpty() || sec.isEmpty()) {
            Toast.makeText(this,
                    "Go back to step 2 and fill in Client ID and Client Secret first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Build credentials JSON on the fly
        String credJSON = buildCredentials(cid, sec, pid);

        String oauthUrl;
        try {
            oauthUrl = Androidlib.getOAuthURL(credJSON);
        } catch (Exception e) {
            setTokenStatus(false, "Error building OAuth URL: " + e.getMessage());
            return;
        }

        openUrl(oauthUrl);
        setTokenStatus(null, "Browser opened — authorize and paste the redirect URL below.");
    }

    private void exchangeCode() {
        String codeOrUrl = etCodeInput.getText().toString().trim();
        if (codeOrUrl.isEmpty()) {
            Toast.makeText(this, "Paste the redirect URL or code first.", Toast.LENGTH_SHORT).show();
            return;
        }

        String cid = etClientId != null ? etClientId.getText().toString().trim() : profile.clientId;
        String sec = etClientSecret != null ? etClientSecret.getText().toString().trim() : profile.clientSecret;
        String pid = etProjectId != null ? etProjectId.getText().toString().trim() : profile.projectId;
        String credJSON = buildCredentials(cid, sec, pid);
        String dataDir  = getFilesDir().getAbsolutePath();

        // Build transport config from current advanced values (or profile defaults)
        String tip = (etTargetIP != null && !etTargetIP.getText().toString().trim().isEmpty())
                ? etTargetIP.getText().toString().trim() : profile.targetIp;
        String sni = (etSNI != null && !etSNI.getText().toString().trim().isEmpty())
                ? etSNI.getText().toString().trim() : profile.sni;
        String hh  = (etHostHeader != null && !etHostHeader.getText().toString().trim().isEmpty())
                ? etHostHeader.getText().toString().trim() : profile.hostHeader;

        String transportJSON = (tip != null && !tip.isEmpty())
                ? "{\"TargetIP\":\"" + tip + "\",\"SNI\":\"" + sni + "\",\"HostHeader\":\"" + hh + "\"}"
                : "";

        btnExchange.setEnabled(false);
        btnExchange.setText("⏳  Exchanging…");
        setTokenStatus(null, "Talking to Google…");

        // Run on background thread (network)
        String finalCredJSON   = credJSON;
        String finalCodeOrUrl  = codeOrUrl;
        String finalTransport  = transportJSON;

        new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... voids) {
                try {
                    String tokenJSON = Androidlib.exchangeOAuthCode(
                            finalCredJSON, finalCodeOrUrl, dataDir, finalTransport);
                    return new String[]{"ok", tokenJSON};
                } catch (Exception e) {
                    return new String[]{"err", e.getMessage()};
                }
            }

            @Override
            protected void onPostExecute(String[] res) {
                btnExchange.setEnabled(true);
                btnExchange.setText("⇄  Get Token");
                if ("ok".equals(res[0])) {
                    // Parse refresh_token from returned JSON
                    try {
                        org.json.JSONObject j = new org.json.JSONObject(res[1]);
                        String rt = j.optString("refresh_token", "");
                        if (!rt.isEmpty()) {
                            profile.refreshToken = rt;
                            etTokenDirect.setText(rt);
                            etCodeInput.setText("");
                            setTokenStatus(true, "✓  Token obtained! در حال جستجوی فولدر Drive…");
                            // auto-fetch folder after token
                            autoFetchFolder(rt);
                        } else {
                            setTokenStatus(false, "Token exchange succeeded but no refresh_token found.");
                        }
                    } catch (Exception e) {
                        setTokenStatus(false, "Parse error: " + e.getMessage());
                    }
                } else {
                    setTokenStatus(false, "Error: " + res[1]);
                }
            }
        }.execute();
    }

    /**
     * بعد از گرفتن refresh_token، یه access_token موقت میگیره و
     * فولدر "Flow-Data" رو توی Drive پیدا/میسازه.
     */
    private void autoFetchFolder(String refreshToken) {
        String cid = etClientId != null ? etClientId.getText().toString().trim() : profile.clientId;
        String sec = etClientSecret != null ? etClientSecret.getText().toString().trim() : profile.clientSecret;

        new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... v) {
                try {
                    String accessToken = getAccessToken(cid, sec, refreshToken);
                    String fid = findDriveFolder(accessToken, "Flow-Data");
                    if (fid == null || fid.isEmpty())
                        fid = createDriveFolder(accessToken, "Flow-Data");
                    return new String[]{"ok", fid};
                } catch (Exception e) {
                    return new String[]{"err", e.getMessage()};
                }
            }

            @Override
            protected void onPostExecute(String[] res) {
                if ("ok".equals(res[0]) && res[1] != null && !res[1].isEmpty()) {
                    profile.folderId = res[1];
                    if (etFolderID != null) etFolderID.setText(res[1]);
                    setTokenStatus(true,
                            "✓  Token + Folder ID آماده!\nFolder ID: " + res[1] + "\n\nمیتونی Save کنی.");
                } else {
                    setTokenStatus(true,
                            "✓  Token دریافت شد.\n⚠ Folder ID بدست نیومد: " + res[1] +
                                    "\nاپ موقع اتصال خودش میسازه — یا از Advanced دستی وارد کن.");
                }
            }
        }.execute();
    }

    private String getAccessToken(String clientId, String clientSecret, String refreshToken) throws Exception {
        java.net.URL url = new java.net.URL("https://oauth2.googleapis.com/token");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String body = "grant_type=refresh_token"
                + "&refresh_token=" + java.net.URLEncoder.encode(refreshToken, "UTF-8")
                + "&client_id="     + java.net.URLEncoder.encode(clientId,     "UTF-8")
                + "&client_secret=" + java.net.URLEncoder.encode(clientSecret, "UTF-8");

        conn.getOutputStream().write(body.getBytes("UTF-8"));

        if (conn.getResponseCode() != 200)
            throw new Exception("Token refresh failed: HTTP " + conn.getResponseCode());

        byte[] resp = conn.getInputStream().readAllBytes();
        org.json.JSONObject j = new org.json.JSONObject(new String(resp, "UTF-8"));
        return j.getString("access_token");
    }

    private String findDriveFolder(String accessToken, String name) throws Exception {
        String q = java.net.URLEncoder.encode(
                "name='" + name + "' and mimeType='application/vnd.google-apps.folder' and trashed=false",
                "UTF-8");
        java.net.URL url = new java.net.URL(
                "https://www.googleapis.com/drive/v3/files?q=" + q + "&fields=files(id,name)");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);

        if (conn.getResponseCode() != 200)
            throw new Exception("Drive list failed: HTTP " + conn.getResponseCode());

        byte[] resp = conn.getInputStream().readAllBytes();
        org.json.JSONObject j    = new org.json.JSONObject(new String(resp, "UTF-8"));
        org.json.JSONArray files = j.optJSONArray("files");
        if (files != null && files.length() > 0)
            return files.getJSONObject(0).getString("id");
        return "";
    }

    private String createDriveFolder(String accessToken, String name) throws Exception {
        java.net.URL url = new java.net.URL("https://www.googleapis.com/drive/v3/files");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json");

        String body = "{\"name\":\"" + name + "\","
                + "\"mimeType\":\"application/vnd.google-apps.folder\"}";
        conn.getOutputStream().write(body.getBytes("UTF-8"));

        if (conn.getResponseCode() != 200)
            throw new Exception("Drive create folder failed: HTTP " + conn.getResponseCode());

        byte[] resp = conn.getInputStream().readAllBytes();
        org.json.JSONObject j = new org.json.JSONObject(new String(resp, "UTF-8"));
        return j.getString("id");
    }

    private void setTokenStatus(Boolean ok, String msg) {
        tvTokenStatus.setVisibility(View.VISIBLE);
        tvTokenStatus.setText(msg);
        if (ok == null) {
            tvTokenStatus.setBackgroundColor(0xFF1A2733);
            tvTokenStatus.setTextColor(WARN);
        } else if (ok) {
            tvTokenStatus.setBackgroundColor(0xFF1B3A1F);
            tvTokenStatus.setTextColor(GREEN);
        } else {
            tvTokenStatus.setBackgroundColor(0xFF3A1B1B);
            tvTokenStatus.setTextColor(RED_LT);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String buildCredentials(String cid, String sec, String pid) {
        return "{\"installed\":{"
                + "\"client_id\":\""     + esc(cid) + "\","
                + "\"project_id\":\""    + esc(pid) + "\","
                + "\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\","
                + "\"auth_provider_x509_cert_url\":\"https://www.googleapis.com/oauth2/v1/certs\","
                + "\"client_secret\":\"" + esc(sec) + "\","
                + "\"redirect_uris\":[\"http://localhost\"]}}";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser found.", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Widget factories ──────────────────────────────────────────────────────

    private EditText field(String hint, String value, boolean password) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value != null ? value : "");
        et.setTextColor(TEXT_PRI);
        et.setHintTextColor(0xFF546E7A);
        et.setBackgroundColor(CARD);
        et.setPadding(dp(12), dp(12), dp(12), dp(12));
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            et.setTypeface(Typeface.MONOSPACE);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        et.setLayoutParams(lp);
        return et;
    }

    private TextView label(String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(TEXT_SEC);
        tv.setTextSize(12f);
        tv.setPadding(0, dp(10), 0, dp(2));
        return tv;
    }

    private TextView sectionLabel(String txt) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextColor(ACCENT_LT);
        tv.setTextSize(13f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView text(String txt, float size, int color, int style) {
        TextView tv = new TextView(this);
        if (txt != null) tv.setText(txt);
        tv.setTextSize(size);
        tv.setTextColor(color);
        tv.setTypeface(null, style);
        return tv;
    }

    private View infoCard(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(TEXT_SEC);
        tv.setTextSize(12.5f);
        tv.setLineSpacing(dp(3), 1f);
        tv.setBackgroundColor(CARD);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        return tv;
    }

    private View actionCard(String title, String sub, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(CARD);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setOnClickListener(v -> action.run());

        TextView tvT = text(title, 14f, ACCENT_LT, Typeface.BOLD);
        TextView tvS = text(sub,   12f, TEXT_SEC,   Typeface.NORMAL);
        tvS.setPadding(0, dp(2), 0, 0);
        card.addView(tvT);
        card.addView(tvS);
        return card;
    }

    private Button navButton(String txt) {
        Button b = new Button(this);
        b.setText(txt);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(0xFF37474F);
        return b;
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dp)));
        return v;
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }
}