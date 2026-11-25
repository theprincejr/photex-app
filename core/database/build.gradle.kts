plugins {
    alias(libs.plugins.photx.android.library)
    alias(libs.plugins.photx.android.hilt)
    alias(libs.plugins.photx.android.room)
}

android {
    namespace = "com.example.photx.core.database"

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.androidx.paging.common)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
}
