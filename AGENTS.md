# AGENTS.md

Project guidance for contributors working in this repository.

## Scope
This file applies to the entire repository rooted at `/workspace/TempleI`.

## Current project state
- The app is currently XML-first and screen-oriented.
- `activity_main.xml` acts as the launcher menu.
- `activity_screen1.xml` through `activity_screen4.xml` are shell UIs intended for iterative build-out.
- Activity classes are intentionally light and should remain easy to read while screens are scaffolded.

## Working conventions
- Prefer small, incremental commits with clear messages.
- Keep UI strings in `app/src/main/res/values/strings.xml` (avoid hardcoded text when practical).
- Add short XML/Kotlin comments for placeholders and TODO sections so shell code is self-explanatory.
- If introducing new activities, ensure they are declared in `AndroidManifest.xml`.
- Preserve existing package namespace: `com.example.templei`.

## Validation guidance
- Run Gradle checks where environment permits.
- If Android SDK is unavailable, still perform static review and clearly report the limitation.

## Obsidian file index (wikilinks)
Use the entries below for Obsidian graph/linking. Paths are repo-relative and intentionally nested.

- [[AGENTS.md]]
- [[build.gradle.kts]]
- [[settings.gradle.kts]]
- [[gradle.properties]]
- [[gradlew]]
- [[gradlew.bat]]
- [[gradle/libs.versions.toml]]
- [[gradle/wrapper/gradle-wrapper.properties]]
- [[gradle/wrapper/gradle-wrapper.jar]]
- [[app/build.gradle.kts]]
- [[app/proguard-rules.pro]]
- [[app/src/main/AndroidManifest.xml]]
- [[app/src/main/java/com/example/templei/MainActivity.kt]]
- [[app/src/main/java/com/example/templei/Screen1Activity.kt]]
- [[app/src/main/java/com/example/templei/Screen2Activity.kt]]
- [[app/src/main/java/com/example/templei/Screen3Activity.kt]]
- [[app/src/main/java/com/example/templei/Screen4Activity.kt]]
- [[app/src/main/java/com/example/templei/feature/camera/CameraFeature.kt]]
- [[app/src/main/java/com/example/templei/feature/export/ExportFeature.kt]]
- [[app/src/main/java/com/example/templei/ui/components/PulseButton.kt]]
- [[app/src/main/java/com/example/templei/ui/components/UiPaletteBar.kt]]
- [[app/src/main/java/com/example/templei/ui/navigation/NavGraph.kt]]
- [[app/src/main/java/com/example/templei/ui/navigation/Routes.kt]]
- [[app/src/main/java/com/example/templei/ui/navigation/TopNavigation.kt]]
- [[app/src/main/java/com/example/templei/ui/state/HomeEvent.kt]]
- [[app/src/main/java/com/example/templei/ui/state/HomeUiState.kt]]
- [[app/src/main/java/com/example/templei/ui/theme/Color.kt]]
- [[app/src/main/java/com/example/templei/ui/theme/Theme.kt]]
- [[app/src/main/java/com/example/templei/ui/theme/Type.kt]]
- [[app/src/main/res/layout/activity_main.xml]]
- [[app/src/main/res/layout/activity_screen1.xml]]
- [[app/src/main/res/layout/activity_screen2.xml]]
- [[app/src/main/res/layout/activity_screen3.xml]]
- [[app/src/main/res/layout/activity_screen4.xml]]
- [[app/src/main/res/layout/view_top_navigation.xml]]
- [[app/src/main/res/values/strings.xml]]
- [[app/src/main/res/values/colors.xml]]
- [[app/src/main/res/values/themes.xml]]
- [[app/src/main/res/xml/backup_rules.xml]]
- [[app/src/main/res/xml/data_extraction_rules.xml]]
- [[app/src/main/res/drawable/ic_launcher_background.xml]]
- [[app/src/main/res/drawable/ic_launcher_foreground.xml]]
- [[app/src/main/res/mipmap-anydpi/ic_launcher.xml]]
- [[app/src/main/res/mipmap-anydpi/ic_launcher_round.xml]]
- [[app/src/main/res/mipmap-mdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-mdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-hdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-hdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-xhdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-xxhdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp]]
- [[app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp]]
- [[app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp]]
- [[app/src/test/java/com/example/templei/ExampleUnitTest.kt]]
- [[app/src/androidTest/java/com/example/templei/ExampleInstrumentedTest.kt]]
