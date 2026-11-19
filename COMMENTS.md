# Quill for Android: notes

Android companion to Quill (Projects/quill). It is a remote-control front end for Claude Code sessions. It does not run `claude`, and it has no server or API key of its own.

## How it works
- Quill on the desktop starts every session with Claude Code's own Remote Control. The setting is "Remote Control for every session" and it is on by default.
- This app is a WebView of `https://claude.ai/code`, where Remote Control sessions show up. You sign in once and the cookies persist.
- `assets/quill.js` is injected on every page load and re-run through a MutationObserver, because claude.ai is a single-page app. It adds:
  - readability: line height, wrapped code blocks, scrollable tables, inputs of at least 16px (no auto-zoom), Quill accent colour #d97757
  - side panels: tall `aside`/`nav`/sidebar/drawer elements touching the left or right edge are tagged `data-quill-panel`. A 34px chevron button in Quill's style (translucent terracotta ring) slides each one out or back in. The state is kept per side in the page's localStorage (`quill.rightOff` / `quill.leftOff`).
  - optional centred text (≡ button)
- Native bar: the Quill icon and title (tap goes to /code), A−/A+ text zoom (80–200%, default 115%), ≡ centre, ⟳ reload.
- Dark theme comes from the app theme (Theme.Material), so the page sees `prefers-color-scheme: dark`. Quill's palette is Claude's dark palette, so no colour remapping is needed.
- Edge-to-edge (targetSdk 36): the root view pads itself by the system-bar and IME insets.
- Links outside claude.ai/claude.com/anthropic.com open in the browser. Opening a `claude.ai/code` link (for example a Quill RC session URL) opens it in this app.

## Limits
- Panel detection is heuristic. If claude.ai changes its layout, adjust `SEL` and the size checks in `scan()`.
- Google sign-in is blocked inside WebViews by Google. Use email sign-in (code or link) instead.
- No notifications yet: claude.ai exposes no page event to hook.

## Build
Toolchain lives in ~/Android: JDK 21 (Temurin), SDK (platform 36, build-tools 37), Gradle wrapper 9.9.2, AGP 9.5.1. No AndroidX dependencies.

    JAVA_HOME=~/Android/jdk-21.0.13.1+2 ./gradlew assembleDebug
    cp app/build/outputs/apk/debug/app-debug.apk dist/quill-remote.apk

Separate repo from Quill. Quill's RPM/COPR packaging (`git archive HEAD` of the quill repo) never sees this tree, and nothing here reads from Quill at build time. The icon is a copy of cmd/quill/Icon.png.
