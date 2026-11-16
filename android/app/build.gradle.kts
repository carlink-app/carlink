plugins {
    id("com.android.application")
}

android {
    namespace = "app.tslink"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tslink"
        minSdk = 30
        targetSdk = 36
        // Shizuku replaces a running engine daemon only when its version changes:
        // tie it to the build so an updated app never talks to an old engine.
        buildConfigField("int", "ENGINE_VERSION", "${(System.currentTimeMillis() / 1000).toInt()}")
        versionCode = 1
        versionName = "0.2.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging {
        // The engine runs via app_process and loads the native core by path,
        // so the .so must be extracted to disk.
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests.
    testImplementation("org.json:json:20260814")
}
