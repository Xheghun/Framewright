# Framewright

Framewright is a professional-grade video diagnostics toolkit for Android, designed to provide deep visibility into media playback performance, ABR (Adaptive Bitrate) behavior, and DRM health.

Unlike typical video players, Framewright focuses on **observability** and **reproducibility**, making it an essential tool for media engineers and developers working with complex playback stacks.

## Key Features

- **Media-Agnostic Analytics**: A core telemetry engine that decouples event logic from the specific player implementation.
- **ABR Explorer**: Real-time visualization of bandwidth estimation vs. track selection(WIP).
- **Diagnostics Overlay**: A performance-focused Compose overlay for monitoring resolution, codecs, and buffer health in-situ(WIP).
- **DRM Inspector**: Detailed Widevine status reporting and a catalog of reproducible failure signatures(WIP).
- **Media Lab**: A fixture-driven simulation environment to reproduce edge-case bugs without real network infrastructure(WIP).

## Project Structure

Framewright is organized into specialized modules to ensure a clean separation of concerns:

| Module | Description |
| :--- | :--- |
| [`:analytics`](file:///analytics) | Pure Kotlin core for tracking session lifecycle and diagnostic events. |
| [`:player-core`](file:///player-core) | Media3/ExoPlayer implementation and event mapping. |
| [`:bandwidth-monitor`](file:///bandwidth-monitor) | Custom bandwidth estimators and ABR tracking logic. |
| [`:codec-inspector`](file:///codec-inspector) | Device capability reporting (HW vs. SW codecs). |
| [`:drm-inspector`](file:///drm-inspector) | DRM key status tracking and failure cataloging. |
| [`:diagnostics-overlay`](file:///diagnostics-overlay) | Real-time UI overlay for playback stats. |
| [`:storage`](file:///storage) | Room-backed persistence for playback sessions. |
| [`:media-lab`](file:///media-lab) | Fixture-based playback simulation and case studies. |
| [`:app`](file:///app) | The main demonstration activity and UI. |

## 🛠 Getting Started

### Prerequisites
- Android Studio Ladybug or newer.
- Android SDK 35.
- Kotlin 2.1.0+.

### Build & Run
```bash
./gradlew :app:assembleDebug
```

---

> [!NOTE]
> This project is currently in active development as part of a technical deep-dive into Media3 and Android telemetry.
