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
        versionName = "0.1.0-alpha01"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.model)
    implementation(projects.feature.auth)

    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
