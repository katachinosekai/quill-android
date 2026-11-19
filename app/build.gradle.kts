plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.katachinosekai.quillremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.katachinosekai.quillremote"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
