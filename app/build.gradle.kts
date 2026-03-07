import java.io.File

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
    kotlinOptions {
        jvmTarget = "11"
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
    commandLine(
        "bash",
        File(rootDir, "scripts/build-libsrt-android.sh").absolutePath,
        "arm64-v8a",
    )
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

tasks.named("preBuild") {
    dependsOn(installSrtArm64)
    dependsOn(verifySrtDependency)
}
