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
        versionCode = 1
        versionName = "1.0.0"
    }
}
