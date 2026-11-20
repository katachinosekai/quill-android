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

## Synced with Quill e2724f8 (styles last compared at b2d351b)
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
