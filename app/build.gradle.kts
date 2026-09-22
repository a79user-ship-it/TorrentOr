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

android {
    namespace = "com.example.torrentor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.torrentor"
        minSdk = 24
        targetSdk = 36

        // (changed) Raise versionCode with every release you install over an
        // older one. Android refuses an update whose versionCode is lower.
        versionCode = 2
        versionName = "1.1"

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "armeabi-v7a"
            )
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters(
                    "arm64-v8a",
                    "armeabi-v7a"
                )
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
}
