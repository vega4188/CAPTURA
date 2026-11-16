# Captura

**One-tap stealth media capture (Photo, Video, Audio) straight from the Android notification shade — zero UI friction, built for speed.**

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue?style=flat-square)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![CameraX](https://img.shields.io/badge/Jetpack-CameraX-4285F4?style=flat-square)](https://developer.android.com/training/camerax)
[![Shizuku](https://img.shields.io/badge/Privilege-Root%20%7C%20Shizuku-orange?style=flat-square)](https://shizuku.rikka.app/)
[![License](https://img.shields.io/badge/License-MIT-lightgrey?style=flat-square)](LICENSE)

---

## 📖 Overview

**Captura** is a lightweight, native Android utility designed for environments where speed and zero visual interruption are critical—such as workshops, field documentation, and academic visits. 

Instead of unlocking your phone, hunting for the camera app, and waiting for viewfinders to initialize, Captura lives persistently in your notification shade (and Quick Settings). A single tap captures a photo, starts recording video, or captures ambient audio without ever launching a visible UI or interrupting what is currently on your screen.

---

## ✨ Key Features

* **Zero-UI Headless Capture:** Uses an invisible, translucent activity trampoline compliant with Android 12+ background launch restrictions to bind CameraX and trigger media capture in under `~1.5s`.
* **Persistent Shade Controls:** Trigger **Photo**, **Video** (with live `● REC mm:ss` timer), or **Audio** directly from the notification bar.
* **Quick Settings Integration:** Includes a native `TileService` for instant toggling from the system Quick Settings panel.
* **Privilege Automation (Root & Shizuku):** Automatically grants runtime permissions, sets `appops` flags, and whitelists the app from Doze/battery optimizations via `su` or a modern `Shizuku UserService` (with automatic fallback to standard runtime dialogs).
* **Haptic-Only Feedback:** Completely toast-free. File saves are confirmed discreetly via a short vibration pulse.
* **Scoped Storage Native:** Saves directly to `DCIM/Captura/` (photos & video) and `Recordings/Captura/` (`.m4a` AAC audio) via `MediaStore` using `yyyyMMdd_HHmmss` timestamps.
* **Crash-Safe Recording:** Gracefully handles low storage, camera-in-use conflicts, and unexpected service kills by finalizing media containers mid-stream.

---

## 🛠️ Architecture & Tech Stack

Built strictly with modern Android Jetpack components and minimal external dependencies:

* **Language:** 100% Kotlin (Gradle Kotlin DSL)
* **UI:** Material 3 (`SettingsActivity` — single-screen configuration, zero bloat)
* **Media Engine:** Jetpack CameraX (`ImageCapture` & `VideoCapture`) + `MediaRecorder` (AAC)
* **Lifecycle & Background:** `LifecycleService` with `camera|microphone` Foreground Service types
* **Persistence:** Jetpack Preferences DataStore
* **System Privileges:** Shizuku API (`UserService` IPC) & Native `su` shell execution

### How It Works Under the Hood
To comply with Android 12+ and Android 14 Foreground Service (FGS) restrictions without displaying a camera preview:
1. Tapping a notification action fires a `PendingIntent.getActivity` targeting `CaptureActivity` (`Theme.Translucent.NoTitleBar`, `excludeFromRecents="true"`).
2. Because `CaptureActivity` briefly holds foreground focus, it legally elevates `RecorderService` to a `camera|microphone` Foreground Service and immediately calls `finish()`.
3. `RecorderService` binds CameraX directly to its `LifecycleOwner` off-screen, writes the stream to `MediaStore`, and triggers a haptic pulse upon completion.

---

## ⚙️ Configuration (`SettingsActivity`)

The app consists of a single configuration screen:

| Control | Description |
| :--- | :--- |
| **Privilege Mode** | Switch between **Root** (`su -c`) and **Shizuku** (`UserService`). |
| **Status Indicator** | Real-time diagnostic feedback (`Mode OK` or exact Shizuku/Root error state). |
| **Apply Permissions** | Executes `pm grant`, `cmd appops set`, and `deviceidle whitelist` silently. |
| **Camera Lens** | Toggle between **Back** and **Front** camera sensors. |
| **Video Quality** | Select between **720p** and **1080p** output resolution. |
| **Quick Settings Tile** | Enable or disable the system Quick Settings tile component. |

---

## 🚀 Getting Started

### Prerequisites
* Android Studio Hedgehog (2023.1.1) or newer
* JDK 17
* Android device running **Android 10 (API 29)** through **Android 14 (API 34)**
* *(Optional)* **Magisk / KernelSU** (for Root mode) or **Shizuku** installed and running.

### Build & Run
```bash
git clone [https://github.com/yourusername/Captura.git](https://github.com/yourusername/Captura.git)
cd Captura
./gradlew assembleDebug
Install the generated APK from app/build/outputs/apk/debug/app-debug.apk.

📁 Storage Paths
All captured media is indexed immediately by Android's media scanner:

Photos (.jpg): /storage/emulated/0/DCIM/Captura/yyyyMMdd_HHmmss.jpg

Videos (.mp4): /storage/emulated/0/DCIM/Captura/yyyyMMdd_HHmmss.mp4

Audio (.m4a): /storage/emulated/0/Recordings/Captura/yyyyMMdd_HHmmss.m4a

⚠️ Disclaimer
This tool is intended for rapid documentation, personal productivity, workshops, and authorized educational environments. Users are responsible for complying with local laws and privacy regulations regarding audio and video recording.

📄 License
Distributed under the MIT License. See LICENSE for more information.
