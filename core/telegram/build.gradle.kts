import java.io.StringReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.photx.android.library)
    alias(libs.plugins.photx.android.hilt)
}

val tdlibReleaseTag = "tdlib-1.8.70-c8828d5"
val tdlibArchiveName = "tdlib-android-$tdlibReleaseTag.zip"
val tdlibReleaseUrl =
    "https://github.com/theprincejr/photex-app/releases/download/$tdlibReleaseTag/$tdlibArchiveName"
val tdlibExpectedSha256 = "876890ffa2316ddf7c9fb7ce3aa3f454a86f399c561a37ee35db55842c0f0dd8"

abstract class PrepareTdlibArtifactsTask : DefaultTask() {
    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:Internal
    abstract val cachedArchive: RegularFileProperty

    @get:OutputDirectory
    abstract val javaOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibsOutputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val archivePath = cachedArchive.get().asFile.toPath()
        Files.createDirectories(archivePath.parent)

        val targetSha256 = expectedSha256.get().lowercase()
        if (!Files.exists(archivePath) || sha256Hex(archivePath) != targetSha256) {
            downloadVerifiedArchive(URI.create(archiveUrl.get()), archivePath, targetSha256)
        }

        val javaRoot = javaOutputDir.get().asFile.toPath()
        val jniRoot = jniLibsOutputDir.get().asFile.toPath()
        clearDirectory(javaRoot)
        clearDirectory(jniRoot)

        extractArchive(archivePath, javaRoot, jniRoot)
    }

    private fun downloadVerifiedArchive(uri: URI, target: Path, expectedHash: String) {
        val tempFile = target.resolveSibling("${target.fileName}.download.tmp")
        try {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile))
            if (response.statusCode() !in 200..299) {
                throw GradleException(
                    "Failed to download verified TDLib archive from $uri (HTTP ${response.statusCode()})"
                )
            }

            val actualHash = sha256Hex(tempFile)
            if (actualHash != expectedHash) {
                throw GradleException(
                    "TDLib archive SHA-256 verification failed. Expected $expectedHash, got $actualHash"
                )
            }
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun extractArchive(archivePath: Path, javaRoot: Path, jniRoot: Path) {
        ZipInputStream(Files.newInputStream(archivePath)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val destination = resolveEntryDestination(entry.name, javaRoot, jniRoot)
                    if (destination != null) {
                        Files.createDirectories(destination.parent)
                        Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun resolveEntryDestination(entryName: String, javaRoot: Path, jniRoot: Path): Path? {
        val (baseDir, relative) = when {
            entryName.startsWith("java/") && entryName.endsWith(".java") ->
                javaRoot to entryName.removePrefix("java/")

            entryName.startsWith("jniLibs/") && entryName.endsWith(".so") ->
                jniRoot to entryName.removePrefix("jniLibs/")

            else -> return null
        }

        val resolved = baseDir.resolve(relative).normalize()
        if (!resolved.startsWith(baseDir.normalize())) {
            throw GradleException("Unsafe zip entry path detected in TDLib archive: $entryName")
        }
        return resolved
    }

    private fun clearDirectory(directory: Path) {
        if (Files.exists(directory)) {
            Files.walk(directory)
                .sorted(Comparator.reverseOrder())
                .forEach(Files::deleteIfExists)
        }
        Files.createDirectories(directory)
    }

    private fun sha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val tdlibCacheZipFile = gradle.gradleUserHomeDir.resolve("caches/photx-tdlib/$tdlibArchiveName")

val prepareTdlibArtifacts = tasks.register<PrepareTdlibArtifactsTask>("prepareTdlibArtifacts") {
    archiveUrl.set(tdlibReleaseUrl)
    expectedSha256.set(tdlibExpectedSha256)
    cachedArchive.set(tdlibCacheZipFile)
    javaOutputDir.set(layout.buildDirectory.dir("generated/tdlib/java"))
    jniLibsOutputDir.set(layout.buildDirectory.dir("generated/tdlib/jniLibs"))
}

fun readPropertyFromText(text: String?, key: String): String? {
    if (text.isNullOrBlank()) return null
    val props = Properties()
    props.load(StringReader(text))
    return props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}

val secretsTextProvider = providers.fileContents(
    rootProject.layout.projectDirectory.file("secrets.properties")
).asText
val localPropsTextProvider = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties")
).asText

fun resolveTelegramCredential(key: String): String? =
    providers.gradleProperty(key).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("PHOTEX_$key").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: readPropertyFromText(secretsTextProvider.orNull, key)
        ?: readPropertyFromText(localPropsTextProvider.orNull, key)

val telegramApiId: Int = resolveTelegramCredential("TELEGRAM_API_ID")?.toIntOrNull() ?: 0
val telegramApiHash: String = (resolveTelegramCredential("TELEGRAM_API_HASH") ?: "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "com.example.photx.core.telegram"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
        buildConfigField("String", "TDLIB_VERSION", "\"1.8.70\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            prepareTdlibArtifacts,
            PrepareTdlibArtifactsTask::javaOutputDir
        )
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            prepareTdlibArtifacts,
            PrepareTdlibArtifactsTask::jniLibsOutputDir
        )
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
