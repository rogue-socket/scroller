# Agent Memory

## Purpose
Provides bounded, in-memory context for the loop to improve stability without persistence or semantic search. It stores fingerprints and decisions as pure data, not bitmaps.

## Stored Data (Bounded)
- Last 20 actions
- Last 5 screen fingerprints
- Last 10 LLM decisions
- Last 10 events (failures, recoveries)

## Compression Strategy
`AgentMemory.compressedSummary()` produces a short summary string with:
- Recent actions
- Recent events

The summary is capped to a maximum character length (default 600) before being passed to the LLM client.

## Failure Flow
Memory does not recover or retry. It only provides context to the LLM. Any failures are tracked as short events.

## Why Intentionally Limited
This module avoids unbounded history, background persistence, or vector search. It is purely a bounded, deterministic context buffer.
