plugins {
    alias(libs.plugins.photx.android.library)
    alias(libs.plugins.photx.android.hilt)
}

android {
    namespace = "com.example.photx.core.media"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.model)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
