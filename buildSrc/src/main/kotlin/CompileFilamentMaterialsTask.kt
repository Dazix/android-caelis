import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Compiles `*.mat` Filament material sources into `*.filamat` blobs with `matc`.
 *
 * Why a pinned, downloaded matc and not a checked-in binary blob: a `.filamat` is only valid for the
 * Filament runtime whose matc produced it — a mismatch fails at `Material.Builder.build()` on the
 * device, not at build time. Reading the version from the same catalogue entry as the
 * `filament-android` dependency makes that mismatch impossible to commit.
 *
 * The archive is checked against `gradle/filament-tools.sha256` before a single file is extracted.
 * This runs on the build machine only; the app itself never downloads anything here.
 */
@CacheableTask
abstract class CompileFilamentMaterialsTask : DefaultTask() {

    @get:Input
    abstract val filamentVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checksumFile: RegularFileProperty

    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val materialSources: ConfigurableFileCollection

    /** Filament's OpenGL ES backend is what `Engine.create()` picks on Android. */
    @get:Input
    abstract val backends: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Survives `clean` on purpose — the archive is 55 MB. Not an input: its content is pinned by checksum. */
    @get:Internal
    abstract val toolsCacheDir: DirectoryProperty

    @get:Inject abstract val exec: ExecOperations
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun compile() {
        val matc = ensureMatc()
        val out = outputDir.get().asFile
        fs.delete { delete(out) }
        out.mkdirs()
        materialSources.files.sortedBy { it.name }.forEach { mat ->
            val target = File(out, mat.nameWithoutExtension + ".filamat")
            val args = mutableListOf(matc.absolutePath, "-p", "mobile")
            backends.get().split(',').map(String::trim).filter(String::isNotEmpty).forEach {
                args += listOf("-a", it)
            }
            args += listOf("-o", target.absolutePath, mat.absolutePath)
            exec.exec { commandLine(args) }
            if (!target.isFile || target.length() == 0L) {
                throw GradleException("matc produced no output for ${mat.name}")
            }
        }
    }

    private fun ensureMatc(): File {
        val version = filamentVersion.get()
        val archiveName = "filament-v$version-${hostSuffix()}.tgz"
        val expected = checksumFile.get().asFile.readLines()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1] == archiveName }
            ?.get(0)
            ?: throw GradleException("No checksum for $archiveName in ${checksumFile.get().asFile}")

        val versionDir = File(toolsCacheDir.get().asFile, "v$version")
        val exe = if (hostSuffix() == "windows") "matc.exe" else "matc"
        val installed = File(versionDir, "filament/bin/$exe")
        val stamp = File(versionDir, ".verified-$expected")
        if (installed.isFile && stamp.isFile) return installed

        versionDir.mkdirs()
        val archive = File(versionDir, archiveName)
        if (!archive.isFile || sha256(archive) != expected) {
            val url = "https://github.com/google/filament/releases/download/v$version/$archiveName"
            logger.lifecycle("Downloading $url")
            val part = File(versionDir, "$archiveName.part")
            URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val actual = sha256(part)
            if (actual != expected) {
                part.delete()
                throw GradleException("$archiveName checksum mismatch: expected $expected, got $actual")
            }
            if (!part.renameTo(archive)) throw GradleException("Could not move $part to $archive")
        }
        fs.copy {
            from(archives.tarTree(archives.gzip(archive))) {
                include("filament/bin/**")
            }
            into(versionDir)
        }
        if (!installed.isFile) throw GradleException("$archiveName contains no filament/bin/$exe")
        installed.setExecutable(true)
        stamp.writeText(archiveName)
        return installed
    }

    private fun hostSuffix(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") -> "mac"
            os.contains("win") -> "windows"
            arch == "aarch64" || arch == "arm64" -> "arm-linux"
            else -> "linux"
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
