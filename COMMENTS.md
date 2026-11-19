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

## Synced with Quill b2d351b
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
