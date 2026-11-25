plugins {
    alias(libs.plugins.photx.jvm.library)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
