plugins {
    alias(libs.plugins.photx.android.library)
    alias(libs.plugins.photx.android.hilt)
}

android {
    namespace = "com.example.photx.core.data"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.domain)
    implementation(projects.core.media)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.androidx.paging.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.robolectric)
}
