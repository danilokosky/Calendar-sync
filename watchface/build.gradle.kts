plugins {
    id("com.android.application")
}

android {
    namespace = "com.chronowear.watchface"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chronowear.watchface"
        minSdk = 33
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.2"
    }

    buildTypes {
        debug {
            // Obrigatório no Watch Face Format (hasCode=false) para remover classes.dex vazio do APK debug
            isMinifyEnabled = true
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
