# Native SRT dependency placement

Place prebuilt `libsrt.so` files in ABI-specific folders for runtime loading by `SrtTransportNode`:

- `app/src/main/jniLibs/arm64-v8a/libsrt.so`
- `app/src/main/jniLibs/armeabi-v7a/libsrt.so` (optional)
- `app/src/main/jniLibs/x86_64/libsrt.so` (optional emulator/dev)

The native sender currently attempts to load `libsrt.so` and `libsrt.so.1` via `dlopen`.
If not found, Start Stream faults with a detailed dependency error.
