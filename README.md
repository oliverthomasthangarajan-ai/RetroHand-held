# RetroHandheld (Android Multi-System Emulator)

An Android Studio project (Kotlin + Jetpack Compose + C++ JNI) that runs **GB, GBC, GBA, NDS, and 3DS** games on Android using **libretro** cores, with **Save/Load State** and **NDS/3DS touch-screen** support.

## Option 1: Build the APK in the Cloud from Your Phone (No PC Needed)

1. Unzip `RetroHandheldEmulator.zip` on your phone.
2. Create a free account on [GitHub](https://github.com/) and create a new repository.
3. Upload the contents of `RetroHandheldEmulator` (including the `.github` folder) to your repository.
4. Open the **Actions** tab in your GitHub repository and wait for **Build Android APK** to finish (it automatically downloads the 4 ARM64 emulator cores and compiles your APK).
5. Tap the completed workflow run, download **RetroHandheld-debug-apk**, unzip it, and tap `app-debug.apk` to install it on your phone.

## Option 2: Build on a Computer with Android Studio

1. Open the `RetroHandheldEmulator` folder in **Android Studio**.
2. Download and extract the 4 ARM64 `.so` cores from the [Libretro Android ARM64 Buildbot](https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/) into `app/src/main/jniLibs/arm64-v8a/` (make sure each filename starts with `lib`).
3. Connect your phone via USB and click **Run ▶**.
