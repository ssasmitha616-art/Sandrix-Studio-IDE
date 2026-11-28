# Sandrix Studio IDE 🚀

**Build, Compile, Debug, and Sign Android Apps Directly on Your Phone — No PC Required.**

Sandrix Studio IDE is a lightweight, full-featured on-device Integrated Development Environment (IDE) designed specifically to empower developers on low-spec Android devices. Built entirely on a **2GB RAM Nokia 3.4**, this IDE proves that you don't need expensive hardware to create production-ready Android applications.

> 💡 **Fun Fact:** Sandrix Studio IDE is **100% self-hosting** — its own source code can be edited, compiled, and built directly inside Sandrix Studio IDE on a phone!

---

## ✨ Key Features

### 🛠️ Real Android Build Pipeline
* **Full Compilation:** Powered by **AAPT2** (resource linking), **ECJ** (Java compilation), **D8** (dexing), **R8** (code shrinking & obfuscation), zipalign, and APK signing.
* **Play Store Ready:** Generate both **APK** and **Android App Bundle (AAB)** files directly on your device.
* **Background Builds:** Builds run smoothly in the background with live progress notifications.
* **Flexible Configuration:** Supports both lightweight `build.sd` files and standard `build.gradle` configurations.

### 💻 Smart Code Editor & UI Tools
* **Multi-Language Support:** Syntax highlighting for **Java**, **XML**, and `build.sd` with project indexing and IntelliSense code completion.
* **Live XML Layout Preview:** Design your UI in XML and view real-time renders side-by-side in Split Mode.
* **Layout Inspector:** Inspect view hierarchies and properties visually.
* **Resource & Manifest Managers:** Easily manage strings, colors, drawables, assets, and `AndroidManifest.xml`.

### 📦 Dependency & Signing Management
* **Maven & AAR Support:** Add external libraries (AppCompat, Material Design, etc.) and custom Maven repositories effortlessly.
* **Built-in Keystore Generator:** Create signing keys and sign release builds without external tools.
* **APK Analyzer:** Inspect the contents, dex files, and resources inside any built APK.

### 🐞 Advanced Debugging & Git Integration
* **On-Device Debugger:** Set breakpoints, step over/into/out, and inspect variables and call stacks.
* **Live Logcat & Problems Panel:** Filter real-time device logs and jump straight to compilation errors.
* **Full Git Client:** Clone, commit, branch, checkout, merge, stash, tag, diff, and push/pull directly with GitHub.

---

## 📱 System Requirements
* **OS:** Android 7.0 (Nougat) or newer
* **Architecture:** 64-bit ARM (`arm64-v8a`)
* **RAM:** Optimized for low-spec devices (**2GB RAM** or higher)

---

## 📥 Installation

1. Visit the [Official Website](https://github.io) or go to the [Releases](https://github.com) page.
2. Download the latest `Sandrix-Studio-IDE.apk`.
3. Open the downloaded file and allow **Install unknown apps** if prompted by Android.
4. Launch the app, grant storage permissions (projects are saved in `/App_Project`), and tap **New project**.

---

## 🎥 Video Demo & Tutorials
Watch Sandrix Studio IDE in action on our official YouTube channel:
* 📺 [Build an Android App on Your Phone (No PC) | Sandrix Studio IDE](https://youtube.com)

---

## 🤝 Contributing & Feedback
Found a bug, have a feature request (like Kotlin support!), or want to contribute?
* Open an issue on [GitHub Issues](https://github.com).
* Star ⭐ this repository to support the project and help more mobile developers discover it!

---

## 👨‍💻 Author
Created with passion by **Sandaru Sasmitha** ([Sandrix Developers PVT (LTD)](https://github.com)).

*© 2026 Sandrix Studio. All rights reserved.*
