plugins {
    alias(libs.plugins.photx.android.application)
    alias(libs.plugins.photx.android.compose)
    alias(libs.plugins.photx.android.hilt)
}

android {
    namespace = "com.example.photx"

    defaultConfig {
        applicationId = "com.example.photx"
        versionCode = 1
        versionName = "0.1.0-beta.1"
    }

    val releaseKeystorePath = System.getenv("PHOTX_KEYSTORE_PATH")
    val releaseKeystorePassword = System.getenv("PHOTX_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("PHOTX_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("PHOTX_KEY_PASSWORD")
    val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
        file(releaseKeystorePath).isFile &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.domain)
    implementation(projects.core.model)
    implementation(projects.core.telegram)
    implementation(projects.core.ui)
    implementation(projects.feature.auth)
    implementation(projects.feature.gallery)
    implementation(projects.feature.viewer)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    testImplementation(libs.junit)
}
