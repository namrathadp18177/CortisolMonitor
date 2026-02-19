plugins {
    alias(libs.plugins.android.application)
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

    // USB Serial Library
    implementation("com.github.felHR85:UsbSerial:6.1.0")

    // Newer AndroidX libraries
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

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


}

