package io.github.katachinosekai.quillremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final String HOME = "https://claude.ai/code";
    static final int BG = 0xFF262624;
    static final int SIDE = 0xFF1F1F1D;
    static final int ACCENT = 0xFFD97757;
    static final int TEXT = 0xFFECEAE3;
    static final int MUTED = 0xFF9C9A92;
    static final int ZOOM_MIN = 80;
    static final int ZOOM_MAX = 200;
    static final int ZOOM_STEP = 10;
    static final int FILE_PICK = 7;
    static final int SYNC_PICK = 8;
    static final String QUILL_SYNCED = "0.1.1";

    WebView web;
    ProgressBar progress;
    TextView centerBtn;
    TextView zoomLabel;
    SharedPreferences prefs;
    String skin;
    ValueCallback<Uri[]> pendingFiles;
    SessionBars bars;
    FolderSync sync;
    Updater updater;
    Updater.Release pendingUpdate;
    TextView updateBanner;

    boolean trustedPage() {
        String u = web == null ? null : web.getUrl();
        return u != null && inApp(Uri.parse(u));
    }

    void toast(String m) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
    }

    View updateBanner() {
        updateBanner = new TextView(this);
        updateBanner.setTextColor(0xFF1F1F1D);
        updateBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        updateBanner.setTypeface(Typeface.DEFAULT_BOLD);
        updateBanner.setBackgroundColor(ACCENT);
        updateBanner.setPadding(dp(14), dp(10), dp(14), dp(10));
        updateBanner.setContentDescription("Update banner");
        updateBanner.setVisibility(View.GONE);
        updateBanner.setOnClickListener(v -> {
            if (pendingUpdate != null) {
                updater.install(pendingUpdate);
            }
        });
        return updateBanner;
    }
    java.util.Map<String, String> folderMap = new java.util.LinkedHashMap<>();
    java.util.List<String> folderOrder = new java.util.ArrayList<>();

    void onFolders(java.util.Map<String, String> map, java.util.List<String> order) {
        folderMap = map;
        folderOrder = order;
        inject();
    }

    boolean handlePair(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"quillpair".equals(data.getScheme())) {
            return false;
        }
        acceptPair(data);
        return true;
    }

    void acceptPair(Uri data) {
        String name = FolderSync.pair(prefs, data);
        android.widget.Toast.makeText(this, name == null ? "Not a valid Quill pairing link" : "Paired with " + name, android.widget.Toast.LENGTH_LONG).show();
        if (name != null) {
            sync.refresh();
        }
    }
    TextView sideArrow;

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (bars != null) {
            bars.applyMode(false);
        }
    }

    void openSession(String id) {
        if (web == null || !SessionBars.SESSION.matcher("/code/" + id).matches()) {
            return;
        }
        web.evaluateJavascript("window.__quill?(window.__quill.open('" + id + "'),1):0", v -> {
            if (!"1".equals(v) && web != null) {
                web.loadUrl(HOME + "/" + id);
            }
        });
    }

    void loadHome() {
        if (web != null) {
            web.loadUrl(HOME);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("quill", MODE_PRIVATE);
        debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        skin = asset("quill.js");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(bar(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        bars = new SessionBars(this, prefs);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        body.addView(bars.side, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        body.addView(column, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        column.addView(bars.tabScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        column.addView(bars.bookScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(SIDE));
        column.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        column.addView(updateBanner(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(signInBanner(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        web.setBackgroundColor(BG);
        setupWeb();
        column.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        bars.applyMode(false);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        getWindow().getDecorView().setBackgroundColor(SIDE);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }

        if (state != null) {
            web.restoreState(state);
        }
        sync = new FolderSync(prefs, getContentResolver(), this::onFolders);
        Updater.clean(this);
        updater = new Updater(this, prefs, new Updater.Listener() {
            @Override
            public void onUpdate(Updater.Release r) {
                android.util.Log.i("QuillUpdate", "available " + r.version);
                pendingUpdate = r;
                updateBanner.setText("Quill for Android " + r.version + " is available. Tap to update.");
                updateBanner.setVisibility(View.VISIBLE);
            }

            @Override
            public void onMessage(String msg) {
                android.util.Log.i("QuillUpdate", msg);
                toast(msg);
            }
        });
        if (debuggable && getIntent() != null) {
            String f = getIntent().getStringExtra("quill.updateFeed");
            if (f != null && testHost(Uri.parse(f))) {
                updater.feed = f;
                updater.allowTestHost = true;
                prefs.edit().putLong("update.checked", 0).apply();
            }
        }
        boolean pairing = handlePair(getIntent());
        String test = testUrl(getIntent());
        if (test != null) {
            web.loadUrl(test);
        } else if (state == null || web.getUrl() == null) {
            web.loadUrl(pairing ? startUrl(null) : startUrl(getIntent()));
        }
    }

    View bar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(SIDE);
        bar.setPadding(dp(10), 0, dp(4), 0);

        sideArrow = new TextView(this);
        sideArrow.setContentDescription("Sidebar");
        sideArrow.setTextColor(ACCENT);
        sideArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sideArrow.setTypeface(Typeface.DEFAULT_BOLD);
        sideArrow.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(6));
        ring.setColor(0x30D97757);
        sideArrow.setBackground(ring);
        sideArrow.setOnClickListener(v -> bars.toggleSide());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(32), dp(32));
        ap.setMargins(0, 0, dp(10), 0);
        bar.addView(sideArrow, ap);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icon);
        bar.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView title = new TextView(this);
        title.setText("Quill");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(10), 0, dp(10), 0);
        title.setOnClickListener(v -> {
            if (bars.vertical) {
                bars.toggleSide();
            } else {
                web.evaluateJavascript("window.__quill&&window.__quill.sessions()", null);
            }
        });
        title.setOnLongClickListener(v -> {
            web.loadUrl(HOME);
            return true;
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, tp);

        bar.addView(button("A−", "Smaller text", v -> zoom(-ZOOM_STEP)));
        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(MUTED);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        zoomLabel.setGravity(Gravity.CENTER);
        zoomLabel.setMinWidth(dp(38));
        bar.addView(zoomLabel);
        bar.addView(button("A+", "Larger text", v -> zoom(ZOOM_STEP)));
        centerBtn = button("≡", "Center everything", v -> {
            boolean all = centerCount() < CENTER_KEYS.length;
            SharedPreferences.Editor e = prefs.edit();
            for (String k : CENTER_KEYS) {
                e.putBoolean(k, all);
            }
            e.apply();
            syncCenter();
            inject();
        });
        bar.addView(centerBtn);
        bar.addView(button("⚙", "Settings", v -> settings()));
        bar.addView(button("⟳", "Reload", v -> web.reload()));
        syncCenter();
        return bar;
    }

    static final String[] CENTER_KEYS = {"centerText", "centerInput", "centerStatus"};
    static final String[] CENTER_LABELS = {"Chat text", "Typing", "Status"};

    boolean centerOn(String key) {
        return prefs.getBoolean(key, key.equals("centerText"));
    }

    int centerCount() {
        int n = 0;
        for (String k : CENTER_KEYS) {
            if (centerOn(k)) {
                n++;
            }
        }
        return n;
    }

    void settings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        CheckBox[] checks = new CheckBox[CENTER_KEYS.length];
        CheckBox all = check("Center everything", centerCount() == CENTER_KEYS.length);
        boolean[] syncing = {false};
        Runnable syncAll = () -> {
            syncing[0] = true;
            all.setChecked(centerCount() == CENTER_KEYS.length);
            all.setAlpha(centerCount() > 0 && centerCount() < CENTER_KEYS.length ? 0.6f : 1f);
            syncing[0] = false;
        };
        box.addView(all);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(24), 0, 0, 0);
        for (int i = 0; i < CENTER_KEYS.length; i++) {
            String key = CENTER_KEYS[i];
            checks[i] = check(CENTER_LABELS[i], centerOn(key));
            checks[i].setOnCheckedChangeListener((b, on) -> {
                prefs.edit().putBoolean(key, on).apply();
                syncAll.run();
                syncCenter();
                inject();
            });
            row.addView(checks[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(row);
        all.setOnCheckedChangeListener((b, on) -> {
            if (syncing[0]) {
                return;
            }
            for (CheckBox c : checks) {
                c.setChecked(on);
            }
        });
        syncAll.run();

        TextView modeLabel = new TextView(this);
        modeLabel.setText("Tabs");
        modeLabel.setTextColor(MUTED);
        modeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        modeLabel.setPadding(0, dp(10), 0, 0);
        box.addView(modeLabel);
        android.widget.RadioGroup modes = new android.widget.RadioGroup(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        String[][] modeOpts = {{"Auto", "auto"}, {"Horizontal", "horizontal"}, {"Vertical", "vertical"}};
        String curMode = prefs.getString("tabMode", "auto");
        for (int i = 0; i < modeOpts.length; i++) {
            android.widget.RadioButton r = new android.widget.RadioButton(this);
            r.setId(View.generateViewId());
            r.setText(modeOpts[i][0]);
            r.setTextColor(TEXT);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            r.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            r.setMinHeight(dp(44));
            String val = modeOpts[i][1];
            r.setChecked(val.equals(curMode));
            r.setOnCheckedChangeListener((b, on) -> {
                if (on) {
                    prefs.edit().putString("tabMode", val).apply();
                    bars.applyMode(true);
                }
            });
            modes.addView(r, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(modes);

        LinearLayout barsRow = new LinearLayout(this);
        for (String[] b : new String[][]{{"Tab bar", "showTabs"}, {"Folder bar", "showFolders"}}) {
            CheckBox c = check(b[0], prefs.getBoolean(b[1], true));
            c.setOnCheckedChangeListener((v, on) -> {
                prefs.edit().putBoolean(b[1], on).apply();
                bars.render();
            });
            barsRow.addView(c, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(barsRow);

        box.addView(slider("Chat width", "chatWidth", 40));
        box.addView(slider("Message box width", "inputWidth", 25));

        TextView ver = new TextView(this);
        ver.setText("Quill for Android " + updater.current() + " · synced with Quill " + QUILL_SYNCED);
        ver.setTextColor(MUTED);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ver.setPadding(0, dp(14), 0, 0);
        box.addView(ver);
        box.addView(textButton("Check for updates", v -> {
            toast("Checking for updates...");
            updater.check(true);
        }));

        TextView fl = new TextView(this);
        fl.setText("Folders from Quill");
        fl.setTextColor(MUTED);
        fl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fl.setPadding(0, dp(14), 0, dp(4));
        box.addView(fl);
        TextView st = new TextView(this);
        st.setText(sync.describe());
        st.setTextColor(TEXT);
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(st);
        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.VERTICAL);
        pr.addView(textButton("Paste pairing link", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence t = cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0
                    ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
            acceptPair(t == null ? null : Uri.parse(t.toString().trim()));
            st.setText(sync.describe());
        }));
        pr.addView(textButton("Choose synced file", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(i, SYNC_PICK);
            } catch (ActivityNotFoundException ignored) {
            }
        }));
        TextView sl = new TextView(this);
        sl.setText("Sign-in");
        sl.setTextColor(MUTED);
        sl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sl.setPadding(0, dp(14), 0, dp(4));
        pr.addView(sl);
        pr.addView(textButton("Open claude.ai links in Quill", v -> openLinkSettings()));
        pr.addView(textButton("Paste sign-in link", v -> pasteSignInLink()));
        pr.addView(textButton("Unpair", v -> {
            sync.unpair();
            st.setText(sync.describe());
        }));
        box.addView(pr);

        accent(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Quill")
                .setView(scroll(box))
                .setPositiveButton("Done", null)
                .show());
    }

    TextView banner;

    View scroll(View v) {
        android.widget.ScrollView s = new android.widget.ScrollView(this);
        s.addView(v);
        return s;
    }

    void accent(AlertDialog d) {
        for (int b : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            if (d.getButton(b) != null) {
                d.getButton(b).setTextColor(ACCENT);
            }
        }
    }

    static boolean thirdPartySignIn(Uri u) {
        String h = u.getHost();
        if (h == null) {
            return false;
        }
        return h.equals("accounts.google.com") || h.endsWith(".accounts.google.com")
                || h.equals("appleid.apple.com") || h.equals("idmsa.apple.com");
    }

    void signInHelp() {
        accent(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Sign in with email")
                .setMessage("Google and Apple don't allow signing in inside apps, so that button can't work here.\n\n"
                        + "Use \"Continue with email\" with the same address (your Gmail address works) and you get the same Claude account and sessions.\n\n"
                        + "If the email has a code, type it here. If it has a link, either allow Quill to open claude.ai links (button below, once), "
                        + "or copy the link and use ⚙ → Paste sign-in link.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Open claude.ai links in Quill", (d, w) -> openLinkSettings())
                .show());
    }

    void openLinkSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 31) {
            i = new Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:" + getPackageName()));
        } else {
            i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        }
        try {
            startActivity(i);
            android.widget.Toast.makeText(this, "Add links → tick claude.ai", android.widget.Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
    }

    void pasteSignInLink() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        CharSequence t = cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0
                ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
        Uri u = t == null ? null : Uri.parse(t.toString().trim());
        if (u == null || !inApp(u)) {
            android.widget.Toast.makeText(this, "Copy the claude.ai sign-in link from the email first", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        web.loadUrl(u.toString());
    }

    View signInBanner() {
        banner = new TextView(this);
        banner.setText("Sign in with \"Continue with email\" (your Gmail address works). Google and Apple sign-in don't work inside apps. Tap to hide.");
        banner.setTextColor(TEXT);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        banner.setBackgroundColor(0xFF3A2A22);
        banner.setPadding(dp(14), dp(10), dp(14), dp(10));
        banner.setContentDescription("Sign-in tip");
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> {
            prefs.edit().putBoolean("signInTipHidden", true).apply();
            banner.setVisibility(View.GONE);
        });
        return banner;
    }

    void syncSignInBanner(String url) {
        if (banner == null) {
            return;
        }
        Uri u = url == null ? null : Uri.parse(url);
        String path = u == null || u.getPath() == null ? "" : u.getPath();
        boolean login = u != null && inApp(u) && (path.startsWith("/login") || path.startsWith("/signup"));
        banner.setVisibility(login && !prefs.getBoolean("signInTipHidden", false) ? View.VISIBLE : View.GONE);
    }

    TextView textButton(String label, View.OnClickListener onTap) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(ACCENT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(44));
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setOnClickListener(onTap);
        return b;
    }

    CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        c.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        c.setChecked(on);
        c.setMinHeight(dp(44));
        return c;
    }

    View slider(String label, String key, int lo) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(12), 0, 0);
        TextView t = new TextView(this);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        int cur = prefs.getInt(key, 100);
        t.setText(label + ": " + cur + "%");
        SeekBar s = new SeekBar(this);
        s.setMin(lo);
        s.setMax(100);
        s.setProgress(cur);
        s.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int v, boolean user) {
                t.setText(label + ": " + v + "%");
                prefs.edit().putInt(key, v).apply();
                inject();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        col.addView(t);
        col.addView(s, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        return col;
    }

    TextView button(String label, String desc, View.OnClickListener onTap) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setContentDescription(desc);
        b.setTextColor(ACCENT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(44));
        b.setMinHeight(dp(44));
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(7));
        ring.setColor(0x30D97757);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(36));
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setBackground(ring);
        b.setOnClickListener(onTap);
        return b;
    }

    void syncCenter() {
        int n = centerCount();
        centerBtn.setTextColor(n == CENTER_KEYS.length ? TEXT : ACCENT);
        int fill = n == CENTER_KEYS.length ? 0x80D97757 : n > 0 ? 0x55D97757 : 0x30D97757;
        ((GradientDrawable) centerBtn.getBackground()).setColor(fill);
    }

    void zoom(int delta) {
        int z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, prefs.getInt("zoom", 115) + delta));
        prefs.edit().putInt("zoom", z).apply();
        web.getSettings().setTextZoom(z);
        zoomLabel.setText(z + "%");
    }

    void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        web.addJavascriptInterface(new SessionBars.Bridge(bars), "QuillNative");
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(prefs.getInt("zoom", 115));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (inApp(u)) {
                    return false;
                }
                if (!req.isForMainFrame()) {
                    return false;
                }
                if (thirdPartySignIn(u)) {
                    signInHelp();
                    return true;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException e) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                inject();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject();
                syncSignInBanner(url);
                if (url != null && url.startsWith("https://claude.ai/")) {
                    prefs.edit().putString("last", url).apply();
                }
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                if (view == web) {
                    ((android.view.ViewGroup) web.getParent()).removeView(web);
                    web.destroy();
                    web = null;
                    recreate();
                }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
                if (p >= 60) {
                    inject();
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (pendingFiles != null) {
                    pendingFiles.onReceiveValue(null);
                }
                pendingFiles = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_PICK);
                } catch (ActivityNotFoundException e) {
                    pendingFiles = null;
                    return false;
                }
                return true;
            }
        });
        zoomLabel.setText(prefs.getInt("zoom", 115) + "%");
    }

    static boolean debuggable;

    static boolean testHost(Uri u) {
        return debuggable && "http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost());
    }

    String testUrl(Intent intent) {
        if (!debuggable || intent == null) {
            return null;
        }
        String t = intent.getStringExtra("quill.testUrl");
        if (t == null) {
            return null;
        }
        Uri u = Uri.parse(t);
        return testHost(u) || "chrome://crash".equals(t) ? t : null;
    }

    static boolean inApp(Uri u) {
        String host = u.getHost();
        if (testHost(u)) {
            return true;
        }
        if (host == null || !"https".equals(u.getScheme())) {
            return false;
        }
        return host.equals("claude.ai") || host.endsWith(".claude.ai")
                || host.equals("claude.com") || host.endsWith(".claude.com")
                || host.equals("anthropic.com") || host.endsWith(".anthropic.com");
    }

    void inject() {
        if (skin == null || web == null) {
            return;
        }
        String p = "window.__quillPrefs={center:{chat:" + centerOn("centerText")
                + ",input:" + centerOn("centerInput")
                + ",status:" + centerOn("centerStatus")
                + "},chatWidth:" + prefs.getInt("chatWidth", 100)
                + ",inputWidth:" + prefs.getInt("inputWidth", 100)
                + ",drawer:" + (bars == null || !bars.vertical)
                + ",folders:" + new org.json.JSONObject(folderMap)
                + ",order:" + new org.json.JSONArray(folderOrder) + "};";
        web.evaluateJavascript(p + skin, null);
    }

    String startUrl(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && inApp(data)) {
            return data.toString();
        }
        String last = prefs.getString("last", null);
        return last != null && last.startsWith("https://claude.ai/code") ? last : HOME;
    }

    String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    void back() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation")
    public void onBackPressed() {
        back();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (handlePair(intent)) {
            return;
        }
        String test = testUrl(intent);
        if (test != null) {
            web.loadUrl(test);
            return;
        }
        Uri data = intent.getData();
        if (data != null && inApp(data)) {
            web.loadUrl(data.toString());
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == SYNC_PICK) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                }
                sync.setFile(u);
                android.widget.Toast.makeText(this, "Reading folders from the synced file", android.widget.Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (req == FILE_PICK && pendingFiles != null) {
            pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            pendingFiles = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) {
            web.saveState(out);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        if (sync != null) {
            sync.stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sync != null) {
            sync.start();
        }
        if (updater != null) {
            updater.checkIfDue();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
