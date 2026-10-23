import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the packaging of a release when no release signing key was configured.
 *
 * The build used to fall back to the debug key instead. A release APK or bundle signed with a key
 * every development machine generates for itself looks like an official build, Play rejects it only
 * once it is uploaded, and a CI run with a lost secret said nothing at all. Only packaging waits on
 * this check, so release unit tests and lint still run on a machine without the key.
 */
abstract class CheckReleaseSigningTask : DefaultTask() {

    /** Whether `signingConfigs.release` exists, i.e. all four SIGNING_* variables pointed at a keystore. */
    @get:Input
    abstract val configured: Property<Boolean>

    @TaskAction
    fun check() {
        if (!configured.get()) {
            throw GradleException(
                "No release signing key: set SIGNING_KEY_STORE_PATH, SIGNING_STORE_PASSWORD, " +
                    "SIGNING_KEY_ALIAS and SIGNING_KEY_PASSWORD (scripts/build_release_bundle.sh does this).",
            )
        }
    }
}
