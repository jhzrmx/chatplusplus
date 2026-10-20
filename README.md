# Chat++

Offline LLM chat for Android. Runs upstream llama.cpp's `llama-server` on the device and shows its
web UI in a WebView.

- Package: `com.jhz.llamachatplusplus`
- Min SDK 28 (Android 9), target SDK 33, Material dark theme
- Plain `Activity`, Java 7 syntax, **no** AndroidX/AppCompat, no Gradle dependencies

## Requirements

| Tool | Version |
|------|---------|
| Android Studio | Electric Eel (2022.1) or newer |
| JDK | 11 or 17 (Android Studio's bundled JDK is fine) |
| Android SDK | Platform 33 (install via SDK Manager) |
| Android NDK | r25 or newer (SDK Manager > SDK Tools > NDK) |
| CMake | 3.14+ (system install, or SDK Manager > SDK Tools > CMake) |
| git | any |

Build the native part on Linux or macOS (or WSL on Windows).

## Setup

### 1. Build the native llama-server

```bash
export ANDROID_NDK=$HOME/Android/Sdk/ndk/<version>     # your NDK path
tools/build-llama-server.sh
```

This clones llama.cpp into `tools/llama.cpp`, cross-compiles `llama-server`, and copies it to
`app/src/main/jniLibs/arm64-v8a/libllama_server.so`. The app runs it from the native library dir.

Options (environment variables):

| Variable | Default | Purpose |
|----------|---------|---------|
| `ABIS` | `arm64-v8a` | Space-separated ABIs, e.g. `"arm64-v8a x86_64"` (x86_64 for emulators) |
| `ANDROID_PLATFORM` | `android-28` | Keep at 28 or higher (matches minSdk) |
| `LLAMA_REF` | `master` | llama.cpp branch or tag to build |
| `CPU_ARM_ARCH` | unset | e.g. `armv8.2-a+dotprod`: faster on newer phones, crashes (SIGILL) on old CPUs |

To update llama.cpp later, delete `tools/llama.cpp` and `tools/build` and run the script again.

### 2. Open and run the app

1. In Android Studio: **File > Open** and select this folder.
2. If prompted, accept the Gradle sync. The wrapper jar is not included; Android Studio uses the
   version in `gradle/wrapper/gradle-wrapper.properties` (Gradle 7.5). From the command line, run
   `gradle wrapper --gradle-version 7.5` once to generate `gradlew`.
3. Make sure `local.properties` has `sdk.dir=...` (Android Studio creates it).
4. Run on a physical arm64 device (or an x86_64 emulator if you built that ABI).

If the app shows "Native server binary not found", step 1 was skipped or built for a different ABI
than your device.

### 3. Use it

1. **Models** tab: tap **Add model (.gguf)**, pick a file with the system file picker. It is copied to
   `Android/data/com.jhz.llamachatplusplus/files/models/`.
2. Tap **Load**. Status shows "Loading..." and then the server address.
3. **Server** tab: the llama.cpp web UI opens automatically once the model is ready.
4. **Settings** tab: serve on the local network (0.0.0.0) or localhost only, port, context size, CPU
   threads, optional API key. Changes apply the next time a model is loaded.

Tips:
- Smaller quantizations (Q4_K_M, a few GB or less) and a lower context size avoid out-of-memory kills.
- The **Log** button on the Models tab shows `llama-server` output, which is the first place to look
  when loading fails.
- On Android 13+, allow notifications so the "server running" notification (with a Stop button) is visible.
- If you expose the server on the network, set an API key.

## How it works

- `MainActivity`: three tabs (Models / Server / Settings), model import via Storage Access Framework
  (`ACTION_OPEN_DOCUMENT`) with `.gguf` extension and `GGUF` header checks.
- `ServerService`: foreground service that runs `libllama_server.so` as a child process, streams its
  log, polls `/health`, and publishes state to the UI. Load starts the process, Unload kills it.
- `Prefs`: SharedPreferences wrapper for settings.

A user-chosen model folder (`ACTION_OPEN_DOCUMENT_TREE`) is not used because the native server needs a
real file path and SAF only provides `content://` URIs, so models are copied into app storage.

## Roadmap

- Hugging Face download as another model source writing into the same models folder.
