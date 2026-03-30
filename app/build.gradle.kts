
plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}


android {
    namespace = "com.example.myapplication"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Combined buildFeatures (removed duplicate)
    buildFeatures {
        viewBinding = true
        // Add dataBinding if you're using it
        // dataBinding = true
    }

    // Asset directories configuration
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    // Add this to help with dependency conflicts
    configurations.all {
        resolutionStrategy {
            force("androidx.core:core:1.9.0")
            force("androidx.lifecycle:lifecycle-runtime:2.6.1")
            // Add other forced versions if needed
        }
    }
}




dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // GraphView dependency
    implementation(project(":GraphView-master"))
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")


    // USB Serial Library
    implementation("com.github.felHR85:UsbSerial:6.1.0")

    // Newer AndroidX libraries
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)


    // ML Kit QR scanning
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
// CameraX
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")
    implementation("com.google.guava:guava:32.1.3-android")
    implementation(platform("com.google.firebase:firebase-bom:34.10.0"))
    implementation("com.google.firebase:firebase-auth")

    configurations.all {
        resolutionStrategy {
            force("com.google.guava:guava:32.1.3-android")
            // your existing forced versions...
            force("androidx.core:core:1.9.0")
            force("androidx.lifecycle:lifecycle-runtime:2.6.1")
        }
    }



    // Kommunicate SDK
    implementation("io.kommunicate.sdk:kommunicateui:2.7.1") {
        exclude(group = "com.android.support")
    }

    // Required for Kommunicate if not already included in libs
    implementation("androidx.cardview:cardview:1.0.0")
    implementation(libs.androidx.room.common.jvm)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // Add Room dependencies
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)

    // Add Lifecycle dependencies (if not already included in your libs)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)

    // ONNX Runtime for Android
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:34.10.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-database")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")



}

