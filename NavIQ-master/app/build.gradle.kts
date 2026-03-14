plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.navigation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.navigation"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        ndk {
            // either of these two syntaxes works:
            abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            // or, using the '+=' operator:
            // abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        // Properly configure the release build type
        release {
            isMinifyEnabled = false


            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val cameraxVersion = "1.2.0" // or latest stable
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.android.volley:volley:1.2.1")
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
    implementation("androidx.activity:activity-compose:1.6.1")
    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")


    // Jetpack Compose
    implementation("androidx.compose.ui:ui:1.4.0")
    implementation("androidx.compose.material:material:1.4.0")
    implementation("androidx.compose.material3:material3:1.1.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.4.0")

    // Optional navigation support
    //implementation("androidx.camera:camera-camera2:1.1.0")
    //implementation("androidx.camera:camera-lifecycle:1.1.0")
    //implementation("androidx.camera:camera-view:1.0.0-alpha04")
    //implementation("org.opencv:opencv:4.5.1")
    implementation("androidx.navigation:navigation-compose:2.5.3")

    implementation("org.java-websocket:Java-WebSocket:1.5.3")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.mlkit:face-detection:16.0.5")
    implementation(project(":openCVLibrary"))

    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion") // <--- needed for PreviewView!
    implementation("androidx.camera:camera-extensions:$cameraxVersion")
    implementation("com.google.android.material:material:1.11.0")




    implementation ("com.squareup.okhttp3:okhttp:4.10.0")

}

