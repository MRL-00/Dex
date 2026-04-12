# Remodex Android

Native Android client for Remodex, built from the existing iOS app and local-first bridge/relay protocol in this repo.

## Status

Phase 2 complete, Phase 3 scaffolded:

**Phase 1 (complete)**
- QR pairing against the existing bridge payload
- Secure relay handshake and encrypted JSON-RPC transport
- Saved pairing and trusted-Mac persistence
- Thread list, thread history load, streaming assistant output, interrupt, and approval handling

**Phase 2 (complete)**
- Adaptive sync loop (foreground/background intervals matching iOS)
- Plan mode: step progress cards, streaming plan text, structured user input cards
- Git actions: status, commit, push, pull, branches, checkout, diff viewer
- Local notifications for turn completion and structured input (backgrounded)
- Model/reasoning/service-tier runtime controls per thread
- Access mode toggle (on-request / full-access with auto-approval)
- Dark mode support (system-aware theme)
- Markdown rendering: bold, italic, inline code, fenced code blocks with copy
- Thinking sections (collapsible), tool activity indicators
- Modularized UI: extracted components for messages, composer, sidebar, plan, git, settings
- Background recovery with auto-reconnect on foreground resume
- 22 unit tests covering models, parsing, crypto, and protocol logic

**Phase 3 (scaffolded)**
- Voice recording (24kHz WAV) + ChatGPT transcription via `voice/resolveAuth` RPC
- Subscription service scaffold (free-send quota, RevenueCat integration point)
- Push notification plumbing (FCM scaffold, `notifications/push/register` support)

## Structure

```
core/
  model/        Protocol models, plan/git/runtime/attachment data types
  data/         RemodexRepository (connection, sync, RPC, git, model controls)
  security/     QR validation, secure storage, transcript/HKDF/envelope crypto
  network/      OkHttp WebSocket relay transport
  notification/ Local notification service
  voice/        Voice recording manager, GPT transcription client
  payments/     Subscription service (RevenueCat scaffold)
app/            Application, Activity, ViewModel, root composable
ui/
  theme/        Material 3 theme (Geist fonts, light/dark)
  screen/       Settings screen
  component/    MessageBubble, ComposerBar, SidebarContent, PlanCard,
                StructuredInputCard, GitToolbar, GitDiffSheet,
                GitBranchSelector, RuntimeControlsMenu, ApprovalCard,
                VoiceRecordingCapsule
```

## Setup

1. Open `RemodexAndroid/` in Android Studio.
2. Run `./gradlew assembleDebug` or use the `app` run configuration.
3. Run the app on a device running Android 9+ (API 28).
4. Start the local bridge:

```bash
cd phodex-bridge
npm start
```

5. Scan the QR code or paste the pairing payload.

## Build & Test

```bash
./gradlew assembleDebug   # Build debug APK
./gradlew test             # Run unit tests (22 tests)
```

## What Remains

- Image attachment capture and upload (CameraX + data URL encoding)
- Thread archiving, rename, and search in sidebar
- Context window usage display
- Rate limit tracking UI
- Worktree handoff UI
- Mermaid diagram rendering
- Full RevenueCat integration (requires API key configuration)
- FCM push notifications (requires Firebase project setup)
- Polished subagent collaboration views
- File mention autocomplete in composer

## Notes

- The Mac bridge and relay remain the source of truth.
- No backend changes were required for any parity work.
- The Android client reuses the same secure pairing transcript, trusted-session resolve, AES-GCM envelope format, and JSON-RPC surface as iOS.
