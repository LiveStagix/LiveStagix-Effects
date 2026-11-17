plugins {
    id("com.android.application")
}

android {
    namespace = "com.livestagix.vulkantest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.livestagix.vulkantest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += "arm64-v8a"
        }

    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

}

dependencies {
    implementation(project(":renderer"))
}
