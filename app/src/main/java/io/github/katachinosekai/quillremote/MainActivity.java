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

    /** The kmem notification's tap: open the kmem screen on the Assistant tab (now, or when the connection says kmem is on). */
    boolean handleKmemIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(Notifier.EXTRA_OPEN_KMEM, false)) {
            return false;
        }
        intent.removeExtra(Notifier.EXTRA_OPEN_KMEM);
        if (remote != null && remote.kmemOn) {
            openKmem(KmemScreen.TAB_ASSISTANT);
        } else {
            pendingKmemTab = KmemScreen.TAB_ASSISTANT;
        }
        return true;
    }

    int pendingKmemTab = -1;

    boolean handleOpen(Intent intent) {
        if (handleKmemIntent(intent)) {
            return true;
        }
        String q = intent == null ? null : intent.getStringExtra(Notifier.EXTRA_OPEN_QUILL);
        if (q != null && remote != null && SessionBars.REMOTE_ID.matcher(q).matches()) {
            android.util.Log.w("QuillOpen", q);
            remote.open(q);
            return true;
        }
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

    KmemScreen kmemScreen;

    void openKmem() {
        openKmem(0);
    }

    void openKmem(int tab) {
        if (remote == null || !remote.kmemOn) {
            return;
        }
        if (kmemScreen != null) {
            kmemScreen.selectTab(tab);
            return;
        }
        kmemScreen = new KmemScreen(this, remote);
        kmemScreen.tab = tab;
        kmemScreen.show();
    }

    /** The kmem summary changed (button) or the stream said kmem changed (open screen). */
    void onKmemChanged() {
        if (pendingKmemTab >= 0 && remote != null && remote.kmemOn) {
            int t = pendingKmemTab;
            pendingKmemTab = -1;
            openKmem(t);
        }
        if (bars != null) {
            bars.render();
        }
        if (kmemScreen != null) {
            kmemScreen.refresh();
        }
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
        accent(Touch.show(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Update blocked by your phone's security")
                .setMessage("Android stopped Quill Android from installing its update. This happens on every update for apps that don't come from a store:\n\n"
                        + "• Samsung Auto Blocker: Settings → Security and privacy → Auto Blocker → turn it off, tap the Quill Android update banner to retry, then turn Auto Blocker back on.\n\n"
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
                ));
    }

    TextView notifyBanner;

    /** Banner when Android blocks Quill Android's notifications while paired: replies and approvals would go unseen (seen on a Galaxy A37, 2026-11-21). */
    View notifyBanner() {
        notifyBanner = new TextView(this);
        notifyBanner.setTextColor(TEXT);
        notifyBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        notifyBanner.setBackgroundColor(0xFF5A2E22);
        notifyBanner.setPaddingRelative(dp(14), dp(10), dp(14), dp(10));
        notifyBanner.setText("Notifications are off for Quill Android, so you won't see when a session finishes or needs you. Tap to turn them on. Long-press to hide this.");
        notifyBanner.setContentDescription("Notifications off banner");
        notifyBanner.setVisibility(View.GONE);
        notifyBanner.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !prefs.getBoolean("notifyHint.asked", false)) {
                prefs.edit().putBoolean("notifyHint.asked", true).apply();
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 9);
                return;
            }
            startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        notifyBanner.setOnLongClickListener(v -> {
            prefs.edit().putBoolean("notifyHint.hidden", true).apply();
            notifyBanner.setVisibility(View.GONE);
            toast("Hidden. Turn notifications on any time in Android Settings → Apps → Quill.");
            return true;
        });
        return notifyBanner;
    }

    static boolean notificationsBlocked(android.content.Context c) {
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        if (nm == null) {
            return false;
        }
        if (!nm.areNotificationsEnabled()) {
            return true;
        }
        android.app.NotificationChannel ch = nm.getNotificationChannel(Notifier.CHANNEL);
        return ch != null && ch.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE;
    }

    void syncNotifyBanner() {
        if (notifyBanner == null) {
            return;
        }
        boolean paired = !prefs.getString("pair.key", "").isEmpty();
        boolean show = paired && !prefs.getBoolean("notifyHint.hidden", false) && notificationsBlocked(this);
        notifyBanner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        syncNotifyBanner();
    }

    android.app.AlertDialog unlockDlg;

    /** Password prompt for Quill Desktop's paired-device lock (only when this device's Password switch is on there). */
    android.app.AlertDialog updateDlg;
    boolean updateAsking;
    /** Quill Desktop's questions were looked up since this start (after the first unlocked session list). */
    boolean deskAsked;

    int versionCode() {
        try {
            return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * After an update (user rule 2026-11-22): one dialog with this app's new entries that need the user (questions
     * with the recommended choice picked, one-time security notices) and Quill Desktop's pending questions (phone API
     * /phone/settings/questions, 0.1.25+). "Later" applies nothing and asks again at the next start.
     */
    void updateQuestions() {
        if (updateAsking || (updateDlg != null && updateDlg.isShowing())) {
            return;
        }
        int current = versionCode();
        if (debuggable && getIntent() != null && getIntent().hasExtra("quill.testPrevCode")) {
            prefs.edit().putInt(UpdateNotes.LAST, getIntent().getIntExtra("quill.testPrevCode", 0)).apply();
            getIntent().removeExtra("quill.testPrevCode");
        }
        UpdateNotes.firstRun(prefs, current);
        java.util.List<UpdateNotes.Entry> local = UpdateNotes.pending(prefs, current);
        updateAsking = true;
        PhoneApi api = phoneApi;
        new Thread(() -> {
            org.json.JSONArray desk = null;
            if (remote != null && api != null) {
                PhoneApi.Reply r = api.settingsQuestions();
                if (r.ok()) {
                    desk = r.body.optJSONArray("questions");
                }
            }
            org.json.JSONArray dq = desk;
            runOnUiThread(() -> {
                updateAsking = false;
                if (!isFinishing() && (!local.isEmpty() || (dq != null && dq.length() > 0))) {
                    showUpdateDialog(local, dq, current);
                }
            });
        }, "quill-update-questions").start();
    }

    void showUpdateDialog(java.util.List<UpdateNotes.Entry> local, org.json.JSONArray desk, int current) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(dp(20), dp(8), dp(20), 0);
        java.util.List<Runnable> apply = new java.util.ArrayList<>();
        for (UpdateNotes.Entry e : local) {
            TextView t = para(e.title, TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPaddingRelative(0, dp(10), 0, dp(2));
            col.addView(t);
            col.addView(para(e.text, MUTED));
        }
        // Texts wrap (para, never cut off); Quill Desktop's questions are capped and checked (security review f436bdd).
        int shown = 0;
        for (int i = 0; desk != null && i < desk.length() && shown < UpdateNotes.MAX_QUESTIONS; i++) {
            org.json.JSONObject q = desk.optJSONObject(i);
            if (q == null || q.optString("id").isEmpty()) {
                continue;
            }
            shown++;
            String id = q.optString("id");
            TextView t = para(UpdateNotes.clip(q.optString("title"), UpdateNotes.MAX_TITLE) + "  (Quill Desktop)", TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPaddingRelative(0, dp(10), 0, dp(2));
            col.addView(t);
            if (!q.optString("text").isEmpty()) {
                col.addView(para(UpdateNotes.clip(q.optString("text"), UpdateNotes.MAX_TEXT), MUTED));
            }
            if (!q.optString("question").isEmpty()) {
                TextView qq = para(UpdateNotes.clip(q.optString("question"), UpdateNotes.MAX_TEXT), TEXT);
                qq.setPaddingRelative(0, dp(6), 0, 0);
                col.addView(qq);
            }
            org.json.JSONArray ch = q.optJSONArray("choices");
            int n = ch == null ? 0 : ch.length();
            UpdateNotes.DeskAction act = UpdateNotes.deskAction(q.optString("kind"), n);
            if (act == UpdateNotes.DeskAction.NOTICE) {
                apply.add(() -> remote.answerSetting(id, null, null)); // a notice is answered without a choice
                continue;
            }
            if (act == UpdateNotes.DeskAction.DESKTOP_ONLY) {
                TextView d = para("Answer this one in Quill Desktop.", MUTED); // never answered without a real choice
                d.setPaddingRelative(0, dp(4), 0, 0);
                col.addView(d);
                continue;
            }
            int rec = UpdateNotes.recommended(q.optInt("recommended", 0), n);
            android.widget.RadioGroup g = new android.widget.RadioGroup(this);
            int[] picked = {rec};
            for (int k = 0; k < n; k++) {
                android.widget.RadioButton rb = new android.widget.RadioButton(this);
                String label = UpdateNotes.clip(ch.optString(k), UpdateNotes.MAX_CHOICE);
                rb.setText(label + (k == rec && !label.contains("(recommended)") ? "  (recommended)" : ""));
                rb.setTextColor(TEXT);
                rb.setId(View.generateViewId());
                g.addView(rb);
                rb.setChecked(k == rec);
                int kk = k;
                rb.setOnCheckedChangeListener((b, on) -> {
                    if (on) {
                        picked[0] = kk;
                    }
                });
            }
            col.addView(g);
            apply.add(() -> remote.answerSetting(id, picked[0], m -> {
                if (m != null) {
                    toast(m);
                }
            }));
        }
        updateDlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("After the update")
                .setView(scroll(col))
                .setPositiveButton("OK", (dlg, w) -> {
                    for (Runnable r : apply) {
                        r.run();
                    }
                    UpdateNotes.finished(prefs, local, current);
                })
                .setNegativeButton("Later", null) // applies nothing; asks again at the next start
                .setCancelable(false)
                .create();
        Touch.watch(updateDlg).show();
        accent(updateDlg);
    }

    /** The phone's own unlock prompt (fingerprint or screen lock), for the home-network key (LanKey). */
    void askScreenLock(java.util.function.Consumer<Boolean> done) {
        if (isFinishing()) {
            done.accept(false);
            return;
        }
        android.hardware.biometrics.BiometricPrompt p = new android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Unlock Quill Desktop")
                .setSubtitle("On your home network, this phone's screen lock replaces Quill Desktop's password.")
                .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        p.authenticate(new android.os.CancellationSignal(), getMainExecutor(), new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                done.accept(true);
            }

            @Override
            public void onAuthenticationError(int code, CharSequence err) {
                done.accept(false);
            }
        });
    }

    void unlockDialog() {
        if (remote == null || isFinishing() || (unlockDlg != null && unlockDlg.isShowing())) {
            return;
        }
        android.widget.EditText pw = new android.widget.EditText(this);
        // setSingleLine swaps the transformation method, so it must come before the password input type (else the text shows in plain).
        pw.setSingleLine(true);
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        pw.setTypeface(Typeface.DEFAULT);
        pw.setHint("Device password");
        pw.setContentDescription("Device password");
        CheckBox show = check("Show password", false);
        show.setOnCheckedChangeListener((v, on) -> {
            int at = pw.getSelectionEnd();
            pw.setTransformationMethod(on ? null : android.text.method.PasswordTransformationMethod.getInstance());
            pw.setSelection(Math.max(0, Math.min(at, pw.length())));
        });
        TextView msg = label("");
        // Why the home-network unlock didn't work this time (e.g. not on the home network, screen lock cancelled).
        String lanNote = remote.lanNote;
        if (!lanNote.isEmpty()) {
            msg.setText(lanNote);
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(dp(20), dp(8), dp(20), 0);
        col.addView(label("Quill Desktop asks for its device password before it shows sessions here."));
        col.addView(pw);
        col.addView(show);
        col.addView(msg);
        unlockDlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Unlock Quill Desktop")
                .setView(col)
                .setPositiveButton("Unlock", null)
                .setNegativeButton("Cancel", null)
                .setCancelable(false)
                .create();
        unlockDlg.setOnShowListener(d -> unlockDlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p = pw.getText().toString();
            if (p.isEmpty()) {
                return;
            }
            msg.setText("Unlocking…");
            remote.unlock(p, err -> {
                if (err.isEmpty()) {
                    unlockDlg.dismiss();
                } else {
                    msg.setText(err);
                    pw.selectAll();
                }
            });
        }));
        unlockDlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        unlockDlg.getWindow().setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        Touch.watch(unlockDlg).show();
        pw.requestFocus();
    }

    float swX, swY;
    long swT;
    boolean swOn;
    /** A gesture that started on or near the list edge goes to the edge only (no row long-press, any speed). */
    boolean edgeGesture;
    /** Hold-to-grab anywhere (user 2026-11-21): hold still HOLD_MS, then a sideways move pulls the list edge to the finger. */
    static final int HOLD_MS = 450;
    boolean holdArmed;
    boolean holdDragging;
    final Runnable holdArm = () -> {
        holdArmed = true;
        swOn = false;
        if (bars != null && bars.handle != null) {
            bars.handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    };

    /** Swipe sideways anywhere (user 2026-11-21): right opens the session list, left closes it. The edge strip still works on its own. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (bars != null && bars.vertical && bars.edgeTouch != null) {
            int am = e.getActionMasked();
            if (am == android.view.MotionEvent.ACTION_DOWN) {
                swX = e.getRawX();
                swY = e.getRawY();
                swT = e.getEventTime();
                edgeGesture = bars.nearEdge(e.getRawX(), e.getRawY());
                holdArmed = false;
                holdDragging = false;
                bars.handle.removeCallbacks(holdArm);
                // One rule for the edge and anywhere else (user 2026-11-21): hold ~0.45 s, then drag.
                bars.handle.postDelayed(holdArm, HOLD_MS);
            } else if (am == android.view.MotionEvent.ACTION_POINTER_DOWN) {
                bars.handle.removeCallbacks(holdArm);
                holdArmed = false;
            } else if (am == android.view.MotionEvent.ACTION_MOVE && !holdArmed && !holdDragging
                    && Math.hypot(e.getRawX() - swX, e.getRawY() - swY) > android.view.ViewConfiguration.get(this).getScaledTouchSlop()) {
                // Moved before the hold time: a normal scroll or swipe, not a grab.
                bars.handle.removeCallbacks(holdArm);
            }
            if (holdArmed && !holdDragging && am == android.view.MotionEvent.ACTION_MOVE
                    && Math.abs(e.getRawX() - swX) > android.view.ViewConfiguration.get(this).getScaledTouchSlop()
                    && Math.abs(e.getRawX() - swX) > Math.abs(e.getRawY() - swY)) {
                // Armed and moving sideways: cancel whatever the views underneath started, and drag the edge from here.
                android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
                cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(e);
                down.setAction(android.view.MotionEvent.ACTION_DOWN);
                bars.edgeTouch.onTouch(bars.handle, down);
                down.recycle();
                holdDragging = true;
            }
            if (holdDragging) {
                bars.edgeTouch.onTouch(bars.handle, e);
                if (am == android.view.MotionEvent.ACTION_UP || am == android.view.MotionEvent.ACTION_CANCEL) {
                    holdDragging = false;
                    holdArmed = false;
                }
                return true;
            }
            if (am == android.view.MotionEvent.ACTION_UP || am == android.view.MotionEvent.ACTION_CANCEL) {
                bars.handle.removeCallbacks(holdArm);
                holdArmed = false;
            }
            if (edgeGesture) {
                // On the edge: a quick tap opens/closes the list; nothing underneath (rows) gets the touch.
                if (am == android.view.MotionEvent.ACTION_UP && !holdArmed
                        && Math.hypot(e.getRawX() - swX, e.getRawY() - swY) <= android.view.ViewConfiguration.get(this).getScaledTouchSlop()) {
                    bars.toggleSide();
                }
                if (am == android.view.MotionEvent.ACTION_UP || am == android.view.MotionEvent.ACTION_CANCEL) {
                    edgeGesture = false;
                    holdArmed = false;
                    bars.handle.removeCallbacks(holdArm);
                }
                return true;
            }
        }
        if (bars != null && bars.vertical) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    swX = e.getRawX();
                    swY = e.getRawY();
                    swT = e.getEventTime();
                    android.graphics.Rect r = new android.graphics.Rect();
                    swOn = bars.handle == null || !bars.handle.getGlobalVisibleRect(r) || !r.contains((int) swX, (int) swY);
                    break;
                }
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    swOn = false;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    if (swOn) {
                        float dx = e.getRawX() - swX;
                        float dy = e.getRawY() - swY;
                        if (Math.abs(dx) >= dp(90) && Math.abs(dy) < Math.abs(dx) * 0.5f && e.getEventTime() - swT < 1500) {
                            boolean open = prefs.getBoolean("sideOpen", true);
                            if (dx > 0 != open) {
                                bars.toggleSide();
                            }
                        }
                    }
                    swOn = false;
                    break;
                default:
                    break;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();

        if (remote != null) {
            remote.touch();
        }
    }

    View updateBanner() {
        updateBanner = new TextView(this);
        updateBanner.setTextColor(0xFF1F1F1D);
        updateBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        updateBanner.setTypeface(Typeface.DEFAULT_BOLD);
        updateBanner.setBackgroundColor(ACCENT);
        updateBanner.setPaddingRelative(dp(14), dp(10), dp(14), dp(10));
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
        intent.setData(null);
        acceptPair(data, false);
        return true;
    }

    android.app.AlertDialog pairDlg;

    /**
     * A pairing link or code (security review f436bdd): a quillpair:// link can come from any web page, app or chat
     * link, so it never pairs silently. It shows what it would pair with (name, address, certificate) and pairs only
     * after "Pair"; only an in-app QR scan on an unpaired phone skips the question (the user just pointed the camera
     * at Quill Desktop's code).
     */
    void acceptPair(Uri data, boolean scanned) {
        FolderSync.Pairing p = FolderSync.parse(data);
        if (p == null) {
            toast("Not a valid Quill Desktop pairing link");
            return;
        }
        boolean paired = FolderSync.paired(prefs);
        if (scanned && !paired) {
            applyPair(p);
            return;
        }
        if (pairDlg != null && pairDlg.isShowing()) {
            pairDlg.dismiss();
        }
        TextView msg = para(FolderSync.confirmText(p, paired ? prefs.getString("pair.name", "") : null), TEXT);
        msg.setPaddingRelative(dp(24), dp(8), dp(24), 0);
        pairDlg = Touch.show(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(paired ? "Replace the pairing?" : "Pair with Quill Desktop?")
                .setView(scroll(msg))
                .setPositiveButton(paired ? "Replace" : "Pair", (d, w) -> applyPair(p))
                .setNegativeButton("Cancel", null)
                );
        accent(pairDlg);
    }

    void applyPair(FolderSync.Pairing p) {
        String name = FolderSync.apply(prefs, p);
        android.widget.Toast.makeText(this, "Paired with " + name, android.widget.Toast.LENGTH_LONG).show();
        if (name != null && phoneApi != null && (remote != null) != phoneApi.available()) {
            recreate();
            return;
        }
        if (name != null && remote != null) {
            remote.restart();
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
        syncTitle();
        if (kmemScreen != null) {
            kmemScreen.layout();
        }
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

    /** Launcher "New session" shortcut and Share to Quill (ACTION_SEND text). True when the intent was one of those. */
    boolean handleHome(Intent i) {
        if (i == null) {
            return false;
        }
        if (i.getBooleanExtra(Home.EXTRA_NEW, false)) {
            i.removeExtra(Home.EXTRA_NEW);
            if (remote == null) {
                toast("New sessions from here need Quill Android paired with Quill Desktop (⚙ → Scan pairing QR code).");
                return false;
            }
            newSessionDialog();
            return true;
        }
        if (!Intent.ACTION_SEND.equals(i.getAction())) {
            return false;
        }
        String text = shared(i);
        i.setAction(Intent.ACTION_MAIN);
        if (text.isEmpty()) {
            toast("Quill Android can only take shared text and links.");
            return true;
        }
        if (remote == null) {
            android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Shared text", text));
            toast("Copied. Paste it into the chat.");
            return false;
        }
        shareDialog(text);
        return true;
    }

    /** Shared text: subject (if not already in the text) + text, at most Quill Desktop's 32 KiB message limit. */
    static String shared(Intent i) {
        CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String text = t == null ? "" : t.toString().trim();
        String subj = i.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subj != null && !subj.trim().isEmpty() && !text.contains(subj.trim())) {
            text = text.isEmpty() ? subj.trim() : subj.trim() + "\n" + text;
        }
        while (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32 * 1024) {
            text = Cut.head(text, text.length() * 9 / 10);
        }
        return text;
    }

    /** Share to Quill: pick a session (or a new one); the text lands in the message box to edit and send. */
    void shareDialog(String text) {
        java.util.List<org.json.JSONObject> list = new java.util.ArrayList<>();
        synchronized (remote) {
            list.addAll(remote.sessions);
        }
        if (list.isEmpty()) {
            list.addAll(Home.cached(this));
        }
        String[] names = new String[list.size() + 1];
        names[0] = "New session…";
        for (int k = 0; k < list.size(); k++) {
            org.json.JSONObject s = list.get(k);
            String t = s.optString("title").trim();
            String f = s.optString("project", s.optString("group", s.optString("folder", "")));
            names[k + 1] = (t.isEmpty() ? "Session" : t) + (f.isEmpty() ? "" : "  ·  " + f);
        }
        accent(Touch.show(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Send to…")
                .setItems(names, (d, w) -> {
                    if (w > 0) {
                        remote.open(list.get(w - 1).optString("id"));
                    }
                    chat.draft(text);
                    if (w == 0) {
                        newSessionDialog();
                    }
                })
                .setNegativeButton("Cancel", null)
                ));
    }

    String openTarget(Intent i) {
        String q = i == null ? null : i.getStringExtra(Notifier.EXTRA_OPEN_QUILL);
        return q != null && SessionBars.REMOTE_ID.matcher(q).matches() ? q : prefs.getString("remote.last", null);
    }

    void newSessionDialog() {
        if (remote == null) {
            return;
        }
        remote.folders(folders -> remote.handoffs(handoffs -> newSessionDialog(folders, handoffs)));
    }

    void newSessionDialog(org.json.JSONArray folders, org.json.JSONArray handoffs) {
        {
            if (folders.length() == 0) {
                toast("Quill Desktop has no folders to start a session in.");
                return;
            }
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPaddingRelative(dp(20), dp(8), dp(20), 0);
            boolean kmem = handoffs != null;
            android.widget.EditText name = new android.widget.EditText(this);
            TextView preview = label("");
            String[] resume = {null};
            android.app.AlertDialog[] dlg = {null};
            if (kmem) {
                col.addView(label("Name"));
                name.setHint("Name, e.g. Quill Android");
                name.setSingleLine(true);
                name.setContentDescription("Session name");
                col.addView(name);
                col.addView(preview);
                Runnable[] pending = {null};
                name.addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int st, int c, int a) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int st, int b, int c) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable e) {
                        Touch.ping();
                        if (pending[0] != null) {
                            preview.removeCallbacks(pending[0]);
                        }
                        String t = e.toString().trim();
                        if (t.isEmpty()) {
                            preview.setText("");
                            return;
                        }
                        pending[0] = () -> remote.slug(t, slug -> {
                            if (t.equals(name.getText().toString().trim())) {
                                preview.setText(slug.isEmpty() ? "" : "kmem name: " + slug);
                            }
                        });
                        preview.postDelayed(pending[0], 300);
                    }
                });
                col.addView(label("Or resume a kmem handoff"));
                android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
                if (handoffs.length() == 0) {
                    TextView none = label("No saved handoffs yet.");
                    none.setPaddingRelative(0, 0, 0, dp(4));
                    col.addView(none);
                }
                for (int i = 0; i < handoffs.length(); i++) {
                    org.json.JSONObject h = handoffs.optJSONObject(i);
                    if (h == null) {
                        continue;
                    }
                    String hn = h.optString("name");
                    String saved = h.optLong("savedMs") > 0 ? " · saved " + TimeFmt.format("MMMd", h.optLong("savedMs")) : "";
                    org.json.JSONObject open = h.optJSONObject("openIn");
                    if (open != null) {
                        TextView t = new TextView(this);
                        t.setText(hn + saved + " · open in " + open.optString("title") + " ›");
                        t.setTextColor(MUTED);
                        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                        t.setPaddingRelative(dp(8), dp(10), dp(8), dp(10));
                        String oid = open.optString("id");
                        t.setOnClickListener(v -> {
                            if (dlg[0] != null) {
                                dlg[0].dismiss();
                            }
                            remote.open(oid);
                        });
                        col.addView(t);
                        continue;
                    }
                    android.widget.RadioButton rb = new android.widget.RadioButton(this);
                    rb.setText(hn + saved);
                    rb.setOnLongClickListener(v -> {
                        handoffMenu(hn, dlg[0]);
                        return true;
                    });
                    rb.setId(View.generateViewId());
                    rb.setOnClickListener(v -> {
                        if (hn.equals(resume[0])) {
                            resume[0] = null;
                            rg.clearCheck();
                        } else {
                            resume[0] = hn;
                        }
                        name.setEnabled(resume[0] == null);
                    });
                    rg.addView(rb);
                }
                if (rg.getChildCount() > 0) {
                    col.addView(rg);
                }
            }
            android.widget.Spinner folder = spinner(col, "Folder", names(folders, "name"));
            android.widget.RadioGroup backend = new android.widget.RadioGroup(this);
            backend.setOrientation(LinearLayout.HORIZONTAL);
            for (String b : new String[]{"online", "local"}) {
                android.widget.RadioButton rb = new android.widget.RadioButton(this);
                rb.setText(b.equals("online") ? "Online" : "Local");
                rb.setTag(b);
                rb.setId(View.generateViewId());
                rb.setEnabled(b.equals("online") || remote.models.optJSONArray("local") != null && remote.models.optJSONArray("local").length() > 0);
                backend.addView(rb);
                if (b.equals("online")) {
                    backend.check(rb.getId());
                }
            }
            col.addView(label("Backend"));
            col.addView(backend);
            android.widget.Spinner model = spinner(col, "Model", list(remote.models.optJSONArray("online")));
            android.widget.Spinner mode = spinner(col, "Mode", list(remote.modes.optJSONArray("online")));
            backend.setOnCheckedChangeListener((g, id) -> {
                String b = String.valueOf(g.findViewById(id).getTag());
                fill(model, list(remote.models.optJSONArray(b)));
                fill(mode, list(remote.modes.optJSONArray(b)));
            });
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            sv.addView(col);
            dlg[0] = new android.app.AlertDialog.Builder(this)
                    .setTitle("New session")
                    .setView(sv)
                    .setPositiveButton("Start", (d, w) -> {
                        String b = String.valueOf(backend.findViewById(backend.getCheckedRadioButtonId()).getTag());
                        org.json.JSONObject f = folders.optJSONObject(folder.getSelectedItemPosition());
                        if (f == null || model.getSelectedItem() == null || mode.getSelectedItem() == null) {
                            return;
                        }
                        org.json.JSONObject start = null;
                        if (kmem) {
                            start = resume[0] != null
                                    ? PhoneApi.obj("kmem", "resume", "name", resume[0])
                                    : PhoneApi.obj("kmem", "new", "title", name.getText().toString().trim());
                        }
                        remote.newSession(f.optString("path"), b, String.valueOf(model.getSelectedItem()), String.valueOf(mode.getSelectedItem()), start);
                    })
                    .setNegativeButton("Cancel", null)
                    .create();
            Touch.watch(dlg[0]).show();
        }
    }

    /** Long-press on a saved kmem handoff: resume it or move it to the kmem recycle bin (emptying stays on Quill Desktop). */
    void handoffMenu(String name, android.app.AlertDialog parent) {
        SessionBars.Menu m = new SessionBars.Menu();
        m.add("Resume", () -> {
            if (parent != null) {
                parent.dismiss();
            }
            remote.handoffAction(name, "resume", null);
        });
        m.add("Delete handoff...", () -> Touch.show(new android.app.AlertDialog.Builder(this)
                .setTitle("Delete handoff?")
                .setMessage("\"" + name + "\" moves to the kmem recycle bin. You can restore it in Quill Desktop.")
                .setPositiveButton("Delete", (d, w) -> remote.handoffAction(name, "delete", () -> {
                    if (parent != null) {
                        parent.dismiss();
                    }
                    toast("Moved " + name + " to the kmem recycle bin.");
                }))
                .setNegativeButton("Cancel", null)
                ));
        bars.showMenu(name, m);
    }

    TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(MUTED);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setPaddingRelative(0, dp(12), 0, dp(2));
        return v;
    }

    android.widget.Spinner spinner(LinearLayout col, String title, java.util.List<String> items) {
        col.addView(label(title));
        android.widget.Spinner sp = new android.widget.Spinner(this);
        fill(sp, items);
        col.addView(sp);
        return sp;
    }

    void fill(android.widget.Spinner sp, java.util.List<String> items) {
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
    }

    static java.util.List<String> list(org.json.JSONArray a) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            out.add(a.optString(i));
        }
        return out;
    }

    static java.util.List<String> names(org.json.JSONArray a, String key) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            out.add(o == null ? "" : o.optString(key));
        }
        return out;
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
        TimeFmt.h24 = android.text.format.DateFormat.is24HourFormat(this);
        applyOrientation();
        debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        skin = asset("quill.js");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        phoneApi = new PhoneApi(prefs);
        root.addView(bar(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        sessionBar = new TextView(this);
        sessionBar.setTextColor(TEXT);
        sessionBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sessionBar.setTypeface(Typeface.DEFAULT_BOLD);
        sessionBar.setSingleLine(true);
        sessionBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sessionBar.setGravity(Gravity.CENTER);
        sessionBar.setPaddingRelative(dp(16), 0, dp(16), 0);
        sessionBar.setBackgroundColor(SIDE);
        sessionBar.setVisibility(View.GONE);
        sessionBar.setOnClickListener(v -> toggleSessions());
        root.addView(sessionBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
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
        column.addView(bars.usageStrip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(SIDE));
        column.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        column.addView(updateBanner(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(notifyBanner(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(signInBanner(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        web.setBackgroundColor(BG);
        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(TEXT);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        zoomLabel.setTypeface(Typeface.DEFAULT_BOLD);
        zoomLabel.setPaddingRelative(dp(16), dp(8), dp(16), dp(8));
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
            chat.showTimes = prefs.getBoolean("messageTimes", true);
            chat.animate = prefs.getBoolean("animateText", true);
            chat.animateSpeed = StreamText.speed(prefs.getString("animateSpeed", "normal"));
            chat.drafts = new Drafts(prefs);
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

        if (state != null && !bars.remote) {
            web.restoreState(state);
        }
        Net.init(this);
        Touch.sink = () -> {
            if (remote != null) {
                remote.touch();
            }
        };
        sync = new FolderSync(prefs, getContentResolver(), this::onFolders);
        fgNotifier = new Notifier(this);
        if (chat != null) {
            sync.usageListener = json -> { };
            remote = new Remote(phoneApi, chat, new Remote.Host() {
                @Override
                public void onSessions(String barsJson, String usageJson) {
                    if (!deskAsked) {
                        deskAsked = true; // connected and unlocked: Quill Desktop's pending questions can be fetched now
                        updateQuestions();
                    }
                    boolean typing = chat.box.hasFocus();
                    bars.update(barsJson);
                    bars.setUsage(usageJson);
                    if (typing && !chat.box.hasFocus()) {
                        chat.box.requestFocus();
                    }
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

                @Override
                public void onUnsupported() {
                    prefs.edit().putBoolean("phone.unsupported", true).apply();
                    recreate();
                }

                @Override
                public void onLocked() {
                    unlockDialog();
                }

                @Override
                public void screenLock(java.util.function.Consumer<Boolean> done) {
                    askScreenLock(done);
                }

                @Override
                public void onUnlocked() {
                    if (unlockDlg != null && unlockDlg.isShowing()) {
                        unlockDlg.dismiss();
                    }
                }
            }, appVersion(), openTarget(getIntent()));
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
                updateBanner.setText("Quill Android " + r.version + " is available. Tap to update.");
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
        if (handleHome(getIntent())) {
            return;
        }
        if (remote != null) {
            handleKmemIntent(getIntent());
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
            org.json.JSONArray arr = new org.json.JSONArray(PhoneApi.read(in));
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

            @Override
            public void queueOp(String op, int index, String text) {
                android.util.Log.w("QuillChat", "queue " + op + " " + index + " " + text);
            }

            @Override
            public void resume() {
                android.util.Log.w("QuillChat", "resume");
            }
        };
        chat.setItems(items);
        chat.setBusy(false, "Opus 5.5 · idle");
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar);
        d.setContentView(chat);
        Touch.watch(d).show();
    }

    View bar() {
        android.widget.FrameLayout bar = new android.widget.FrameLayout(this);
        bar.setBackgroundColor(SIDE);
        bar.setPaddingRelative(dp(8), 0, dp(4), 0);

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
        ap.setMarginEnd(dp(3));
        // The arrow is gone (user 2026-11-21): tap or drag the session list's edge strip instead.
        sideArrow.setVisibility(View.GONE);
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
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setText("Quill");
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPaddingRelative(dp(8), 0, 0, 0);
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
        if (phoneApi != null && phoneApi.available()) {
            right.addView(button("+", "New session", v -> newSessionDialog()));
            right.addView(button("⟳", "Reconnect", v -> {
                if (remote != null) {
                    remote.restart();
                }
            }));
        } else {
            right.addView(button("⟳", "Reload", v -> web.reload()));
        }
        bar.addView(right, new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL));
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            // A session title may be long: let it use the space between the button groups (centered), ellipsized.
            int half = Math.min(bar.getWidth() / 2 - left.getRight(), right.getLeft() - bar.getWidth() / 2);
            int maxTitle = Math.max(dp(40), 2 * half - dp(26) - dp(16));
            if (title.getMaxWidth() != maxTitle) {
                title.post(() -> title.setMaxWidth(maxTitle));
            }
            int need = mid.getMeasuredWidth();
            int room = right.getLeft() - left.getRight();
            int center = (bar.getWidth() - need) / 2;
            boolean fits = center >= left.getRight() && center + need <= right.getLeft() && room > need;
            int vis = fits ? View.VISIBLE : View.INVISIBLE;
            if (mid.getVisibility() != vis) {
                mid.post(() -> mid.setVisibility(vis));
            }
        });
        syncCenter();
        return bar;
    }

    void toggleSessions() {
        if (bars.vertical) {
            bars.toggleSide();
        } else {
            prefs.edit().putBoolean("barsHidden", !prefs.getBoolean("barsHidden", false)).apply();
            UpdateNotes.userSet(prefs, "barsHidden");
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
        box.setPaddingRelative(dp(20), dp(4), dp(20), dp(8));

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
                UpdateNotes.userSet(prefs, key);
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
        CheckBox times = check("Message times", prefs.getBoolean("messageTimes", true));
        times.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("messageTimes", on).apply();
            UpdateNotes.userSet(prefs, "messageTimes");
            if (chat != null) {
                chat.setShowTimes(on);
            }
        });
        box.addView(times);
        CheckBox anim = check("Animate new text", prefs.getBoolean("animateText", true));
        anim.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("animateText", on).apply();
            UpdateNotes.userSet(prefs, "animateText");
            if (chat != null) {
                chat.animate = on;
            }
        });
        box.addView(anim);
        TextView speedLine = line("", TEXT);
        Runnable syncSpeed = () -> speedLine.setText("Text animation speed  ·  " + StreamText.speedLabel(prefs.getString("animateSpeed", "normal")));
        syncSpeed.run();
        TextView speedBtn = textButton("Change", v -> {
            String next = StreamText.nextSpeed(prefs.getString("animateSpeed", "normal"));
            prefs.edit().putString("animateSpeed", next).apply();
            UpdateNotes.userSet(prefs, "animateSpeed");
            if (chat != null) {
                chat.animateSpeed = next;
            }
            syncSpeed.run();
        });
        box.addView(row(speedLine, speedBtn));
        TextView size = line("", TEXT);
        Runnable syncSize = () -> size.setText("Text size  " + prefs.getInt("zoom", 115) + "%  ·  pinch to change");
        syncSize.run();
        TextView reset = textButton("Reset", v -> {
            setZoom(115);
            syncSize.run();
        });
        box.addView(row(size, reset));

        section(box, "Folders from Quill", true);
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        Runnable syncStatus = () -> {
            status.removeAllViews();
            for (String l : sync.describe().split("\n")) {
                if (remote != null && l.startsWith("Updated ")) {
                    l = remote.lastOk == 0 ? "Connecting to Quill Desktop…" : "Live from Quill Desktop  ·  last answer " + FolderSync.ago(System.currentTimeMillis() - remote.lastOk);
                }
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
            acceptPair(t == null ? null : Uri.parse(t.toString().trim()), false);
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

        // Quill Desktop's session switches (Settings → General there), shown when the phone API has them.
        CheckBox[] sessionChecks = new CheckBox[2];
        if (remote != null) {
            LinearLayout sessionBox = new LinearLayout(this);
            sessionBox.setOrientation(LinearLayout.VERTICAL);
            section(sessionBox, "Sessions (Quill Desktop)", true);
            String[] keys = {"buildGate", "coordinator"};
            String[] labels = {"Ask before building", "Coordinate with subagents"};
            String[] hints = {"Sessions don't build after each change; they ask: more features, debug, secure, or build?",
                    "The session you chat with stays free and hands each feature or fix to a background helper."};
            boolean[] syncingS = {false};
            for (int k = 0; k < 2; k++) {
                String key = keys[k];
                CheckBox c = check(labels[k], false);
                c.setContentDescription(labels[k]);
                c.setOnCheckedChangeListener((v, on) -> {
                    if (syncingS[0]) {
                        return;
                    }
                    v.setEnabled(false);
                    remote.setSetting(key, on, msg -> {
                        v.setEnabled(true);
                        if (msg != null) {
                            toast(msg);
                            org.json.JSONObject st = remote.settings;
                            syncingS[0] = true;
                            v.setChecked(st != null && st.optBoolean(key, !on));
                            syncingS[0] = false;
                        }
                    });
                });
                sessionChecks[k] = c;
                sessionBox.addView(c);
                TextView h = line(hints[k], MUTED);
                h.setPaddingRelative(dp(32), 0, 0, dp(4));
                sessionBox.addView(h);
            }
            Runnable syncSessions = () -> {
                org.json.JSONObject st = remote.settings;
                sessionBox.setVisibility(st == null ? View.GONE : View.VISIBLE);
                if (st != null) {
                    syncingS[0] = true;
                    for (int k = 0; k < 2; k++) {
                        sessionChecks[k].setChecked(st.optBoolean(keys[k], true));
                    }
                    syncingS[0] = false;
                }
            };
            syncSessions.run();
            remote.onSettings = syncSessions;
            box.addView(sessionBox);

            // Home-network unlock (switched on per device in Quill Desktop → Devices, Linux only there).
            section(box, "Home network", true);
            box.addView(line(LanKey.exists()
                    ? "This phone has its home-network key. When Quill Desktop's Devices switch for it is on, no password is needed on its home network (again every 30 days)."
                    : "Unlock with the password once, then switch \"No password on this home network\" on for this phone in Quill Desktop → Devices.", MUTED));
            CheckBox screenLock = check("Require this phone's screen lock", prefs.getBoolean("lanScreenLock", true));
            screenLock.setContentDescription("Require this phone's screen lock");
            screenLock.setOnCheckedChangeListener((v, on) -> {
                prefs.edit().putBoolean("lanScreenLock", on).apply();
                UpdateNotes.userSet(prefs, "lanScreenLock");
                // A new key is needed for the new setting; Quill Desktop only takes it from a password-unlocked session.
                remote.registerKey(m -> toast(m == null ? "Home-network key updated." : m));
            });
            box.addView(screenLock);
        }

        section(box, "Notifications", true);
        for (int i = 0; i < Notifier.TYPES.size(); i++) {
            String key = "notify." + Notifier.TYPES.get(i);
            CheckBox c = check(Notifier.LABELS[i], prefs.getBoolean(key, true));
            c.setOnCheckedChangeListener((v, on) -> {
                prefs.edit().putBoolean(key, on).apply();
                UpdateNotes.userSet(prefs, key);
            });
            box.addView(c);
        }
        CheckBox bg = check("Notify in background (local network)", prefs.getBoolean("notifyBackground", false));
        bg.setContentDescription("Notify in background");
        bg.setOnCheckedChangeListener((v, on) -> {
            prefs.edit().putBoolean("notifyBackground", on).apply();
            UpdateNotes.userSet(prefs, "notifyBackground");
            if (on) {
                askNotifyPermission();
            }
            syncNotifier();
        });
        box.addView(bg);

        section(box, "Sign-in", true);
        box.addView(textButton("Open claude.ai links in Quill Android", v -> openLinkSettings()));
        box.addView(textButton("Paste sign-in link", v -> pasteSignInLink()));

        section(box, "About", true);
        String desk = remote != null ? remote.deskVersion : "";
        box.addView(line("Quill Android " + updater.current() + (desk.isEmpty() || "dev".equals(desk) ? "" : "  ·  Quill Desktop " + desk), MUTED));
        box.addView(line("License GPL-3.0-or-later  ·  ZXing Apache-2.0", MUTED));
        box.addView(textButton("Check for updates", v -> {
            toast("Checking for updates...");
            updater.check(true);
        }));

        AlertDialog settingsDlg = Touch.show(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Settings")
                .setView(scroll(box))
                .setPositiveButton("Done", null)
                );
        accent(settingsDlg);
        settingsDlg.setOnDismissListener(d -> {
            if (remote != null) {
                remote.onSettings = null;
            }
        });
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
        t.setPaddingRelative(0, dp(10), 0, dp(2));
        box.addView(t);
    }

    /** Wrapping text (dialogs): never cut off, unlike line(). */
    TextView para(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setLineSpacing(0, 1.1f);
        return t;
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
        v.setPaddingRelative(v.getPaddingStart() + dp(24), v.getPaddingTop(), v.getPaddingEnd(), v.getPaddingBottom());
        LinearLayout l = new LinearLayout(this);
        l.setPaddingRelative(dp(4), 0, 0, 0);
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
        accent(Touch.show(new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Sign in with email")
                .setMessage("Google and Apple don't allow signing in inside apps, so that button can't work here.\n\n"
                        + "Use \"Continue with email\" with the same address (your Gmail address works) and you get the same Claude account and sessions.\n\n"
                        + "If the email has a code, type it here. If it has a link, either allow Quill Android to open claude.ai links (button below, once), "
                        + "or copy the link and use ⚙ → Paste sign-in link.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Open claude.ai links in Quill Android", (d, w) -> openLinkSettings())
                ));
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
        banner.setPaddingRelative(dp(14), dp(10), dp(14), dp(10));
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


    TextView sessionBar;

    /** Portrait with the session list fully closed: a slim bar under the top bar shows the open session's title (tap opens the list). */
    void syncTitle() {
        if (sessionBar == null || bars == null) {
            return;
        }
        String t = "";
        if (bars.portrait() && !prefs.getBoolean("sideOpen", true) && bars.current != null) {
            SessionBars.Item it = bars.item(bars.current);
            if (it != null) {
                t = it.title.trim();
            }
        }
        if (!t.contentEquals(sessionBar.getText())) {
            sessionBar.setText(t);
            sessionBar.setContentDescription(t.isEmpty() ? null : "Open session: " + t);
        }
        sessionBar.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The Landscape/Portrait button was removed again (user 2026-11-21): clear any orientation lock it left behind. */
    void applyOrientation() {
        if (prefs.contains("orientation")) {
            prefs.edit().remove("orientation").apply();
        }
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
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

    void chatStyle() {
        styleChat(chat);
        if (kmemScreen != null) {
            styleChat(kmemScreen.chatView);
        }
    }

    /** The zoom and centering settings of the main chat, for any ChatView (the kmem Assistant tab uses the same). */
    void styleChat(ChatView v) {
        if (v != null) {
            v.setStyle(prefs.getInt("zoom", 115) / 115f, centerOn("centerText"), centerOn("centerInput"), centerOn("centerStatus"));
        }
    }

    void syncCenter() {
        chatStyle();
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
        UpdateNotes.userSet(prefs, "zoom");
        web.getSettings().setTextZoom(z);
        chatStyle();
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
        if (chat != null) {
            chat.pinch = det;
            chatStyle();
        }
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
        if (handleHome(intent) || handleOpen(intent)) {
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
                acceptPair(data.getData(), true);
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
        if (chat != null) {
            chat.saveDraft();
        }
        updateTick.removeCallbacks(updateCheck);
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

    /** Checks GitHub for a newer Quill Android when the app comes to the front and then hourly while it stays open. */
    final android.os.Handler updateTick = new android.os.Handler(android.os.Looper.getMainLooper());
    final Runnable updateCheck = new Runnable() {
        @Override
        public void run() {
            updateTick.removeCallbacks(this);
            if (updater != null) {
                updater.checkIfDue();
            }
            updateTick.postDelayed(this, Updater.CHECK_EVERY);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        if (h24 != TimeFmt.h24) {
            TimeFmt.h24 = h24;
            if (bars != null) {
                bars.render();
            }
        }
        // Paired with Quill Desktop's phone API: folders and usage come with the session list (Remote), so the old
        // folders.json/usage.json polling stays off (0.9.22; it doubled the requests and got 401 with a device password).
        if (sync != null && remote == null) {
            sync.start();
        }
        if (remote != null) {
            remote.onKmem = this::onKmemChanged;
            remote.start();
        } else if (phoneApi != null && phoneApi.paired2() && prefs.getBoolean("phone.unsupported", false)) {
            new Thread(() -> {
                if (phoneApi.sessions().ok()) {
                    prefs.edit().remove("phone.unsupported").apply();
                    runOnUiThread(this::recreate);
                }
            }, "quill-probe").start();
        }
        updateCheck.run();
        showUpdateBlocked();
        updateQuestions();
        syncNotifyBanner();
        Notifier.appVisible = true;
        if (fgNotifier != null) {
            syncNotifier();
        }
    }

    @Override
    protected void onDestroy() {
        Touch.sink = null;
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
