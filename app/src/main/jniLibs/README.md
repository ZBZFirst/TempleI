# Native SRT dependency placement

If this app publishes SRT itself (Android app is the sender), you **must** ship SRT native runtime support.
Android `MediaMuxer` does not provide SRT transport.

Place prebuilt `libsrt.so` files in ABI-specific folders for runtime loading by `SrtTransportNode`:

- `app/src/main/jniLibs/arm64-v8a/libsrt.so`
- `app/src/main/jniLibs/armeabi-v7a/libsrt.so` (optional)
- `app/src/main/jniLibs/x86_64/libsrt.so` (optional emulator/dev)

The native sender attempts to load `libsrt.so` and `libsrt.so.1` via `dlopen`.
If not found, Start Stream faults with a dependency error.

## FFmpeg + libsrt note (Android)

For this use case, FFmpeg is valid **only when built with libsrt enabled** and when the final APK still contains a loadable `libsrt.so` for the target ABI.

Typical FFmpeg configure requirements include:

- `--enable-network`
- `--enable-protocol=srt`
- `--enable-libsrt`

Practical checklist:

1. Build `libsrt` for Android NDK ABI(s).
2. Build FFmpeg against that `libsrt`.
3. Copy resulting `libsrt.so` into `app/src/main/jniLibs/<abi>/`.
4. Install APK and verify Screen 2 runtime status no longer reports missing shared library.


## Build helper task (sender mode)

To build and install the required arm64 sender library from source:

```bash
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/<version>
./gradlew :app:buildSrtArm64
```

This project now runs `installSrtArm64` + `verifySrtDependency` during `preBuild`.
If `libsrt.so` is missing, it first attempts to build/install automatically, then fails fast with instructions if prerequisites are unavailable.
