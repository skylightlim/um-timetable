import java.io.File
import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android fails the build.
    id("com.android.application")
}

android {
    namespace = "com.skylightlim.umtimetable"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.skylightlim.umtimetable"
        // 31 (Android 12) is the floor for the widget APIs used to lay out the
        // week grid: setViewLayoutHeight/Margin and responsive size mappings.
        minSdk = 31
        targetSdk = 37
        // The release's tag is "v" + versionCode ("v3"): the in-app updater reads it from there.
        versionCode = 7
        versionName = "1.5.1-beta.1"
    }

    // The release key lives outside the project and is never committed: ~/.android/um-timetable-release.properties
    // names its keystore, alias and passwords. Every build is signed with it when it's there, so debug,
    // release and benchmark builds install over one another and keep the app's data; without it they
    // fall back to this computer's debug key (fine for trying the code, not for publishing).
    val releaseKey = File(System.getProperty("user.home"), ".android/um-timetable-release.properties")
        .takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = File(releaseKey.getProperty("storeFile"))
            storePassword = releaseKey.getProperty("storePassword")
            keyAlias = releaseKey.getProperty("keyAlias")
            keyPassword = releaseKey.getProperty("keyPassword")
        }
    }
    val signing = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
    buildTypes {
        getByName("debug") {
            signingConfig = signing
        }
        getByName("release") {
            // R8: shrinks and optimises the code, drops unused resources. Android also compiles a
            // release build ahead of time, which it never does for a debuggable one.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signing
        }
        // The release build plus the debug-only tools (DebugRenderReceiver's timings), for measuring speed.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
            // The timetable isn't built in: a new or cleared app starts empty and imports it from MAYA.
        }
        getByName("debug").java.srcDirs("src/debug/kotlin")
        getByName("benchmark") {
            // Built-in Kotlin only compiles kotlin source dirs; a java.srcDirs entry is ignored here.
            kotlin.srcDirs("src/debug/kotlin")
            manifest.srcFile("src/debug/AndroidManifest.xml")
        }
    }
}

dependencies {
    // JVM tests of the rules that read names (attendance activities, MAYA, TimeEdit), in src/test:
    // ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in JVM tests; the real one runs TimeEditTimetable's JSON.
    testImplementation("org.json:json:20240303")
}
