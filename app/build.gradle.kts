import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. The key is kept outside the project (and outside GitHub) in ~/ObsStarGet-signing,
// created once by make_release_key.command. build_release.command asks for its password and passes it
// in the environment variable OBS_KEY_PASSWORD, so the password is never written to a file.
// Without the key or the password the release build is left unsigned and build_release.command stops.
val releaseKeyStore = File(System.getProperty("user.home"), "ObsStarGet-signing/obsstarget-release.jks")
val releaseKeyPassword: String? = providers.environmentVariable("OBS_KEY_PASSWORD").orNull

android {
    namespace = "cl.astro.observability"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.astro.observability"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"
    }

    signingConfigs {
        if (releaseKeyStore.isFile && !releaseKeyPassword.isNullOrEmpty()) {
            create("release") {
                storeFile = releaseKeyStore
                storePassword = releaseKeyPassword
                keyAlias = "obsstarget"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            // Development builds (Android Studio's Run, install_on_phone.command) install next to the
            // published app as "ObsStarGet dev" instead of clashing with its release signature.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The astronomy engine is plain Kotlin/JVM, so the unit tests run on the desktop JVM.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // No AndroidX/Compose: the UI uses only the Android framework, so the app has no runtime dependencies
    // besides the Kotlin standard library.
    testImplementation("junit:junit:4.13.2")
}
