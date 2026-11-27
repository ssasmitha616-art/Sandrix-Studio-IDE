# Sandrix Studio IDE

### Build Android apps on your phone. No PC needed.

Sandrix Studio is a full Android IDE that runs on your Android device. Write Java and XML, add libraries, build an APK, and install it, all from the same app.

<!-- Add screenshots here, for example:
<p align="center">
  <img src="screenshots/editor.png" width="30%">
  <img src="screenshots/build.png" width="30%">
  <img src="screenshots/preview.png" width="30%">
</p>
-->

---

## Download

Go to the **[Releases](../../releases)** page and download the latest `Sandrix-Studio-x.x.x.apk`.

## Install

1. Open the downloaded APK on your phone.
2. If Android asks, allow **Install unknown apps** for your browser or file manager.
3. Tap **Install**, then open **Sandrix Studio IDE**.

**You need:** Android 7.0 or newer on a 64-bit (arm64) phone, and an internet connection the first time you download libraries.

---

## What you can do

**Write code**
- Code editor for Java, XML and `build.sd` files, with syntax highlighting
- Multiple tabs, project file explorer, light and dark themes
- Live layout preview next to your code (Code / Split / Preview)

**Build and run**
- One tap to compile, package, sign and install your app
- Add libraries from Maven (AppCompat, Material and more) with the Dependency Manager
- Live build log, error list with jump-to-error, and Logcat viewer
- Build App Bundles (AAB) and shrink apps with R8

**Manage your project**
- Visual manager for strings, colors, drawables, images and assets
- Manifest editor
- Create and manage signing keys for release builds
- APK analyzer to see what is inside an APK

**Debug**
- Breakpoints, step over / into / out
- Inspect variables and the call stack

**Git built in**
- Clone, commit, branch, merge, stash, tag, push and pull
- See file changes with diff view

---

## Getting started

### 1. Allow storage access
On first launch, tap **Connect /storage/emulated/0/App_Project** and grant file access. Your projects are saved in the `App_Project` folder on your phone, so you can find them in any file manager. If you skip this, the app uses a private workspace instead.

### 2. Create a project
Tap **New project**, then enter:
- **Project name**, for example `MyApp`
- **Package name**, for example `com.yourname.myapp`
- **Template**: *Hello Activity* (a simple starter) or *Counter App* (a small working example)

### 3. Build and run
Tap **Build and Run APK**. Sandrix compiles your code, links resources, signs the APK and offers to install it. Progress shows in the **Build** tab, and any mistakes show in **Problems**.

### 4. Install and test
When the build finishes, tap **Install built APK**. Use the **Logcat** tab to see your app's logs.

---

## Project settings: `build.sd`

Every project has a `build.sd` file where you set your app's version, SDK levels and libraries:

```
compileSdk = 36
minSdk = 24
targetSdk = 34
versionCode = 1
versionName = "1.0"
javaVersion = 17

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
```

To add a library, add an `implementation("group:artifact:version")` line (or use the Dependency Manager) and build again. Sandrix downloads it for you. Standard Android `build.gradle` syntax also works.

---

## Using Git

Open the **Git** panel from the toolbar.

- **Clone:** paste an HTTPS repository URL
- **Push / Pull:** set your remote (origin) and enter your GitHub username and a **personal access token** as the password (create one in GitHub → Settings → Developer settings → Personal access tokens)

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Can't install the APK | Allow **Install unknown apps** for the app you downloaded it with |
| Build fails with "toolchain" errors | Open **Toolchain status** and check that all components show as ready |
| Dependencies don't download | Check your internet connection and the library name and version in `build.sd` |
| Projects not showing in your file manager | Make sure you granted storage access on first launch |
| The app closes during a big build | Close other apps to free memory, then build again |
| Can't push to GitHub | Use a personal access token, not your GitHub password |

---

## Permissions explained

| Permission | Why the app needs it |
|---|---|
| Internet | Download libraries and use Git |
| All files access | Save projects to `App_Project` so you can reach them easily |
| Install packages | Install the APKs you build |
| Foreground service | Keep builds running with a progress notification |

Sandrix Studio does not collect or send your code anywhere. Everything is built on your device.

---

## Feedback

Found a bug or have an idea? Open an **[Issue](../../issues)**. Please include your Android version, phone model and the build log.

## License

Copyright (c) 2026 Sandrix Developers PVT (LTD). All rights reserved.

The source code is public for viewing only. You may install and use the official app, but you may not modify, copy, redistribute or republish the code or the app. See the [LICENSE](LICENSE) file for details.
