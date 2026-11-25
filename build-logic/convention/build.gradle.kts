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
    implementation(libs.spotless.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "photx.android.application"
            implementationClass = "PhotxAndroidApplicationConventionPlugin"
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
