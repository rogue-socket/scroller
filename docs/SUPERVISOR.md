# Supervisor Module

## Overview
The Supervisor is a pure decision module that detects unsafe or unproductive loop patterns. It does **not** execute actions, call the LLM, or access Android system APIs. It only evaluates signals and returns a `SupervisorDecision`. It accepts a precomputed `ScreenFingerprint` so it has no Android dependency.

## Perceptual Hash (ScreenFingerprint)
To avoid naive bitmap comparison, the Supervisor uses a perceptual hash:
1. Downscale the bitmap to 32x32.
2. Convert each pixel to grayscale luminance.
3. Compute the mean luminance.
4. Encode a 1024-bit hash where each bit is 1 if pixel > mean, else 0.

Two screens are considered identical if their **similarity** (1 - HammingDistance / 1024) is ≥ `screenDiffThreshold`.

## Oscillation Detection
The Supervisor examines the last 4 actions and flags the pattern A → B → A → B (where A != B). If this oscillation repeats consecutively `maxOscillationCount` times, it returns `OscillationDetected`.

## Memory Bounding Strategy
- Screen fingerprints are kept in a fixed-size circular buffer (`fingerprintHistorySize`).
- No raw bitmaps are stored.
- Action history is already bounded by the loop (last 20 actions).

## Limitations
- Perceptual hashes can miss subtle UI changes; thresholds are heuristic.
- Oscillation detection is limited to 2-action patterns.
- Repeated actions are detected only within the configured window.

## Why Naive Bitmap Compare Fails
Full bitmap equality is expensive, fragile across minor rendering changes, and can produce false negatives. Perceptual hashes are resilient and bounded in size.

## Decisions
- `Continue`
- `RepeatedActionDetected`
- `StalledScreenDetected`
- `OscillationDetected`
- `FailureCascadeDetected`

The Supervisor only signals. The AgentLoopController decides how to respond.
