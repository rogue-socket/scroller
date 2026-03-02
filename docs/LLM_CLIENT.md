# LLM Client Module

## Overview
This module defines a strict boundary for reasoning. It sends structured input to an LLM, enforces a JSON schema response, validates it, and returns **only** typed `LlmDecision` results. No raw strings are surfaced.

## Public API
```kotlin
interface LlmClient {
    suspend fun decideNextAction(
        goal: String,
        screen: ScreenFrame,
        actionHistory: List<AgentAction>,
        memorySummary: String
    ): LlmDecision
}
```

```kotlin
sealed class LlmDecision {
    data class Action(val action: AgentAction) : LlmDecision()
    object Done : LlmDecision()
}
```

## Request Format
- System prompt establishes role and strict JSON-only output.
- User content includes:
  - Goal
  - Last 10 actions max
  - Compressed memory summary
  - Screenshot (JPEG, base64, max 1024px)

## JSON Schema
```json
{
  "type": "object",
  "required": ["action"],
  "additionalProperties": false,
  "properties": {
    "action": {"type": "string", "enum": ["tap", "scroll", "press_back", "type", "open_app", "done"]},
    "x": {"type": "integer"},
    "y": {"type": "integer"},
    "direction": {"type": "string"},
    "text": {"type": "string"},
    "packageName": {"type": "string"}
  }
}
```

Validation rules:
- `tap` requires `x` and `y`.
- `scroll` requires `direction`.
- `type` requires `text`.
- `open_app` requires `packageName`.
- `press_back` and `done` allow **no extra fields**.

## Failure Modes
- `LlmException.NetworkError`: HTTP/network errors.
- `LlmException.Timeout`: overall call exceeds 15s or socket timeout.
- `LlmException.InvalidResponse`: non-JSON or malformed response.
- `LlmException.SchemaViolation`: JSON parsed but fails strict validation.

## Image Preprocessing
- Bitmap is scaled so the maximum dimension is **1024px**.
- JPEG compression at quality 75 balances size and fidelity.
- Result is base64-encoded and sent as a `data:image/jpeg;base64,...` URL.

## Security Notes
- API key is injected at runtime, never hardcoded.
- The module returns only validated, typed actions.
- Strict schema validation prevents hallucinated or malformed actions from reaching the executor.

## Why Strict Validation
This module is the “reasoning firewall.” It ensures that:
- Only known, permitted actions are executed.
- All parameters are present and valid for the action type.
- Malformed or free-form output is rejected early.

## Why No Retries Here
Retries are an orchestration concern and must live in the AgentLoopController. This keeps the LLM boundary deterministic and composable.
