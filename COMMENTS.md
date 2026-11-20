# Quill for Android: notes

Android companion to Quill (Projects/quill). It is a remote-control front end for Claude Code sessions. It does not run `claude`, and it has no server or API key of its own.

## How it works
- Quill on the desktop starts every session with Claude Code's own Remote Control. The setting is "Remote Control for every session" and it is on by default.
- This app is a WebView of `https://claude.ai/code`, where Remote Control sessions show up. You sign in once and the cookies persist.
- `assets/quill.js` is injected on every page load and re-run through a MutationObserver, because claude.ai is a single-page app. It adds:
  - readability: line height, wrapped code blocks, scrollable tables, inputs of at least 16px (no auto-zoom), Quill accent colour #d97757
  - side panels: tall `aside`/`nav`/sidebar/drawer elements touching the left or right edge are tagged `data-quill-panel`. A 34px chevron button in Quill's style (translucent terracotta ring) slides each one out or back in. The state is kept per side in the page's localStorage (`quill.rightOff` / `quill.leftOff`).
  - centring like Quill (see below)
  - Quill colours: code blocks and user bubbles #141413 with 8px radius, input box #30302e with #444440 border, accent caret, thin #444440 scrollbars
  - the open session in the session list gets a bold outline instead of a colour highlight (`aria-current`)
- Native bar: the Quill icon and title (tap goes to /code), A−/A+ text zoom (80–200%, default 115%), ≡ Center everything (full fill = all on, half fill = some), ⚙ settings, ⟳ reload.

