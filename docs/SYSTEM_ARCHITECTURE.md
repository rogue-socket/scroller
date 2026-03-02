# System Architecture

## Overview
This system is a bounded, deterministic autonomous UI controller composed of:
- Screen capture (MediaProjection)
- LLM reasoning boundary (strict JSON)
- Action executor (Accessibility)
- Agent loop (bounded)
- Supervisor + Recovery (safety)
- Orchestrator (sequential subgoals)
- Memory (bounded context)

## Architecture Diagram
```mermaid
flowchart LR
    Capture["ScreenCapture"] --> Loop["AgentLoopController"]
    Loop --> LLM["LlmClient"]
    Loop --> Exec["ActionExecutor"]
    Loop --> Sup["Supervisor"]
    Sup --> Rec["RecoveryPolicy"]
    Rec --> Loop
    Mem["AgentMemory"] --> Loop
    Orchestrator --> Loop
```

## Failure Flow (High Level)
```mermaid
flowchart TD
    A["Capture/LLM/Execute failure"] --> B["Loop failureCount++"]
    B --> C{">= threshold?"}
    C -- Yes --> D["LoopResult.Failed"]
    C -- No --> E["Continue bounded loop"]
    E --> F["Supervisor decision"]
    F --> G["Recovery or Abort"]
```

## Timeout Layering
- Screen capture: `captureTimeoutMs`
- LLM call: `llmTimeoutMs`
- Execution: `executionTimeoutMs`
- Orchestration: single call per goal decomposition

## Memory Bounding
- Actions: 20
- Screen fingerprints: 5
- LLM decisions: 10
- Events: 10

## Recovery Policy
- Deterministic injected actions only.
- Maximum recovery attempts per run.
- Any repeated supervisor trigger after recovery aborts.

## Explicit Non-Goals
- No persistent memory or vector search.
- No dynamic or adaptive learning.
- No recursive orchestration or parallel agents.
- No retries inside LLM client.
