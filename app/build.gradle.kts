import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.templei"
    compileSdk = 34

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        ndk {
            // Initial native rollout targets physical Android test devices first.
            abiFilters += listOf("arm64-v8a")
        }

        applicationId = "com.example.templei"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Keep native libs directly loadable for runtime dlopen checks.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.play.services.maps)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}


val srtOutputArm64 = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libsrt.so")

val buildSrtArm64 by tasks.registering(Exec::class) {
    group = "native dependencies"
    description = "Build libsrt.so for arm64-v8a and copy it into app/src/main/jniLibs/arm64-v8a"
    workingDir = rootDir

    doFirst {
        val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME").orNull
            ?: throw GradleException("ANDROID_NDK_HOME is not set for Gradle.")
        logger.lifecycle("buildSrtArm64 using ANDROID_NDK_HOME=$ndkHome")
        commandLine(
            "C:/Program Files/Git/bin/bash.exe",
            "scripts/build-libsrt-android.sh",
            "arm64-v8a",
            ndkHome,
        )
    }

    onlyIf {
        !srtOutputArm64.asFile.exists()
    }
}

val installSrtArm64 by tasks.registering {
    group = "native dependencies"
    description = "Install sender-side libsrt.so for arm64-v8a when missing"
    dependsOn(buildSrtArm64)
    doLast {
        if (srtOutputArm64.asFile.exists()) {
            logger.lifecycle("libsrt.so ready at ${srtOutputArm64.asFile}")
        }
    }
}

val verifySrtDependency by tasks.registering {
    group = "verification"
    description = "Verify sender-side libsrt.so is packaged for the enabled ABI"
    doLast {
        if (!srtOutputArm64.asFile.exists()) {
            throw GradleException(
                "Missing SRT sender dependency: ${srtOutputArm64.asFile}. " +
                        "Run './gradlew :app:buildSrtArm64' (requires ANDROID_NDK_HOME + network) " +
                        "or provide a prebuilt libsrt.so for arm64-v8a."
            )
        }
    }
}

val ffmpegOutputDirArm64 = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
val ffmpegHeadersDir = layout.projectDirectory.dir("src/main/cpp/third_party/ffmpeg/include")
val ffmpegRequiredLibs = listOf(
    "libavcodec.so",
    "libavformat.so",
    "libavutil.so",
    "libswresample.so",
)
val ffmpegRequiredHeaders = listOf(
    "libavcodec/avcodec.h",
    "libavformat/avformat.h",
    "libavutil/avutil.h",
    "libswresample/swresample.h",
)

val buildFfmpegArm64 by tasks.registering(Exec::class) {
    group = "native dependencies"
    description = "Build FFmpeg runtime libs for arm64-v8a and copy them into app/src/main/jniLibs/arm64-v8a"
    workingDir = rootDir

    doFirst {
        val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME").orNull
            ?: throw GradleException("ANDROID_NDK_HOME is not set for Gradle.")
        logger.lifecycle("buildFfmpegArm64 using ANDROID_NDK_HOME=$ndkHome")
        commandLine(
            "C:/Program Files/Git/bin/bash.exe",
            "scripts/build-ffmpeg-android.sh",
            "arm64-v8a",
            ndkHome,
        )
    }

    onlyIf {
        ffmpegRequiredLibs.any { !ffmpegOutputDirArm64.file(it).asFile.exists() } ||
                ffmpegRequiredHeaders.any { !ffmpegHeadersDir.file(it).asFile.exists() }
    }
}

val installFfmpegArm64 by tasks.registering {
    group = "native dependencies"
    description = "Install FFmpeg runtime libs for arm64-v8a when missing"
    dependsOn(buildFfmpegArm64)
    doLast {
        logger.lifecycle("FFmpeg arm64 runtime check completed in ${ffmpegOutputDirArm64.asFile}")
    }
}

val verifyFfmpegDependency by tasks.registering {
    group = "verification"
    description = "Verify FFmpeg runtime libs are packaged for the enabled ABI"
    doLast {
        val missingLibs = ffmpegRequiredLibs.filterNot { ffmpegOutputDirArm64.file(it).asFile.exists() }
        val missingHeaders = ffmpegRequiredHeaders.filterNot { ffmpegHeadersDir.file(it).asFile.exists() }
        if (missingLibs.isNotEmpty() || missingHeaders.isNotEmpty()) {
            throw GradleException(
                "Missing FFmpeg native dependencies for arm64-v8a. " +
                        "libs=[${missingLibs.joinToString()}], headers=[${missingHeaders.joinToString()}]. " +
                        "Run './gradlew :app:buildFfmpegArm64' (requires ANDROID_NDK_HOME + network) " +
                        "or provide prebuilt FFmpeg runtime libraries under app/src/main/jniLibs/arm64-v8a/ " +
                        "and headers under app/src/main/cpp/third_party/ffmpeg/include/."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(installSrtArm64)
    dependsOn(verifySrtDependency)
    dependsOn(installFfmpegArm64)
    dependsOn(verifyFfmpegDependency)
}