## Sessions and folders (0.3.0, needs Quill 2ed7fd8+)
Quill names every Remote Control session `<Folder> · <Title>` (rename_session; see Quill COMMENTS.md "Remote Control names carry Quill's folder"). `sessions()` in quill.js reads claude.ai's `a[href*="/code/session_"]` links, splits on the first " · ", and builds its own left drawer (`#quill-drawer`, our own overlay with its own shade, so hiding it is safe). Folders come in Quill's order: Active, custom folders (in claude.ai's list order), Inactive, Archive, Created by Claude, and Other (sessions without a prefix, for example not started by Quill, or not yet titled). Folder icons as in Quill. Archive and Created by Claude start closed, open state is per folder in localStorage. The open session gets a bold outline. Tapping a row clicks claude.ai's own link (SPA navigation) and closes the drawer. Opened by the edge arrow at 42vh on the left (it rides the drawer edge, 220 ms like Quill's sidebar) or by tapping "Quill" in the bar; long-press "Quill" goes to /code. Rebuilt only when the list or path signature changes, and mutations inside `.quill-ui` are ignored, so there is no observer loop. If claude.ai only renders its list when its own menu is open, the drawer says so until the links exist.

Tested in jsdom against a mock page (/tmp test, not kept): folder order, current marker, toggle, folder collapse, click forwarding, re-injection, and no idle mutations. Layout-dependent parts (panel arrows) need a real device.

## Tab bar and folder bar (0.4.0, mirrors Quill e2724f8)
Both are native (SessionBars.java) between the top bar and the WebView, so they never cover claude.ai's own header. quill.js sends `{path, items:[{id,folder,title}], order}` to `QuillNative.sessions` (addJavascriptInterface, `SessionBars.Bridge`) whenever the session-list signature changes.
- Tab bar: like Quill's browser-style tabs. Every session opened is added (max 12, oldest dropped), saved in prefs `tabs`. Chips are #2f2f2c with an 8dp radius, and the active one has a 2dp #eceae3 outline and bold text. × closes a tab; closing the open one switches to its neighbour, or to /code when none are left.
- Folder bar: like Quill's bookmark bar. One "Folder (n)" button per folder in Quill's order with the folder icon (res/drawable/folder.xml). Tapping it opens a drop-down list (ListPopupWindow) with the open session outlined.
- Opening from either: `window.__quill.open(id)` clicks claude.ai's own link (SPA), otherwise it loads /code/<id>. Ids are checked against `session_[A-Za-z0-9]+` before they go into JS.
- ⚙ has "Tab bar" / "Folder bar" toggles (`showTabs`, `showFolders`, default on). Empty bars hide themselves.
- Not mirrored: split view (too narrow on a phone), "Delete session..." (deletes transcripts on the computer, which the phone can't reach), Format Rules (no sorting rules here).

## Vertical tabs (0.5.0, Quill's Horizontal/Vertical tab layout)
Quill's "Vertical" layout swaps the tab row and bookmark row for the folder sidebar. Here `SessionBars.applyMode` picks the layout from ⚙ "Tabs: Auto / Horizontal / Vertical" (pref `tabMode`, default auto). Auto means vertical when `screenWidthDp >= 600` (tablets, unfolded foldables, wide landscape) and horizontal otherwise. It re-runs in `onConfigurationChanged`, so folding, unfolding or rotating switches live (the activity handles screenSize/smallestScreenSize/screenLayout itself, so the WebView is not reloaded).
- Vertical: a native 300dp folder sidebar docked left of the page (`side`, 1dp #3a3a37 edge), not an overlay. Folders are collapsible (`fold.<name>`), the open session is outlined and bold, tap opens. A Quill-style ring arrow (‹/›) in the top bar, or tapping "Quill", slides it (220 ms width animation, `sideOpen`). The tab and folder bars and the JS drawer are hidden (`drawer:false` in `__quillPrefs`).
- Horizontal: the 0.4.0 tab bar plus folder bar, and the JS drawer.

## Folders from Quill (0.6.0, needs Quill 0.1.1 / b5faa86+)
Since Quill e0d62ee, Remote Control names are plain titles, so folders come from Quill's `quill-folders.json` (see Quill COMMENTS.md "Phone folder sync"): `{"version":1,"updatedMs","order","sessions":{"session_<id>":"Folder"}}`. FolderSync.java polls every 30 s while the app is in the foreground (gist at most once a minute) and on resume. The source with the newest `updatedMs` wins; the result is cached in prefs (`folders.cache`) and shown at once on the next start.
- Local network: HTTPS to each host in the pairing link (LAN IPs, then `<host>.local`) on the paired port, header `X-Quill-Key`. The certificate is accepted only if the SHA-256 of its DER equals the paired fingerprint (`FolderSync.pinned`). That is why lint's CustomX509TrustManager warning is expected, and why hostname verification is skipped (self-signed, pinned). Checked against a real `folders.Server`: correct pin + key 200, wrong key 403, wrong pin rejected in the handshake.
- Synced file: ⚙ "Choose synced file", ACTION_OPEN_DOCUMENT + takePersistableUriPermission, read through ContentResolver (works with Syncthing/Nextcloud/Dropbox local copies; Drive's provider can lag).
- Gist: `https://gist.githubusercontent.com/<owner>/<id>/raw/quill-folders.json` (raw CDN, a few minutes of delay is possible).
- Pairing: `quillpair://pair?...` from Quill's QR code. The phone camera opens it in the app (intent filter), or use ⚙ "Paste pairing link". Every field is validated (hosts, port 1025–65535, key charset, 64-hex fingerprint, owner/id gist). A new pairing replaces the old network and gist settings but keeps the synced file. "Unpair" clears everything, including the cache.
- quill.js no longer parses "Folder · Title". It gets `folders` (id → folder) and `order` in `__quillPrefs`. Sessions not in the map go under Other, and titles are shown in full.
- Tab long-press menu mirrors Quill's tab right-click menu (Close, Close others, Close tabs to the right, Move left/right). Quill's drag-to-reorder becomes Move left/right.

## Emulator tests (scripts/emu-test.sh, 0.6.1)
`scripts/emu-test.sh [--avd qfold|qphone|qtab] [--keep] [--window]` boots the AVD headless (it creates it from the android-36 google_apis x86_64 image if missing), builds and installs the **debug** APK, and runs adb/uiautomator checks. It writes PASS/FAIL lines, screenshots and logcat to build/emu-test/<time>-<avd>/ and exits non-zero on failure.
- Emulator flags: `-gpu host -feature -Vulkan`. With swiftshader_indirect, QEMU dumped core as soon as the WebView rendered (2026-11-19).
- Session links come from a mock page (python http.server on 127.0.0.1:8765, `adb reverse`), so no claude.ai login is needed. Only debug builds honour the `quill.testUrl` extra (http://127.0.0.1 or chrome://crash) and allow cleartext to 127.0.0.1 (src/debug network_security_config). Release builds have neither (checked with aapt2).
- Pairing uses a real folder server: scripts/testdata/foldersd.go is built as module github.com/katachinosekai/quill/emutest with a replace to ../quill, so it may import internal/folders. The emulator reaches it at 10.0.2.2.
- Foldables: the outer screen asks "Swipe up to continue" after folding, so the script sets `fold_lock_behavior_setting=stay_awake_on_fold_key`, wakes and dismisses the keyguard, and screenshots whichever display is ON. Native-only checks use `class="android.widget.TextView"` because the page's own drawer text is in the same accessibility dump.
- Last run on qfold (unfolded 841dp, folded 411dp): 29/30. The remaining failure was the closed JS drawer still being in the accessibility tree; fixed in 0.6.1 (visibility hidden + aria-hidden when closed). Fold testing is paused at the user's request; qphone and qtab have not been run yet.
- Bugs found by these runs: none in the app's logic. Launch, vertical sidebar, arrow, fold/unfold switching, tab bar, folder bar and drop-down, text size, settings, pairing over the pinned local network, cached folders with Quill offline, Unpair, invalid pairing link, rotation, renderer-crash recovery and back all passed.

## 0.9.0 (usage view, two-line rows, test speed)
- Usage: FolderSync also polls Quill's `GET /usage.json` (Quill b4af5d1; LAN only, same pinned TLS + key; 503 until wired) every 30 s while open; the last copy is cached in `usage.cache` (cleared on Unpair). SessionBars shows the desktop strip order "5-hour N% · resets H:mm | Session <tokens> · ctx N% | All sessions <today> today | Weekly N% · credits …" under the tabs in horizontal view (hidden with the bars) and as four lines at the top of the vertical sidebar. "Session" = meta of the open session.
- Folder rows (drop-downs and vertical sidebar) are two lines like Quill 0.1.4+: title, then a muted "● age · N msgs" with the status dot coloured as on the desktop (running blue, waiting amber, idle green, error red, terminal purple).
- claude.ai's right edge panel (context) is now hidden too (all edge panels get data-quill-off; no panel arrows remain).
- Real bug fixed: `msgBox()` returned a boolean, so `sendFrom` read an empty message and swallowed Enter. It now returns the element. Debug builds log key events to logcat (`quillkey`).
- Debug builds have applicationId suffix `.debug` ("Quill (debug)"), so tests can run on the user's real tablet next to the real app.
- emu-test: `--device <serial>` for real devices (only the debug app, no setting changes except animations, which are saved to ~/.cache/quill-emu-anim-* and restored at exit or at the next start if a run was killed), `--full` for slow checks (updater, renderer crash), quick mode by default; waits poll every 0.15 s, fixed pauses scaled to 15 %, one cached UI dump per screen; new AVD `qp90` (1200×1920@240, hardware keyboard) matching the user's P90 tablet. Phone 63/63, qp90 59/59, ~4 min per run (parallel). Gotcha: a notification heads-up banner covers the top bar, so close banners (`cmd statusbar collapse`) before tapping ⚙.

## 0.8.2 (user requests from real-tablet testing, 2026-11-20)
- Only claude.ai (+ GitHub sign-in paths) is in-app; claude.com/anthropic.com docs and "Learn more" open in the browser. Back with no history on a non-claude page reloads /code.
- quill.js `tidy()`: hides (data-quill-hidden) the sidebar rows Artifacts / Customize / More / Scheduled / Edit sidebar (exact text, outside main/form), the "Code" label in the top-left 320×90 px, and closes Remote Control "Learn more" prompts with their own Close/Dismiss/Got it/Not now button (or hides them). claude.ai's own left sidebar (any left edge panel) is always hidden (`data-quill-off`, no left arrow); Quill's native sidebar / folder bar is the only one. Session links are still read from the hidden DOM.
- Enter sends: capture keydown Enter (not composing, not keyCode 229) and beforeinput insertLineBreak/insertParagraph while Shift isn't held → click the form's submit / "Send" button; empty box sends nothing; `enterkeyhint=send`. Autocorrect/IME composition untouched.
- Hardware keyboard (KEYBOARD_QWERTY + not hidden; debug builds always): Ctrl+1–9 switch tabs (9 = last) in dispatchKeyEvent; Tab accepts the selected `[role=option][aria-selected=true]` suggestion.
- Horizontal mode never uses the JS drawer (drawer:false always; its open state is no longer persisted); the top-left arrow (▴/▾) shows/hides the tab and folder bars (`barsHidden`). Vertical mode unchanged.
- Stable order: SessionBars.stableOrder keeps `order.folders` / `order.sessions` (first-seen order, new ones appended); ⚙ "Sort folders by activity" (off) restores activity order.
- The open session is saved to `last` on every bridge update (SPA navigation), so restart reopens it.
- Emulator (phone, before the left-sidebar change): 63/67. The 4 failures were harness issues (crash check matched a Chrome crashpad report from the GitHub-link test; the Enter test focused the box via an aria-label the WebView doesn't expose). Not re-run (user asked to hurry): real-device check needed for Enter sends and the hidden items.

## GitHub connect and page trust (0.8.1)
- "Connect GitHub" on claude.ai used to leave the app: github.com went to the browser, and the redirect back to claude.ai was then claimed by the official Claude app (verified App Links). Now `githubAuth(u)` keeps exactly `https://github.com` + /login…, /session…, /sessions/…, /apps/…/installations…, /settings/installations…, /settings/connections/applications… inside the WebView, so the redirect lands in Quill. Other github.com links still open outside (emu-test checks both).
- Trust is separate from navigation: `claudePage(u)` (claude.ai/claude.com/anthropic.com, debug 127.0.0.1) gates the page bridge (`trustedPage`), the injected skin (`inject(url)` uses the URL of the page being loaded, not the WebView's possibly stale one) and "Paste sign-in link". The injected script also checks its own origin first (https + those hosts) and does nothing elsewhere, so nothing of Quill runs on GitHub pages, even mid-redirect. WebView on Android 8+ keeps no form data of its own (system autofill only).
- Notifications fix found by the first run against Quill's real /events: the first contact long-polled (wait=25), so events arriving in that window were dropped as "pre-pairing". First contact now uses wait=0 just to record boot/next. Phone emu-test: 55/55 including every notification check against the real server (folders.Listen + Emit from Quill 4077bef).

## Notifications (0.8.0, Notifier.java + NotifyService.java; needs Quill 0.1.5+)
Quill serves `GET /events?since=<id>&wait=<0..25>` on the pairing server (same pinned TLS and X-Quill-Key; spec agreed with the Quill session, implemented in Quill 4077bef): `{version:1, boot, next, events:[{id,time,type,rc,quill,title,folder}]}`. Types: approval, question, plan, finished, error. Only titles, folders and types, never chat content. ids restart at 1 per Quill start (`boot`). `since` is inclusive; it returns 404 when "Send notifications to paired phones" is off or on older Quills; 429 past 4 waiters.
- The client loops with wait=25 (long poll), 300 ms between successful polls, 15 s back-off when no host answers. It stores `events.boot` / `events.since`. On first contact (no boot stored) it notifies nothing. A boot change resets since to 0. Events older than 10 min are skipped. Nothing is shown for the session currently on screen (`Notifier.foregroundRc` from the page bridge, `appVisible`).
- Notification: channel "Session alerts" (high importance), title "<Type label>: <session title>", text = folder. Tap → MainActivity with `quill.openSession` = rc → loads claude.ai/code/<rc> (rc validated). Per-type toggles `notify.<type>` in ⚙ → Notifications.
- App open: an in-app Notifier runs only while the activity is visible. "Notify in background (local network)" (`notifyBackground`) starts NotifyService, a foreground service of type specialUse (FOREGROUND_SERVICE_SPECIAL_USE plus a subtype property explaining it) with an ongoing "Quill is watching for approvals" notification and a Stop action. It only reaches Quill on the LAN (or Tailscale/VPN); there is no cloud relay. POST_NOTIFICATIONS is requested after pairing or when background mode is enabled. Unpair stops everything and clears the cursor.
- Emulator (phone, against a spec-identical fake feed): approval for another session notifies, the session on screen stays silent, stale events are ignored, the background service starts, shows its notification, notifies with the app closed and stops when switched off. 7/8 passed; the 8th only verified tap-to-open through the saved URL (claude.ai redirects to sign-in in the emulator). The test now checks a `QuillOpen` log line instead, and scripts/testdata/foldersd.go now uses Quill's real `folders.Listen` + `Emit`. Both changes are **not yet re-run** (postponed by the user, 2026-11-19).

## Top bar and settings (0.7.2)
- Top bar is a FrameLayout. On the left: the sidebar arrow (top-left in both layouts: vertical toggles the native sidebar, horizontal toggles the JS drawer; the JS edge arrow is hidden with `edgeArrow:false`) and ≡ Center everything. In the middle: icon + "Quill", truly centred (tap = sessions, long-press = /code). On the right: ⚙ and ⟳. A−/A+ were removed: pinch changes the size, and a rounded "115%" label fades in and out at the centre of the page while pinching (alpha 0 otherwise, so tests read the size from ⚙).
- ⚙ "Settings" sections: Text (Center everything with indented Chat text / Typing / Status, "Text size N% · pinch to change" + Reset), Tabs (Auto/Horizontal/Vertical segmented control, Tab bar / Folder bar), Folders from Quill (one-line status lines, Paste pairing link, Choose synced file, Unpair), Sign-in, About (version · Quill 0.1.3, Check for updates). Each section has an accent heading and a 1dp divider; rows are 42dp, single line, ellipsized, never wrapped.
- Folder long-press (folder bar and vertical sidebar) mirrors Quill's folder menu where it makes sense on a phone: Open all in tabs; in the sidebar also Expand/Collapse all folders. Rename/delete/format rules change data on the computer, so they are not offered.
- Not mirrored from Quill 0.1.4: the usage strip and the "age · msgs" second line (claude.ai exposes no such data to the app).

## Text size and pinch (0.7.1)
The Chat width / Message box width sliders were removed (user 2026-11-19). quill.js still understands `chatWidth`/`inputWidth`, but the app no longer sends them, so both are full width. Two-finger pinch on the WebView (ScaleGestureDetector in `setupPinch`, quick-scale off) changes WebSettings.textZoom, the same value as A−/A+: `pinchZoom(start, factor)` rounds to 5 % steps, clamped 80–200 %, live, saved as pref `zoom`. Text reflows, so Quill's CSS rules and layout stay intact. The WebView's own page zoom is off (setSupportZoom/BuiltInZoomControls false). Multi-pointer events are consumed while pinching so the page doesn't scroll. Debug builds accept `--ef quill.testPinch <factor>`, which emu-test uses to check the rounding and clamping (a real two-finger gesture can't be sent through adb).

## Self-update (0.7.0, Updater.java + InstallStatus.java)
- Checks `api.github.com/repos/katachinosekai/quill-android/releases/latest` on resume (at most once a day, pref `update.checked`) and from ⚙ "Check for updates". It ignores drafts and prereleases and only offers a strictly higher `vX.Y.Z` than the installed versionName, with the asset named `quill-android-X.Y.Z.apk`.
- Safety checks before install, each covered by emu-test:
  - the asset URL must start with `https://github.com/katachinosekai/quill-android/releases/download/`; redirects are followed by hand and must stay on github.com / objects.githubusercontent.com / release-assets.githubusercontent.com (https);
  - size at most 50 MB and exactly the announced size;
  - the SHA-256 of the download must equal GitHub's asset `digest`;
  - the archive's package name must be ours with a higher versionCode;
  - its signing certificate (SHA-256) must equal the installed app's.
  Then PackageInstaller (MODE_FULL_INSTALL); Android shows its own confirmation and enforces the signature again.
- The PackageInstaller callback goes to the non-exported `InstallStatus` receiver (not the exported activity, which would allow intent redirection). It only starts the confirm intent if that resolves to a system app. `<queries>` CONFIRM_INSTALL makes the installer visible.
- Needs REQUEST_INSTALL_PACKAGES; the first update sends the user to "Install unknown apps" for Quill.
- The download sits in cacheDir/update and is deleted on start and on failure.
- Debug builds only: the `quill.updateFeed` extra (http://127.0.0.1) replaces the feed and allows the test host. `-PqVersionCode/-PqVersionName` build a higher test version.

## Security notes (0.7.0)
- The page bridge `QuillNative.sessions` is ignored unless the WebView's current page is claude.ai (or the debug test host), and input is capped at 1 MB.
- No backups or device transfer of app data (allowBackup=false, fullBackupContent=false, data_extraction_rules exclude everything). That covers the pairing key, cached folders and cookies.
- WebView: no file access; JavaScript only for claude.ai pages (navigation elsewhere opens the browser). Subframe navigations never launch the browser. Google/Apple sign-in pages are intercepted with a help dialog (Google blocks embedded sign-in).
- Release builds have no cleartext and no test hooks (checked with aapt2).

## Synced with Quill 4077bef (/events, 0.1.5 pending); earlier eb80b9e (0.1.4), b5faa86 (earlier: e2724f8, styles compared at b2d351b) (styles last compared at b2d351b)
Style and formatting are mirrored from Quill's desktop settings. To sync later, look at `git log b2d351b..` in Projects/quill for theme.go, chatview.go and desk.go changes and port what makes sense on a phone.
- Centring: "Center everything" master plus Chat text / Typing / Status, the same prefs and defaults as Quill (`centerText` on, `centerInput` off, `centerStatus` off). Chat text keeps code, tables and tool details left-aligned and puts list bullets inline (Quill's centred lists). Status = `role=status` / `aria-live` regions.
- Width sliders (⚙): "Chat width" (40–100%) pads `main` evenly on both sides, and "Message box width" (25–100%) narrows the input's form/fieldset. Both default to 100% on a phone. Quill's desktop defaults (65% chat, 45% box) would be too narrow on a phone.
- Not ported: tool chips starting collapsed (claude.ai renders its own tool blocks, and auto-clicking them is unsafe), the Latest glow, the usage panel, the chat outline, and status dots (desktop or CLI only).

## Debug notes (0.2.0)
- `ByteArrayOutputStream.toString(Charset)` is API 33 only. It crashed on Android 11/12, so it is replaced with `new String(bytes, UTF_8)`.
- WebView renderer killed (low memory) → `onRenderProcessGone` destroys the view and `recreate()`s instead of crashing the app. `web` is null-guarded during that window.
- Side-panel arrows skip `position: fixed/absolute` panels and dialogs. Hiding an overlay drawer would leave its backdrop over the page and block taps. So on a phone, where claude.ai's menus are overlays, arrows only show for panels that sit in the page layout. Close overlays with claude.ai's own controls.
- Back: `OnBackInvokedDispatcher` on API 33+, and `onBackPressed` only for 30–32 (lint GestureBackNavigation suppressed for that reason).
- Email sign-in links (`claude.ai/magic-link`) open in the app, so the sign-in cookie lands in the WebView.
- Dark theme comes from the app theme (Theme.Material), so the page sees `prefers-color-scheme: dark`. Quill's palette is Claude's dark palette, so no colour remapping is needed.
- Edge-to-edge (targetSdk 36): the root view pads itself by the system-bar and IME insets.
- Links outside claude.ai/claude.com/anthropic.com open in the browser. Opening a `claude.ai/code` link (for example a Quill RC session URL) opens it in this app.

## Limits
- Panel detection is heuristic. If claude.ai changes its layout, adjust `SEL` and the size checks in `scan()`.
- Google sign-in is blocked inside WebViews by Google. Use email sign-in (code or link) instead.
- No notifications yet: claude.ai exposes no page event to hook.

## Build
Toolchain lives in ~/Android: JDK 21 (Temurin), SDK (platform 36, build-tools 37), Gradle wrapper 9.9.2, AGP 9.5.1. No AndroidX dependencies.

    JAVA_HOME=~/Android/jdk-21.0.13.1+2 ./gradlew assembleRelease lintRelease
    cp app/build/outputs/apk/release/app-release.apk dist/quill-remote.apk

Release signing: `~/Android/quill-release.jks` plus `~/Android/quill-release.properties` (chmod 600, outside the repo, read by app/build.gradle.kts). Keep both. Updates only install over an APK signed with the same key. Without the properties file the release build is unsigned. Bump versionCode for every APK you hand out.

Separate repo from Quill. Quill's RPM/COPR packaging (`git archive HEAD` of the quill repo) never sees this tree, and nothing here reads from Quill at build time. The icon is a copy of cmd/quill/Icon.png.
