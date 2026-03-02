# Screen Capture Module (MediaProjection)

## Overview
This module provides a production-grade, coroutine-based screen capture API using MediaProjection. It exposes:
- `ScreenCapture` interface (`suspend fun capture(): ScreenFrame`).
- `ScreenCaptureManager` implementation.
- `MediaProjectionController` to manage MediaProjection lifecycle and VirtualDisplay creation.

All communication is in-process. There is no Binder IPC, no static access, and no reliance on deprecated display APIs.

## Lifecycle Diagram
```mermaid
flowchart LR
    Activity["Host Activity"] -->|request permission| MPIntent["MediaProjection Intent"]
    Activity -->|initialize(projection)| Controller["MediaProjectionController"]
    Executor["ScreenCaptureManager"] -->|capture()| Controller
    Controller -->|createVirtualDisplay| VD["VirtualDisplay"]
    VD --> Reader["ImageReader"]
    Reader -->|latest Image| Executor
    Controller -->|onStop| Controller
```

## Permission Flow
1. Host Activity calls `MediaProjectionController.createProjectionIntent()`.
2. User grants permission via system dialog.
3. Activity receives result via `registerForActivityResult`.
4. On success, Activity obtains `MediaProjection` and calls `MediaProjectionController.initialize(projection)`.
5. `ScreenCaptureManager.capture()` throws `NoActiveProjectionException` if permission not granted.

## Concurrency Model
- Only one capture at a time enforced via `Mutex` in `ScreenCaptureManager`.
- A capture must complete or fail before the next can start.
- Timeout: if no image is acquired within 2 seconds, `CaptureTimeoutException` is thrown.

## Memory Handling
- A fresh `VirtualDisplay` and `ImageReader` are created for each capture.
- `ImageReader` uses a dedicated `HandlerThread` and is always closed after capture.
- Images are closed in a `use` block to prevent leaks.
- A reusable scratch bitmap is used for row-padding copies. The returned `Bitmap` is always a new instance to avoid mutation by later captures.

## Failure Cases
- `NoActiveProjectionException`: capture called without an active MediaProjection.
- `CaptureTimeoutException`: no frame within 2 seconds.
- `CaptureFailedException`: unexpected failures during capture or bitmap conversion.

## Security Implications
- MediaProjection permission grants full screen access. The host app must request it explicitly and handle denial.
- No background capture occurs without an active MediaProjection instance.

## Integration Notes (Future LLM Pipeline)
`ScreenCaptureManager.capture()` returns a real `Bitmap` with width/height/timestamp. Downstream perception or LLM modules can consume `ScreenFrame` without changes to this module.
