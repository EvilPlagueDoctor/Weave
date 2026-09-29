import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Normal prototype builds remain debug-signed for easy local installation.
// F-Droid can pass -PweaveFdroidBuild=true so the release APK is left unsigned
// for F-Droid's own signing pipeline.
val weaveFdroidBuild = providers.gradleProperty("weaveFdroidBuild")
    .map { it.equals("true", ignoreCase = true) || it == "1" || it.isEmpty() }
    .orElse(false)

android {
    namespace = "app.weave"
    compileSdk { version = release(36) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "app.weave"
        minSdk = 29
        targetSdk = 36
        versionCode = 44
        versionName = "0.11.6-startup-tips"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildTypes {
        getByName("release") {
            // Prototype performance builds stay easy to install while iterating.
            // F-Droid builds deliberately omit the local signing configuration.
            isMinifyEnabled = false
            if (!weaveFdroidBuild.get()) {
                signingConfig = signingConfigs.getByName("debug")
            } else {
                signingConfig = null
            }
            ndk { debugSymbolLevel = "none" }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { it.outputFileName.set("Weave-${variant.name}.apk") }
    }
}

// The daemon remains a normal Rust cdylib. Weave simply packages the Android build and talks to
// it through NativeDaemonBridge instead of requiring a second APK/process.
val rustManifest = rootProject.file("native/veilknit-daemon/Cargo.toml")
val rustOutput = layout.projectDirectory.dir("src/main/jniLibs")

fun Exec.configureCargoNdk(release: Boolean) {
    group = "rust"
    description = "Build the embedded VeilKnit Rust core for Android"
    val rustRoot = rootProject.file("native/veilknit-daemon")
    workingDir(rustRoot)

    if (release) {
        val separator = "\u001f"
        val flags = listOf(
            "--remap-path-prefix=${rootProject.projectDir.absolutePath}=/_/weave/android",
            "--remap-path-prefix=${System.getProperty("user.home")}=/_/home",
            "-C", "debuginfo=0",
            "-C", "strip=symbols"
        )
        environment("CARGO_ENCODED_RUSTFLAGS", flags.joinToString(separator))
        environment("CARGO_INCREMENTAL", "0")
    }

    val args = mutableListOf(
        "cargo", "ndk",
        "--platform", "29",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", rustOutput.asFile.absolutePath,
        "build", "--lib"
    )
    if (release) args.add("--release")
    commandLine(args)

    inputs.dir(rootProject.file("native/veilknit-daemon/src"))
    inputs.file(rustManifest)
    outputs.dir(rustOutput)
}

val buildRustDebug = tasks.register<Exec>("buildRustDebug") {
    configureCargoNdk(release = false)
}
val buildRustRelease = tasks.register<Exec>("buildRustRelease") {
    configureCargoNdk(release = true)
}

afterEvaluate {
    tasks.matching { it.name == "preDebugBuild" }
        .configureEach { dependsOn(buildRustDebug) }
    tasks.matching { it.name == "preReleaseBuild" }
        .configureEach { dependsOn(buildRustRelease) }
}
