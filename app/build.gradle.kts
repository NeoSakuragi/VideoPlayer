plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videoplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.videoplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            pickFirsts += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md"
            )
        }
    }

    androidResources {
        noCompress += listOf("zip")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("dev.jdtech.mpv:libmpv:0.5.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
}
