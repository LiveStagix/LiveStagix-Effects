import java.io.File

plugins {
    id("com.android.application")
    id("androidx.baselineprofile")
}

val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orNull
    ?.toIntOrNull()
    ?: 1

val labKeystorePath = providers.environmentVariable("VULKAN_LAB_KEYSTORE_FILE").orNull
val labKeystoreFile = labKeystorePath?.let { File(it) }
val labStorePassword = providers.environmentVariable("VULKAN_LAB_KEYSTORE_PASSWORD").orNull
val labKeyAlias = providers.environmentVariable("VULKAN_LAB_KEY_ALIAS").orNull
val labKeyPassword = providers.environmentVariable("VULKAN_LAB_KEY_PASSWORD").orNull
val hasLabSigning = !labKeystorePath.isNullOrBlank()

if (hasLabSigning) {
    check(labKeystoreFile?.isFile == true) {
        "VULKAN_LAB_KEYSTORE_FILE does not point to an existing keystore"
    }
    check(!labStorePassword.isNullOrBlank()) {
        "VULKAN_LAB_KEYSTORE_PASSWORD is required when the lab keystore is provided"
    }
    check(!labKeyAlias.isNullOrBlank()) {
        "VULKAN_LAB_KEY_ALIAS is required when the lab keystore is provided"
    }
    check(!labKeyPassword.isNullOrBlank()) {
        "VULKAN_LAB_KEY_PASSWORD is required when the lab keystore is provided"
    }
}

android {
    namespace = "com.livestagix.vulkantest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.livestagix.vulkantest"
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.$buildNumber"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasLabSigning) {
            create("vulkanLab") {
                storeFile = labKeystoreFile!!
                storePassword = labStorePassword
                keyAlias = labKeyAlias
                keyPassword = labKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasLabSigning) {
                signingConfig = signingConfigs.getByName("vulkanLab")
            }
        }

        release {
            isDebuggable = false

            if (hasLabSigning) {
                signingConfig = signingConfigs.getByName("vulkanLab")
            }

            optimization {
                enable = true
            }
        }
    }
}

dependencies {
    implementation(project(":renderer"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":macrobenchmark"))
}
