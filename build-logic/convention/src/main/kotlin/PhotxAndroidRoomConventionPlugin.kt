import androidx.room.gradle.RoomExtension
import com.example.photx.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Convention plugin configuring Room persistence with KSP, exported schemas, and Paging 3 support.
 */
class PhotxAndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("androidx.room")

            extensions.configure<RoomExtension> {
                schemaDirectory("$projectDir/schemas")
            }

            dependencies {
                add("implementation", libs.findLibrary("androidx-room-runtime").get())
                add("implementation", libs.findLibrary("androidx-room-ktx").get())
                add("implementation", libs.findLibrary("androidx-room-paging").get())
                add("ksp", libs.findLibrary("androidx-room-compiler").get())
            }
        }
    }
}
