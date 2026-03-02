# Scroller — Autonomous Android UI Control System

Scroller is a modular, production‑grade Android system for **autonomous UI control**. It is built as a sequence of strongly isolated modules that together form a closed‑loop **Perception → Reasoning → Action** engine, with strict safety guardrails.

This repository implements:
- **Hands**: AccessibilityActionExecutor
- **Eyes**: MediaProjection ScreenCapture
- **Reasoning Boundary**: Strict LLM Client (JSON‑only)
- **Heartbeat**: AgentLoopController (bounded control loop)
- **Safety Layer**: Supervisor + RecoveryPolicy
- **Task Segmentation**: Orchestrator (sequential subgoals)
- **Short‑Term Memory**: AgentMemory (bounded context)

No scaffolding. No fake responses. No hardcoded UI assumptions.

---

## Project Structure

- `app/` — Host Android app (minimal UI for enabling accessibility + MediaProjection permission)
- `action-executor/` — Library module containing all core logic and services
- `docs/` — Architecture and module documentation

---

## Architecture Overview

The system runs a **bounded loop**:

1. Capture screen (MediaProjection)
2. Ask LLM for next action (strict JSON schema)
3. Execute action (Accessibility)
4. Evaluate safety (Supervisor + Recovery)
5. Repeat within strict bounds

**Core modules are pure logic** (no Android APIs) except for:
- Screen capture
- Accessibility execution
- MediaProjection control

Reference diagram: `docs/SYSTEM_ARCHITECTURE.md`.

---

## Modules

### 1) AccessibilityActionExecutor (Hands)
- **File(s)**: `action-executor/src/main/java/com/scroller/agent/executor/AccessibilityActionExecutorService.kt`, `AndroidActionExecutor.kt`
- Executes:
  - `Tap(x, y)`
  - `Scroll(direction)`
  - `PressBack`
  - `Type(text)`
  - `OpenApp(packageName)`
- Uses Accessibility APIs only.
- All gestures dispatched on `Dispatchers.Main.immediate`.
- No hardcoded coordinates.
- **Backpressure** via `Channel<ActionCommand>(capacity = 1)`.

### 2) ScreenCapture (Eyes)
- **File(s)**: `ScreenCaptureManager.kt`, `MediaProjectionController.kt`, `ScreenFrame.kt`
- MediaProjection‑based capture.
- Real-time bounds via `WindowMetricsCalculator`.
- Strict timeout on capture.
- No deprecated Display APIs.

### 3) LLM Client (Reasoning Boundary)
- **File(s)**: `LlmClient.kt`, `OpenAiLlmClient.kt`, `ActionSchema.kt`
- **Strict JSON schema validation** enforced.
- Invalid output never reaches executor.
- API key is injected at runtime.
- Input includes:
  - Goal
  - Last 10 actions
  - Compressed memory summary
  - Screenshot (JPEG, max dimension 1024, quality 75)

### 4) AgentLoopController (Heartbeat)
- **File(s)**: `AgentLoopController.kt`, `LoopConfig.kt`, `LoopResult.kt`, `LoopState.kt`
- Bounded loop with:
  - `maxSteps`
  - `stepDelayMs`
  - per‑stage timeouts
- Cancellation propagates cleanly.
- No Android dependencies.

### 5) Supervisor + RecoveryPolicy (Safety Layer)
- **File(s)**: `Supervisor.kt`, `ScreenFingerprint.kt`, `RecoveryPolicy.kt`
- Detects:
  - Repeated actions
  - Stalled screen
  - Oscillation
  - Failure cascade
- Recovery is deterministic and bounded.

### 6) Orchestrator (Lightweight Subgoals)
- **File(s)**: `Orchestrator.kt`, `Subgoal.kt`
- One LLM call to split goal into 1–5 subgoals.
- Runs subgoals sequentially; aborts on first failure.

### 7) AgentMemory (Short‑Term Context)
- **File(s)**: `AgentMemory.kt`, `MemorySnapshot.kt`
- Bounded buffers for actions, events, fingerprints, decisions.
- Provides compressed summary for the LLM.

---

## Safety Guarantees

- **No infinite loops**: `maxSteps`, failure thresholds, timeouts.
- **No silent failures**: all errors surface via structured results.
- **No uncontrolled retries**: retries are explicit and bounded.
- **No Android dependencies in core logic**: Supervisor/Recovery/Memory are pure Kotlin.
- **No unbounded memory growth**: all history buffers capped.

---

## Build Requirements

- Android Studio (Giraffe+)
- Gradle 8.2.2 (wrapper scripts included; jar not committed)
- Kotlin 1.9.22
- JDK 17

---

## Quick Start

1. Open project in Android Studio.
2. Ensure Gradle sync completes.
3. Build `:app` and install on device.
4. Enable accessibility service:
   - Open app → **Open Accessibility Settings** → enable **Scroller**
5. Request MediaProjection permission:
   - Open app → **Request Screen Capture Permission** → allow.

---

## Configuration

All critical limits are configurable:

- `LoopConfig`
  - `maxSteps`, `stepDelayMs`, timeouts
- `SupervisorConfig`
  - repeated actions, oscillation thresholds
- `RecoveryConfig`
  - `maxRecoveryAttempts`

No magic numbers in controllers.

---

## Security Notes

- API keys must be injected at runtime.
- No API keys are stored in code or logs.
- LLM input/output is strictly validated.

---

## Documentation

Key docs:
- `docs/SYSTEM_ARCHITECTURE.md`
- `docs/ACCESSIBILITY_ACTION_EXECUTOR.md`
- `docs/SCREEN_CAPTURE_MODULE.md`
- `docs/LLM_CLIENT.md`
- `docs/AGENT_LOOP_CONTROLLER.md`
- `docs/SUPERVISOR.md`
- `docs/RECOVERY.md`
- `docs/ORCHESTRATOR.md`
- `docs/MEMORY.md`

---

## Non‑Goals (Explicit)

- No persistence across app restarts
- No background services beyond required Android services
- No multi‑agent parallelism
- No semantic memory or vector DB
- No hidden retries

---

## License

This project is internal and experimental. Add a license if you plan to distribute.
