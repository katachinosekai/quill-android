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
    static final int FILE_PICK = 7;
    static final int SYNC_PICK = 8;
    static final int PAIR_SCAN = 10;
    Runnable pairStatus;
    static final String QUILL_SYNCED = "0.1.8";

    WebView web;
    ProgressBar progress;
    TextView centerBtn;
    TextView zoomLabel;
    SharedPreferences prefs;
    String skin;
    ValueCallback<Uri[]> pendingFiles;
    SessionBars bars;
    FolderSync sync;
    PhoneApi phoneApi;
    Remote remote;
    ChatView chat;
    Notifier fgNotifier;

    void askNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9);
        }
    }

    void syncNotifier() {
        boolean paired = !prefs.getString("pair.key", "").isEmpty();
        if (paired && prefs.getBoolean("notifyBackground", false)) {
            fgNotifier.stop();
            NotifyService.start(this);
        } else {
            NotifyService.stop(this);
            if (paired && Notifier.appVisible) {
                fgNotifier.start(25);
            } else {
                fgNotifier.stop();
            }
        }
    }

    boolean handleOpen(Intent intent) {
        String id = intent == null ? null : intent.getStringExtra(Notifier.EXTRA_OPEN);
        if (id == null || !SessionBars.SESSION.matcher("/code/" + id).matches()) {
            return false;
        }
        android.util.Log.i("QuillOpen", id);
        web.loadUrl(HOME + "/" + id);
        return true;
    }
    Updater updater;
    Updater.Release pendingUpdate;
    TextView updateBanner;

    boolean trustedPage() {
        String u = web == null ? null : web.getUrl();
        return u != null && claudePage(Uri.parse(u));
    }

    static boolean claudePage(Uri u) {
        return inApp(u) && !githubAuth(u);
    }

    void toast(String m) {
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
    }

    void showUpdateBlocked() {
        String f = prefs.getString("update.failed", null);
        if (f == null) {
            return;
        }
        prefs.edit().remove("update.failed").apply();
        String detail = f.contains("|") ? f.substring(f.indexOf('|') + 1).trim() : "";
        if (pendingUpdate != null) {
            updateBanner.setText("Update " + pendingUpdate.version + " was blocked. Tap to retry.");
            updateBanner.setVisibility(View.VISIBLE);
        }
        accent(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Update blocked by your phone's security")
                .setMessage("Android stopped Quill from installing its update. This happens on every update for apps that don't come from a store:\n\n"
                        + "• Samsung Auto Blocker: Settings → Security and privacy → Auto Blocker → turn it off, tap the Quill update banner to retry, then turn Auto Blocker back on.\n\n"
                        + "• Google Play Protect: if it says the app is unsafe or unknown, tap \"More details\" → \"Install anyway\".\n\n"
                        + "The retry reuses the download and checks it again (same checksum and signing key) before installing."
                        + (detail.isEmpty() ? "" : "\n\nAndroid said: " + detail))
                .setPositiveButton("Open security settings", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton("Later", null)
                .show());
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
        if (name != null && remote == null && phoneApi != null && phoneApi.available()) {
            recreate();
            return;
        }
        if (name != null) {
            sync.refresh();
            askNotifyPermission();
            syncNotifier();
        }
        if (pairStatus != null) {
            pairStatus.run();
        }
    }
    TextView sideArrow;

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (bars != null) {
            bars.applyMode(false);
            inject();
        }
    }

    void openSession(String id) {
        if (remote != null) {
            remote.open(id);
            return;
        }
        if (web == null || !SessionBars.SESSION.matcher("/code/" + id).matches()) {
            return;
        }
        web.evaluateJavascript("window.__quill?(window.__quill.open('" + id + "'),1):0", v -> {
            if (!"1".equals(v) && web != null) {
                web.loadUrl(HOME + "/" + id);
            }
        });
    }

    String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    void loadHome() {
        if (remote == null && web != null) {
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
        phoneApi = new PhoneApi(prefs);
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
        column.addView(bars.usageStrip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));

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
        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(TEXT);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        zoomLabel.setTypeface(Typeface.DEFAULT_BOLD);
        zoomLabel.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable zb = new GradientDrawable();
        zb.setCornerRadius(dp(10));
        zb.setColor(0xE62F2F2C);
        zb.setStroke(dp(1), 0xFF444440);
        zoomLabel.setBackground(zb);
        zoomLabel.setContentDescription("Text size");
        zoomLabel.setAlpha(0f);
        setupWeb();
        android.widget.FrameLayout webFrame = new android.widget.FrameLayout(this);
        webFrame.addView(web, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        if (bars.remote) {
            chat = new ChatView(this);
            webFrame.addView(chat, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
        }
        webFrame.addView(zoomLabel, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        column.addView(webFrame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        bars.applyMode(false);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
        setContentView(root);
        getWindow().getDecorView().setBackgroundColor(SIDE);
        web.requestFocus();

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }

        if (state != null) {
            web.restoreState(state);
        }
        sync = new FolderSync(prefs, getContentResolver(), this::onFolders);
        fgNotifier = new Notifier(this);
        if (chat != null) {
            sync.usageListener = json -> { };
            remote = new Remote(phoneApi, chat, new Remote.Host() {
                @Override
                public void onSessions(String barsJson, String usageJson) {
                    bars.update(barsJson);
                    bars.setUsage(usageJson);
                }

                @Override
                public void onOpened(String id, org.json.JSONObject session) {
                    prefs.edit().putString("remote.last", id).apply();
                }

                @Override
                public void onConnection(String line) {
                    chat.setConnection(line);
                }

                @Override
                public void toast(String msg) {
                    MainActivity.this.toast(msg);
                }
            }, appVersion(), prefs.getString("remote.last", null));
        } else {
            sync.usageListener = json -> bars.setUsage(json);
            String cachedUsage = prefs.getString("usage.cache", null);
            if (cachedUsage != null) {
                bars.setUsage(cachedUsage);
            }
        }
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
        if (debuggable && getIntent() != null && getIntent().hasExtra("quill.testUpdateBlocked")) {
            prefs.edit().putString("update.failed", "2|" + getIntent().getStringExtra("quill.testUpdateBlocked")).apply();
        }
        if (debuggable && getIntent() != null) {
            String f = getIntent().getStringExtra("quill.updateFeed");
            if (f != null && testHost(Uri.parse(f))) {
                updater.feed = f;
                updater.allowTestHost = true;
                prefs.edit().putLong("update.checked", 0).apply();
            }
        }
        if (debuggable && getIntent() != null && getIntent().hasExtra("quill.testChat")) {
            testChat();
        }
        boolean pairing = handlePair(getIntent());
        setupPinch();
        if (debuggable && getIntent() != null && getIntent().hasExtra("quill.testPinch")) {
            float f = getIntent().getFloatExtra("quill.testPinch", 1f);
            setZoom(pinchZoom(prefs.getInt("zoom", 115), f));
        }
        String test = testUrl(getIntent());
        if (remote != null) {
            return;
        }
        if (test != null) {
            web.loadUrl(test);
        } else if (handleOpen(getIntent())) {
            return;
        } else if (state == null || web.getUrl() == null) {
            web.loadUrl(pairing ? startUrl(null) : startUrl(getIntent()));
        }
    }

    void testChat() {
        ChatView chat = new ChatView(this);
        java.util.List<ChatView.Item> items = new java.util.ArrayList<>();
        try (java.io.InputStream in = getAssets().open("chat-sample.json")) {
            org.json.JSONArray arr = new org.json.JSONArray(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                items.add(ChatView.Item.from(arr.getJSONObject(i)));
            }
        } catch (java.io.IOException | org.json.JSONException e) {
            toast("chat sample: " + e);
            return;
        }
        chat.actions = new ChatView.Actions() {
            @Override
            public void send(String text) {
                android.util.Log.w("QuillChat", "send " + text);
                ChatView.Item it = new ChatView.Item();
                it.seq = chat.items.size() + 100;
                it.kind = "user";
                it.text = text;
                it.toolName = it.summary = it.result = it.requestId = it.state = "";
                java.util.List<ChatView.Item> next = new java.util.ArrayList<>(chat.items);
                next.add(it);
                chat.setItems(next);
            }

            @Override
            public void answer(String requestId, String action, org.json.JSONObject extra) {
                android.util.Log.w("QuillChat", "answer " + requestId + " " + action + " " + extra);
                toast(action + " " + extra);
            }

            @Override
            public void interrupt() {
                android.util.Log.w("QuillChat", "interrupt");
            }
        };
        chat.setItems(items);
        chat.setBusy(false, "Opus 5.5 · idle");
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        d.setContentView(chat);
        d.show();
    }

    View bar() {
        android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        bar.setBackgroundColor(SIDE);
        bar.setPadding(dp(8), 0, dp(4), 0);

        LinearLayout left = new LinearLayout(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        sideArrow = new TextView(this);
        sideArrow.setContentDescription("Sidebar");
        sideArrow.setTextColor(ACCENT);
        sideArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        sideArrow.setTypeface(Typeface.DEFAULT_BOLD);
        sideArrow.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(7));
        ring.setColor(0x30D97757);
        sideArrow.setBackground(ring);
        sideArrow.setOnClickListener(v -> toggleSessions());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(42), dp(36));
        ap.setMargins(0, 0, dp(3), 0);
        left.addView(sideArrow, ap);
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
        left.addView(centerBtn);
        bar.addView(left, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icon);
        mid.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView title = new TextView(this);
        title.setText("Quill");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(8), 0, 0, 0);
        title.setSingleLine(true);
        mid.addView(title);
        mid.setOnClickListener(v -> toggleSessions());
        mid.setOnLongClickListener(v -> {
            web.loadUrl(HOME);
            return true;
        });
        bar.addView(mid, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(button("⚙", "Settings", v -> settings()));
        right.addView(button("⟳", "Reload", v -> web.reload()));
        bar.addView(right, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL));
        syncCenter();
        return bar;
    }

    void toggleSessions() {
        if (bars.vertical) {
            bars.toggleSide();
        } else {
            prefs.edit().putBoolean("barsHidden", !prefs.getBoolean("barsHidden", false)).apply();
            bars.render();
            bars.syncArrow();
        }
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
        box.setPadding(dp(20), dp(4), dp(20), dp(8));

        section(box, "Text", false);
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
        for (int i = 0; i < CENTER_KEYS.length; i++) {
            String key = CENTER_KEYS[i];
            checks[i] = check(CENTER_LABELS[i], centerOn(key));
            checks[i].setOnCheckedChangeListener((b, on) -> {
                prefs.edit().putBoolean(key, on).apply();
                syncAll.run();
                syncCenter();
                inject();
            });
            box.addView(indent(checks[i]));
        }
        all.setOnCheckedChangeListener((b, on) -> {
            if (syncing[0]) {
                return;
            }
            for (CheckBox c : checks) {
                c.setChecked(on);
            }
        });
        syncAll.run();
        TextView size = line("", TEXT);
        Runnable syncSize = () -> size.setText("Text size  " + prefs.getInt("zoom", 115) + "%  ·  pinch to change");
        syncSize.run();
        TextView reset = textButton("Reset", v -> {
            setZoom(115);
            syncSize.run();
        });
        box.addView(row(size, reset));

        section(box, "Tabs", true);
        box.addView(segmented(new String[][]{{"Auto", "auto"}, {"Horizontal", "horizontal"}, {"Vertical", "vertical"}}));
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
        CheckBox act = check("Sort folders by activity", prefs.getBoolean("sortActivity", false));
        act.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("sortActivity", on).apply());
        box.addView(act);

        section(box, "Folders from Quill", true);
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        Runnable syncStatus = () -> {
            status.removeAllViews();
            for (String l : sync.describe().split("\n")) {
                status.addView(line(l, MUTED));
            }
        };
        syncStatus.run();
        box.addView(status);
        pairStatus = syncStatus;
        box.addView(textButton("Scan pairing QR code", v -> {
            try {
                startActivityForResult(new Intent(this, PairScan.class), PAIR_SCAN);
            } catch (ActivityNotFoundException ignored) {
            }
        }));
        box.addView(textButton("Paste pairing link", v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            CharSequence t = cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0
                    ? cm.getPrimaryClip().getItemAt(0).coerceToText(this) : null;
            acceptPair(t == null ? null : Uri.parse(t.toString().trim()));
        }));
        box.addView(textButton("Choose synced file", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                startActivityForResult(i, SYNC_PICK);
            } catch (ActivityNotFoundException ignored) {
            }
        }));
        box.addView(textButton("Unpair", v -> {
            sync.unpair();
            syncNotifier();
            syncStatus.run();
        }));

        section(box, "Notifications", true);
        for (int i = 0; i < Notifier.TYPES.size(); i++) {
            String key = "notify." + Notifier.TYPES.get(i);
            CheckBox c = check(Notifier.LABELS[i], prefs.getBoolean(key, true));
            c.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
            box.addView(c);
        }
        CheckBox bg = check("Notify in background (local network)", prefs.getBoolean("notifyBackground", false));
        bg.setContentDescription("Notify in background");
        bg.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("notifyBackground", on).apply();
            if (on) {
                askNotifyPermission();
            }
            syncNotifier();
        });
        box.addView(bg);

        section(box, "Sign-in", true);
        box.addView(textButton("Open claude.ai links in Quill", v -> openLinkSettings()));
        box.addView(textButton("Paste sign-in link", v -> pasteSignInLink()));

        section(box, "About", true);
        box.addView(line("Quill for Android " + updater.current() + "  ·  Quill " + QUILL_SYNCED, MUTED));
        box.addView(textButton("Check for updates", v -> {
            toast("Checking for updates...");
            updater.check(true);
        }));

        accent(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Settings")
                .setView(scroll(box))
                .setPositiveButton("Done", null)
                .show());
    }

    void section(LinearLayout box, String title, boolean divider) {
        if (divider) {
            View d = new View(this);
            d.setBackgroundColor(0xFF3A3A37);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMargins(0, dp(10), 0, 0);
            box.addView(d, lp);
        }
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.04f);
        t.setSingleLine(true);
        t.setPadding(0, dp(10), 0, dp(2));
        box.addView(t);
    }

    TextView line(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMinHeight(dp(28));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    View indent(View v) {
        v.setPadding(v.getPaddingLeft() + dp(24), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom());
        LinearLayout l = new LinearLayout(this);
        l.setPadding(dp(4), 0, 0, 0);
        l.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }

    View row(View main, View end) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.addView(main, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        l.addView(end, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }

    View segmented(String[][] opts) {
        LinearLayout l = new LinearLayout(this);
        l.setPadding(0, dp(4), 0, dp(4));
        TextView[] items = new TextView[opts.length];
        Runnable sync = () -> {
            String cur = prefs.getString("tabMode", "auto");
            for (int i = 0; i < opts.length; i++) {
                boolean on = opts[i][1].equals(cur);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(8));
                g.setColor(on ? 0x55D97757 : 0x00000000);
                g.setStroke(dp(1), on ? ACCENT : 0xFF444440);
                items[i].setBackground(g);
                items[i].setTextColor(on ? TEXT : MUTED);
                items[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                items[i].setSelected(on);
            }
        };
        for (int i = 0; i < opts.length; i++) {
            TextView t = new TextView(this);
            t.setText(opts[i][0]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(40));
            String val = opts[i][1];
            t.setOnClickListener(v -> {
                prefs.edit().putString("tabMode", val).apply();
                sync.run();
                bars.applyMode(true);
            });
            items[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(i == 0 ? 0 : dp(3), 0, i == opts.length - 1 ? 0 : dp(3), 0);
            l.addView(t, lp);
        }
        sync.run();
        return l;
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
        if (u == null || !claudePage(u)) {
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
        b.setMinHeight(dp(42));
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
        c.setMinHeight(dp(42));
        c.setSingleLine(true);
        c.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return c;
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

    void setZoom(int z) {
        z = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
        if (z == prefs.getInt("zoom", 115) && web.getSettings().getTextZoom() == z) {
            return;
        }
        prefs.edit().putInt("zoom", z).apply();
        web.getSettings().setTextZoom(z);
        zoomLabel.setText(z + "%");
        zoomLabel.animate().cancel();
        zoomLabel.setAlpha(1f);
        zoomLabel.animate().alpha(0f).setStartDelay(900).setDuration(250).start();
    }

    static int pinchZoom(int start, float factor) {
        int z = Math.round(start * factor / 5f) * 5;
        return Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
    }

    int pinchStart;
    float pinchFactor = 1f;
    boolean pinching;

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    void setupPinch() {
        android.view.ScaleGestureDetector det = new android.view.ScaleGestureDetector(this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                pinching = true;
                pinchStart = prefs.getInt("zoom", 115);
                pinchFactor = 1f;
                return true;
            }

            @Override
            public boolean onScale(android.view.ScaleGestureDetector d) {
                pinchFactor *= d.getScaleFactor();
                setZoom(pinchZoom(pinchStart, pinchFactor));
                return true;
            }

            @Override
            public void onScaleEnd(android.view.ScaleGestureDetector d) {
                pinching = false;
            }
        });
        det.setQuickScaleEnabled(false);
        web.setOnTouchListener((v, e) -> {
            det.onTouchEvent(e);
            return pinching || e.getPointerCount() > 1;
        });
    }

    void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        web.addJavascriptInterface(new SessionBars.Bridge(bars), "QuillNative");
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
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
                inject(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject(url);
                syncSignInBanner(url);
                View f = getCurrentFocus();
                if (view == web && !(f instanceof android.widget.EditText)) {
                    web.requestFocus();
                }
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

    static final String[] GITHUB_AUTH = {"/login", "/session", "/sessions/", "/apps/", "/settings/installations", "/settings/connections/applications"};

    static boolean githubAuth(Uri u) {
        if (!"https".equals(u.getScheme()) || !"github.com".equals(u.getHost())) {
            return false;
        }
        String path = u.getPath() == null ? "" : u.getPath();
        for (String pre : GITHUB_AUTH) {
            if (path.equals(pre) || path.startsWith(pre.endsWith("/") ? pre : pre + "/") || (pre.equals("/login") && path.equals("/login"))) {
                if (pre.equals("/apps/") && !path.contains("/installations")) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    static boolean inApp(Uri u) {
        String host = u.getHost();
        if (testHost(u) || githubAuth(u)) {
            return true;
        }
        if (host == null || !"https".equals(u.getScheme())) {
            return false;
        }
        return host.equals("claude.ai") || host.endsWith(".claude.ai");
    }

    void inject() {
        inject(web == null ? null : web.getUrl());
    }

    void inject(String url) {
        if (skin == null || web == null || url == null || !claudePage(Uri.parse(url))) {
            return;
        }
        String p = "window.__quillPrefs={center:{chat:" + centerOn("centerText")
                + ",input:" + centerOn("centerInput")
                + ",status:" + centerOn("centerStatus")
                + "}"
                + ",drawer:false"
                + ",hwkb:" + hardwareKeyboard()
                + ",debugKeys:" + debuggable
                + ",edgeArrow:false"
                + ",folders:" + new org.json.JSONObject(folderMap)
                + ",order:" + new org.json.JSONArray(folderOrder) + "};";
        String guard = "(function(){var h=location.hostname;if(!(location.protocol==='https:'&&(h==='claude.ai'||/\\.claude\\.ai$/.test(h)||h==='claude.com'||/\\.claude\\.com$/.test(h)||h==='anthropic.com'||/\\.anthropic\\.com$/.test(h)))"
                + (debuggable ? "&&!(location.protocol==='http:'&&h==='127.0.0.1')" : "") + ")return;";
        web.evaluateJavascript(guard + p + skin + "})()", null);
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
        if (web == null) {
            moveTaskToBack(true);
        } else if (web.canGoBack()) {
            web.goBack();
        } else if (web.getUrl() == null || !claudePage(Uri.parse(web.getUrl()))) {
            web.loadUrl(HOME);
        } else {
            moveTaskToBack(true);
        }
    }

    boolean hardwareKeyboard() {
        android.content.res.Configuration c = getResources().getConfiguration();
        return c.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY
                && c.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.isCtrlPressed() && bars != null
                && e.getKeyCode() >= android.view.KeyEvent.KEYCODE_1 && e.getKeyCode() <= android.view.KeyEvent.KEYCODE_9
                && (hardwareKeyboard() || debuggable)) {
            bars.switchTab(e.getKeyCode() - android.view.KeyEvent.KEYCODE_0);
            return true;
        }
        return super.dispatchKeyEvent(e);
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
        if (handleOpen(intent)) {
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
        if (req == PAIR_SCAN) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                acceptPair(data.getData());
            }
            return;
        }
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
        if (remote != null) {
            remote.stop();
        }
        Notifier.appVisible = false;
        if (fgNotifier != null && !prefs.getBoolean("notifyBackground", false)) {
            fgNotifier.stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sync != null) {
            sync.start();
        }
        if (remote != null) {
            remote.start();
        }
        if (updater != null) {
            updater.checkIfDue();
        }
        showUpdateBlocked();
        Notifier.appVisible = true;
        if (fgNotifier != null) {
            syncNotifier();
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
