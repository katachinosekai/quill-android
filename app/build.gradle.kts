import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/Android/quill-release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.katachinosekai.quillremote"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.katachinosekai.quillremote"
        minSdk = 30
        targetSdk = 36
        versionCode = (project.findProperty("qVersionCode") as String?)?.toInt() ?: 17
        versionName = (project.findProperty("qVersionName") as String?) ?: "0.9.2"
        manifestPlaceholders["appLabel"] = "Quill"
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Quill (debug)"
        }
        release {
            isMinifyEnabled = false
            if (releaseProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
