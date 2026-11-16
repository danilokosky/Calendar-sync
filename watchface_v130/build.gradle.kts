plugins {
    id("com.android.application")
}

android {
    namespace = "com.chronowear.watchface.v130"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chronowear.watchface.v130"
        minSdk = 33
        targetSdk = 34
        versionCode = 30
        versionName = "1.3.0"
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
