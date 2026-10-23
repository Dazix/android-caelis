import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * [Parameters.versionName] with the local time of the running build appended, e.g. `1.0-2026/10/23-19:30`.
 *
 * A [ValueSource] rather than `LocalDateTime.now()` in the build script: the configuration cache stores
 * whatever the script computed, so a plain call there was evaluated once and every later build reused
 * that time. The debug versionName then kept showing the build of four days before while the APK under
 * it was new. Wired into a task input and never read while configuring, a value source is obtained
 * again by each build that runs the task. The name is joined here rather than in a `map` in the script,
 * because such a lambda holds the script object, which the configuration cache cannot store.
 */
abstract class BuildTimeValueSource : ValueSource<String, BuildTimeValueSource.Parameters> {

    interface Parameters : ValueSourceParameters {
        val versionName: Property<String>
    }

    override fun obtain(): String =
        parameters.versionName.get() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/M/d-HH:mm"))
}
