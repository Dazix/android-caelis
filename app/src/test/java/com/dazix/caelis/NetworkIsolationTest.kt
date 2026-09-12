package com.dazix.caelis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app declares `INTERNET`, and that permission cannot be taken back.
 *
 * `INTERNET` has protection level `normal`: Android grants it at install time, offers no runtime
 * API for it and shows the user no switch. Before the terrain horizon layer existed, the absence of
 * that line in the manifest was a guarantee the operating system enforced — worth stating plainly in
 * an app that also holds the accessibility permission.
 *
 * That guarantee is now the source's to keep, so it is kept here. This test walks the shipped Kotlin
 * and fails the build the moment a second place in the app learns how to open a connection. It is
 * the reason the claim in the README and in the manifest comment can still be checked rather than
 * merely believed.
 */
class NetworkIsolationTest {

    /**
     * The single file allowed to touch the network, as a path suffix.
     *
     * If this list ever needs a second entry, that is the decision to argue about — not the test.
     */
    private val allowedNetworkFiles = listOf(
        "com/dazix/caelis/data/horizon/DemTileDownloader.kt",
    )

    /**
     * Files that may name a network *permission* or a connectivity API without opening anything.
     * [com.dazix.caelis.data.horizon.NetworkAvailability] asks the package manager what this build
     * declares; that is the opposite of making a request.
     */
    private val allowedConnectivityFiles = allowedNetworkFiles + listOf(
        "com/dazix/caelis/data/horizon/NetworkAvailability.kt",
    )

    /** Ways to open a socket. Matched against source text, so a new one has to be added knowingly. */
    private val networkMarkers = listOf(
        "java.net.",
        "HttpURLConnection",
        "openConnection(",
        "java.net.Socket",
        "URLConnection",
        "OkHttp",
        "Retrofit",
        "WebSocket",
        "DatagramSocket",
        "ServerSocket",
    )

    private val permittedNetworkPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    @Test
    fun `only the tile downloader opens a connection`() {
        val sources = mainSources()
        assertTrue("no Kotlin sources found — wrong working directory?", sources.size > 50)

        val offenders = mutableListOf<String>()
        for (file in sources) {
            val relative = file.invariantPath()
            if (allowedConnectivityFiles.any { relative.endsWith(it) }) continue

            val text = file.readText()
            for (marker in networkMarkers) {
                if (text.contains(marker)) offenders += "$relative uses '$marker'"
            }
        }

        assertEquals(
            "network access must stay inside ${allowedNetworkFiles.single()}",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the downloader really is where the network lives`() {
        val downloader = mainSources().single { it.invariantPath().endsWith(allowedNetworkFiles.single()) }
        val text = downloader.readText()

        assertTrue("the allowed file no longer opens anything — is the allowance stale?", text.contains("openConnection("))
        assertTrue("consent must be consulted before connecting", text.contains("if (!consent())"))
    }

    @Test
    fun `the manifest declares no network permission beyond the two the tile fetch needs`() {
        val manifest = manifestFile()
        val declared = Regex("""uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .filter { it.contains("INTERNET") || it.contains("NETWORK") || it.contains("SOCKET") }
            .toSet()

        assertEquals(permittedNetworkPermissions, declared)
    }

    /**
     * Removing the `INTERNET` line has to stay a supported way to build this from source, which
     * means nothing may hard-depend on it. The runtime check is what lets the UI adapt instead.
     */
    @Test
    fun `the build can tell at runtime whether it has the permission at all`() {
        val availability = mainSources()
            .single { it.invariantPath().endsWith("com/dazix/caelis/data/horizon/NetworkAvailability.kt") }
            .readText()

        assertTrue(availability.contains("fun hasInternetPermission"))
        assertTrue(availability.contains("checkPermission"))
    }

    private fun File.invariantPath(): String = path.replace(File.separatorChar, '/')

    private fun mainSources(): List<File> = sourceRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    private fun sourceRoot(): File = firstExisting(
        File("src/main/java"),
        File("app/src/main/java"),
    )

    private fun manifestFile(): File = firstExisting(
        File("src/main/AndroidManifest.xml"),
        File("app/src/main/AndroidManifest.xml"),
    )

    /** The suite runs from either the module or the repository root, depending on the invocation. */
    private fun firstExisting(vararg candidates: File): File =
        candidates.firstOrNull { it.exists() }
            ?: error("none of ${candidates.joinToString { it.path }} exists from ${File(".").absolutePath}")
}
