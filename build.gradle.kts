// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://kommunicate.jfrog.io/artifactory/kommunicate-android-sdk") }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}