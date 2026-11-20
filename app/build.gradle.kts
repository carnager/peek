import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: ~/.local/android/release-keys/peek.properties (or the file named by
// PEEK_ANDROID_SIGNING_PROPERTIES), or the same keys as environment variables.
// Without them, release builds use the debug key.
val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = file(
    System.getenv("PEEK_ANDROID_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.local/android/release-keys/peek.properties"
)
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun signingValue(name: String): String? = System.getenv(name) ?: releaseSigningProperties.getProperty(name)

android {
    namespace = "io.github.carnager.peek"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.carnager.peek"
        minSdk = 30
        targetSdk = 36
        versionCode = 9
        versionName = "0.9.0"
    }

    signingConfigs {
        create("release") {
            signingValue("PEEK_ANDROID_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingValue("PEEK_ANDROID_STORE_PASSWORD")
            keyAlias = signingValue("PEEK_ANDROID_KEY_ALIAS")
            keyPassword = signingValue("PEEK_ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingValue("PEEK_ANDROID_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-gif:3.6.3")
    // Video thumbnails in the grid.
    implementation("io.coil-kt.coil3:coil-video:3.6.3")
    // Map tiles in the photo info (OpenStreetMap), cached on disk.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    // Uploads to trunk keep running when peek is in the background.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // PlayerSurface: video in Compose without a View that would swallow the pager's swipes.
    implementation("androidx.media3:media3-ui-compose:1.11.1")
    // Pinch zoom with tiled loading, so 50-megapixel photos stay sharp without eating memory.
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")
}
