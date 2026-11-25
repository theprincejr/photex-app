import com.diffplug.gradle.spotless.SpotlessExtension
import com.example.photx.buildlogic.libs
import com.example.photx.buildlogic.stringVersion
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention plugin configuring Spotless (ktlint) and Detekt static analysis for PhotX modules.
 */
class PhotxQualityConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.diffplug.spotless")
            pluginManager.apply("dev.detekt")

            val ktlintVersion = libs.stringVersion("ktlint")
            val detektVersion = libs.stringVersion("detekt")

            extensions.configure<SpotlessExtension> {
                kotlin {
                    target("src/**/*.kt")
                    targetExclude("**/build/**")
                    ktlint(ktlintVersion)
                }
                kotlinGradle {
                    target("*.kts")
                    targetExclude("**/build/**")
                    ktlint(ktlintVersion)
                }
            }

            extensions.configure<DetektExtension> {
                toolVersion.set(detektVersion)
                buildUponDefaultConfig.set(true)
                allRules.set(false)
                config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
            }
        }
    }
}
