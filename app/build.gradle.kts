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
