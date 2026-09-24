# Min

Unofficial Android client that runs the official **Claude Code CLI** inside Ubuntu on device (proot).
No root required. Phones and tablets.

**Not affiliated with, endorsed by, or sponsored by Anthropic.** You bring your own API token or relay.

[中文](README_zh.md)

## What it does

- Three-step setup: enter a token / relay endpoint → download the Ubuntu 24.04 base image (~30 MB, falling back to a mirror when the official source is unreachable) →
  install Node.js 22 + `@anthropic-ai/claude-code` (the App resumable-downloads the native binary itself and verifies it against the official sha512)
- Sessions: multiple sessions, streaming output, collapsible tool-call cards, permission prompts / AskUserQuestion / plan mode, checkpoint undo,
  live switching of model / permission mode / effort / cwd, slash commands, `@` file mentions, images
- Background: a foreground service keeps work alive (degrading gracefully when an OEM ROM refuses it); notifications for pending approvals, completion, and interruption, with allow / deny directly on the notification
- Environment: a files page (import / export / share / edit), a real terminal (Termux terminal-view), CLI / apt updates, reinstall
- Tablet / landscape: the session list stays pinned in the left column

## Interface

Visual and motion rules live in `DESIGN.md` ("海 / sea": paper / ink / sea). There is no "blue" color value anywhere in the UI — every blue area is a window cut out of the paper in that shape,
laid over an alcohol-ink blue texture; the whole screen shares one sea, the paper slides across it as you scroll, and tilting the phone shifts the sea beneath the paper. The launcher icon is a two-tone diagonal Min (Cormorant Garamond italic, sand / sea).

<p align="center">
  <img src="docs/screenshots/session-dark.jpg" width="250" alt="Session · dark">
  <img src="docs/screenshots/tools-and-cost-dark.jpg" width="250" alt="Tool calls and context usage · dark">
  <img src="docs/screenshots/tablet-split-light.jpg" width="510" alt="Tablet landscape split view · light">
</p>

## Build

```bash
./gradlew :app:assembleDebug
```

Requires the Android SDK (`sdk.dir` in `local.properties`) and **JDK 17**. Output lands in `app/build/outputs/apk/debug/`;
install `app-arm64-v8a-debug.apk` (phones / tablets) or `app-x86_64-debug.apk` (emulator).

Unit tests: `./gradlew :app:testDebugUnitTest`

On first launch the setup wizard downloads the rootfs / Node / CLI separately (size and speed depend on the mirror). That is independent of building the APK.

## Layout

```
app/        dev.min.code — UI, session protocol, installer, settings, services
workspace/  proot startup, rootfs install, fake /proc and /dev/shm, pty
highlight/  syntax highlighting
tools/      build_sea_assets.py, uitap.py (tap emulator buttons by their text)
```

## License

**AGPL-3.0** (see `LICENSE`). Third-party code and font attributions are in `NOTICE`.

Claude Code is a product of Anthropic, PBC. This project only drives the publicly distributed CLI with credentials you supply.
