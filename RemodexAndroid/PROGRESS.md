# Progress

## Phase 1 (complete)

- Native Android project scaffold with Compose, CameraX, Coroutines, OkHttp, and Kotlin serialization
- Secure QR payload validation
- Android-side secure identity generation and encrypted local persistence
- Bridge-compatible transcript/signature/HKDF/envelope crypto helpers
- OkHttp WebSocket relay transport with `x-role: iphone`
- Repository flow for connect, initialize, list threads, read thread history, start turns, interrupt turns, and approval responses
- Compose shell for pairing, thread list, turn timeline, composer, approval banner, connection state, and settings basics
- Unit tests for pairing validation and secure envelope round-trips

## Phase 2 (complete)

- **Architecture refactor**: Split monolithic `RemodexApp.kt` into 14 focused component files
- **Adaptive sync loop**: Thread list (10s/75s), active thread (3s/90s), running watch (2s/15s) matching iOS intervals
- **Plan mode**: `turn/plan/updated`, `turn/plan/delta`, `item/tool/requestUserInput` handling with step progress cards and structured input UI
- **Git actions**: `git/status`, `git/commit`, `git/push`, `git/pull`, `git/branches`, `git/checkout`, `git/diff`, `git/createBranch` RPCs with toolbar, diff sheet, and branch selector UI
- **Local notifications**: Turn completion and structured input notifications when backgrounded, with deep-link tap routing
- **Model/reasoning controls**: `model/list` fetch, per-thread runtime overrides (model, effort, service tier), sent with `turn/start`
- **Access mode**: `sandbox/setApprovalPolicy` RPC, auto-approval in full-access mode, settings toggle
- **Dark mode**: System-aware theme selection (was hardcoded to light)
- **Rich message rendering**: Markdown (bold, italic, inline code), fenced code blocks with language label and copy button, horizontal scroll
- **Thinking sections**: Collapsible "Thinking" disclosure with `item/reasoning/delta` streaming
- **Tool activity**: Inline tool-use indicators with `item/toolUse/started`/`completed`
- **Background recovery**: Lifecycle-aware auto-reconnect on foreground resume
- **UI polish**: Empty state with icon, running badges in sidebar, error container colors, thread title overflow
- **Models**: `PlanModels.kt`, `GitModels.kt`, `RuntimeModels.kt`, `AttachmentModels.kt` with JSON parsing helpers
- **Tests**: 22 unit tests (+12 new) covering plan step parsing, git status/branches parsing, runtime config enums, voice preflight, existing crypto tests

## Phase 3 (scaffolded)

- **Voice recording**: `VoiceRecordingManager` captures 24kHz mono 16-bit PCM WAV (max 60s / 10MB)
- **Voice transcription**: `GptTranscriptionClient` POSTs multipart WAV to `chatgpt.com/backend-api/transcribe` with ephemeral token from `voice/resolveAuth` RPC
- **Voice UI**: `VoiceRecordingCapsule` hold-to-record composable with pulsing animation
- **Subscription service**: Free-send quota (5 attempts) with `SubscriptionService`, RevenueCat integration scaffold with clear TODO markers
- **Push plumbing**: `notifications/push/register` RPC support in repository, `POST_NOTIFICATIONS` permission declared

## Remaining

- Image attachment capture (CameraX) and data-URL encoding for `turn/start` input
- Thread archiving, rename, and search in sidebar
- Context window usage display (`thread/contextWindow/read`)
- Rate limit tracking (`account/rateLimitsUpdated`)
- Bridge update prompt (`ui/bridgeUpdateAvailable`)
- Worktree handoff UI
- Mermaid diagram rendering
- Full RevenueCat integration (requires API key)
- FCM push setup (requires Firebase project)
- Subagent collaboration views
- File mention autocomplete in composer
- Comprehensive edge-case recovery matching all iOS close-code handling
