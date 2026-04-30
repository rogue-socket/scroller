# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build everything
./gradlew assembleDebug

# Build individual modules
./gradlew :app:assembleDebug
./gradlew :action-executor:assembleDebug

# Clean build
./gradlew clean assembleDebug

# Install on connected device
./gradlew :app:installDebug
```

No test suite exists yet. No linter is configured.

## Architecture

Scroller is an autonomous Android UI agent that runs a bounded **Perception → Reasoning → Action** loop:

1. **Capture** — `ScreenCaptureManager` uses MediaProjection + ImageReader to grab screenshots (scaled to max 1024px, JPEG quality 75)
2. **Decide** — `LlmClient` (Gemini or OpenAI) receives the screenshot + goal + last 10 actions + memory summary and returns a strict-JSON action or "done"
3. **Execute** — `AccessibilityActionExecutorService` dispatches the action (tap, scroll, type, press_back, open_app) via Android Accessibility APIs
4. **Evaluate** — `Supervisor` checks for repeated actions, stalled screens, oscillation, or failure cascades; `RecoveryPolicy` injects corrective actions
5. **Repeat** — up to `maxSteps` (default 25), with 500ms delay between iterations

### Module layout

- **`app/`** — Thin Android host. `MainActivity` handles settings UI and loop lifecycle. `ScrollerApp` manages encrypted SharedPreferences for API keys. `MediaProjectionForegroundService` holds the screen capture permission.
- **`action-executor/`** — All core logic (~40 files). This is the library module containing the agent loop, LLM clients, action executor, supervisor, recovery, memory, and orchestrator.

### Key design decisions

- **Pure logic separation**: Supervisor, RecoveryPolicy, and AgentMemory have zero Android dependencies. Only capture, execution, and MediaProjection touch Android APIs.
- **Channel-based command bus**: `ActionCommandBus` uses `Channel(capacity=1)` for deterministic action sequencing. Gestures dispatch on `Dispatchers.Main.immediate`.
- **Per-stage timeouts**: capture (2s), LLM decision (15s), action execution (5s). Three consecutive failures abort the loop.
- **Strict JSON schema**: LLM output is validated via Moshi with `additionalProperties: false` before reaching the executor. Invalid output never executes.
- **Deterministic recovery**: No randomness. Repeated action → PressBack, stalled screen → ScrollDown, oscillation → PressBack, failure cascade → Abort. Max 2 recovery attempts.
- **Bounded memory**: All history buffers are capped (20 actions, 5 fingerprints, 10 decisions, 10 events). Memory summary max 600 chars.

### Orchestrator (optional)

`Orchestrator` can decompose a goal into 1–5 subgoals via a single LLM call, then runs each sequentially through `AgentLoopController`. Aborts on first subgoal failure.

## Build Environment

- Gradle 8.2.2 (wrapper included, jar not committed)
- Kotlin 1.9.22, JDK 17
- compileSdk/targetSdk 34, minSdk 26
- Key dependencies: OkHttp 4.12.0, Moshi 1.15.1, kotlinx-coroutines 1.7.3, androidx.window 1.2.0

## Setup on Device

1. Build and install `:app`
2. Enable accessibility service: App → Open Accessibility Settings → enable "Scroller"
3. Grant MediaProjection: App → Request Screen Capture Permission → allow

## Documentation

Detailed module docs live in `docs/` — start with `docs/SYSTEM_ARCHITECTURE.md` for the full picture.
