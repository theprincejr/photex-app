import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

group = "com.example.photx.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.spotless.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "photx.android.application"
            implementationClass = "PhotxAndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "photx.android.library"
            implementationClass = "PhotxAndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "photx.android.compose"
            implementationClass = "PhotxAndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "photx.android.hilt"
            implementationClass = "PhotxAndroidHiltConventionPlugin"
        }
        register("androidFeature") {
            id = "photx.android.feature"
            implementationClass = "PhotxAndroidFeatureConventionPlugin"
        }
        register("jvmLibrary") {
            id = "photx.jvm.library"
            implementationClass = "PhotxJvmLibraryConventionPlugin"
        }
        register("quality") {
            id = "photx.quality"
            implementationClass = "PhotxQualityConventionPlugin"
        }
    }
}
