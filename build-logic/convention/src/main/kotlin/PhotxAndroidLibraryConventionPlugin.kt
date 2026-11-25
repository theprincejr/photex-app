import com.android.build.api.dsl.LibraryExtension
import com.example.photx.buildlogic.configureKotlinAndroid
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Convention plugin for Android library modules (`:feature:*`, `:core:telegram`, etc.).
 *
 * Uses AGP 9's built-in Kotlin support, enables explicit API mode so module contracts
 * remain intentional, configures Android Lint, and applies `photx.quality`.
 */
class PhotxAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            pluginManager.apply("photx.quality")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)

                defaultConfig {
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                lint {
                    abortOnError = true
                    warningsAsErrors = false
                    checkDependencies = true
                }
            }

            extensions.configure<KotlinAndroidProjectExtension> {
                explicitApi()
            }
        }
    }
}
