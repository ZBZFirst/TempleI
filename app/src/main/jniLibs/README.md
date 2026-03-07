# Native FFmpeg/SRT dependency placement

Screen 2 transport now targets an FFmpeg-backed runtime path.
Legacy TS mux/SRT node fallback has been removed.

Place prebuilt native runtime libraries in ABI-specific folders for runtime probing:

- `app/src/main/jniLibs/arm64-v8a/libsrt.so`
- `app/src/main/jniLibs/arm64-v8a/libavcodec.so`
- `app/src/main/jniLibs/arm64-v8a/libavformat.so`
- `app/src/main/jniLibs/arm64-v8a/libavutil.so`
- `app/src/main/jniLibs/arm64-v8a/libswresample.so`

Optional additional ABI folders can be added as needed for emulator/dev targets.

## FFmpeg + libsrt requirements (Android)

For this use case, FFmpeg must be built with SRT protocol support and linked against libsrt.

Typical FFmpeg configure requirements include:

- `--enable-network`
- `--enable-protocol=srt`
- `--enable-libsrt`
- `--enable-muxer=mpegts`

Practical checklist:

1. Build `libsrt` for Android NDK ABI(s).
2. Build FFmpeg against that `libsrt`.
3. Copy resulting runtime `.so` libraries into `app/src/main/jniLibs/<abi>/`.
4. Install APK and verify Screen 2 runtime status no longer reports missing FFmpeg artifacts.

## Build helper tasks

To build and install arm64 sender/runtime libraries from source:

```bash
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/<version>
./gradlew :app:buildSrtArm64
./gradlew :app:buildFfmpegArm64
```

This project now runs `installSrtArm64` + `verifySrtDependency` and
`installFfmpegArm64` + `verifyFfmpegDependency` during `preBuild`.
If libraries are missing, Gradle attempts to build/install automatically, then fails fast with instructions if prerequisites are unavailable.
