plugins {
    alias(libs.plugins.photx.android.application)
}

android {
    namespace = "com.example.photx"

    defaultConfig {
        applicationId = "com.example.photx"
        versionCode = 1
        versionName = "0.1.0-bootstrap"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)

    testImplementation(libs.junit)
}
