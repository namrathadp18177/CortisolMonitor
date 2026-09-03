Put samsung-health-sensor-api.aar in this folder.

Download (requires a Samsung account, free, no approval needed for dev/thesis use):
https://developer.samsung.com/health/sensor/process.html

Current version referenced by this project: 1.4.1

Once the .aar is here, myapplication/build.gradle.kts already picks it up via:
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

No other build changes needed after dropping the file in.
