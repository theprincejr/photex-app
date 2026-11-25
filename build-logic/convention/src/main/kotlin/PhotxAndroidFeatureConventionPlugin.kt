import com.example.photx.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin for PhotX UI feature modules (`:feature:*`).
 *
 * Combines Android library, Compose, and Hilt conventions, wires shared pure-Kotlin core
 * dependencies (`:core:domain`, `:core:model`, `:core:common`), and provides standard Compose
 * lifecycle and unit-testing libraries.
 */
class PhotxAndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("photx.android.library")
            pluginManager.apply("photx.android.compose")
            pluginManager.apply("photx.android.hilt")

            dependencies {
                add("implementation", project(":core:domain"))
                add("implementation", project(":core:model"))
                add("implementation", project(":core:common"))
                add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
                add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
                add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())

                add("testImplementation", libs.findLibrary("junit").get())
                add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
                add("testImplementation", libs.findLibrary("turbine").get())
            }
        }
    }
}
