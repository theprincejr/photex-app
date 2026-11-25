import com.android.build.api.dsl.ApplicationExtension
import com.example.photx.buildlogic.configureKotlinAndroid
import com.example.photx.buildlogic.intVersion
import com.example.photx.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention plugin for the PhotX Android application module (`:app`).
 *
 * Uses AGP 9's built-in Kotlin support (`org.jetbrains.kotlin.android` must not be applied),
 * centralizes SDK targets from `libs.versions.toml`, configures Android Lint, and applies
 * the shared quality gates (`photx.quality`).
 */
class PhotxAndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("photx.quality")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)

                defaultConfig {
                    targetSdk = libs.intVersion("targetSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                lint {
                    abortOnError = true
                    warningsAsErrors = false
                    checkDependencies = true
                }
            }
        }
    }
}
