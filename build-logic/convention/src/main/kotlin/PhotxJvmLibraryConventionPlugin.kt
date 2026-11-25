import com.example.photx.buildlogic.configureKotlinJvmCompilerOptions
import com.example.photx.buildlogic.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Convention plugin for pure Kotlin/JVM library modules (`:core:common`, `:core:model`,
 * and future `:core:domain`).
 *
 * Enforces Kotlin explicit API mode, JVM 17 bytecode compatibility, and shared quality gates.
 */
class PhotxJvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            pluginManager.apply("photx.quality")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            extensions.configure<KotlinJvmProjectExtension> {
                explicitApi()
            }

            configureKotlinJvmCompilerOptions()

            dependencies {
                add("testImplementation", libs.findLibrary("junit").get())
            }
        }
    }
}
