import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
}

// (added) Release signing details are read from keystore.properties in the
// project root. That file holds passwords, so it must not go into git.
val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")

    if (propsFile.exists()) {
        FileInputStream(propsFile).use { load(it) }
    }
}

// ABIs to build. ARM always; x86_64 is added automatically as soon as its
// prebuilt libtorrent exists at app/src/main/jniLibs/x86_64/ - until then it
// is skipped, so the build can never fail for a missing x86_64 library.
val appAbis = mutableListOf("arm64-v8a", "armeabi-v7a").apply {
    if (file("src/main/jniLibs/x86_64/libtorrent-rasterbar.so").exists()) {
        add("x86_64")
    }
}

android {
    namespace = "com.example.torrentor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.torrentor"
        minSdk = 24
        targetSdk = 36

        // (changed) Raise versionCode with every release you install over an
        // older one. Android refuses an update whose versionCode is lower.
        versionCode = 254
        versionName = "3.33"

        ndk {
            abiFilters += appAbis
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters(*appAbis.toTypedArray())
            }
        }
    }

    // (added) The key used to sign the release APK.
    signingConfigs {
        create("release") {
            val storePath = keystoreProps.getProperty("storeFile")

            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // (added) Minify stays off: the app calls native code by name (JNI), and
    // shrinking would rename those classes.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false

            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // (added) Pure-Java, no native code - used only to generate the Web
    // UI's self-signed HTTPS certificate (WebUiServer.kt). Does not
    // touch anything else in the app.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
