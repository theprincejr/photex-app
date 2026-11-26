plugins {
    alias(libs.plugins.photx.android.library)
    alias(libs.plugins.photx.android.compose)
}

android {
    namespace = "com.example.photx.core.ui"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.domain)
    implementation(projects.core.model)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
