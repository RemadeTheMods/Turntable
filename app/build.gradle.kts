plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.turntable.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.turntable.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    // Used only to safely EMBED lyrics fetched from LRCLIB back into a file's
    // own tags (ID3 for MP3, Vorbis comment for FLAC, atom for M4A). A proven
    // library is used here rather than hand-written binary tag writers,
    // since a bug in hand-rolled format code risks corrupting music files.
    implementation("net.jthink:jaudiotagger:3.0.1")
}
