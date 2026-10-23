package com.dazix.caelis

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What another app on the phone can call. The accessibility service taps into whatever is in front,
 * so anything that can start a session is a way for an unrelated app to tap through this one.
 */
class ExportedComponentsTest {

    /**
     * Every exported receiver and service names the permission a caller needs. The accessibility service
     * is bound by the system alone; the debug session receiver takes DUMP, which only the shell holds.
     */
    @Test
    fun `no exported receiver or service is open to every app`() {
        val tag = Regex("""<(receiver|service)\b([^>]*)>""", RegexOption.DOT_MATCHES_ALL)
        val all = manifests().flatMap { manifest ->
            tag.findAll(manifest.readText()).map { manifest to it.groupValues[2] }
        }
        assertTrue("no components found, the scan is looking in the wrong place", all.isNotEmpty())

        val open = all
            .filter { (_, attributes) -> attributes.contains("""android:exported="true"""") }
            .filterNot { (_, attributes) -> attributes.contains("android:permission=") }
            .map { (manifest, attributes) ->
                manifest.path + ": " + Regex("""android:name="([^"]+)"""").find(attributes)?.groupValues?.get(1)
            }
        assertEquals("exported without a permission", emptyList<String>(), open)
    }

    @Test
    fun `the debug session entry point accepts only the shell`() {
        val debug = manifests().single { it.path.replace(File.separatorChar, '/').contains("src/debug/") }
        val receiver = Regex("""<receiver\b[^>]*DebugSessionReceiver[^>]*>""", RegexOption.DOT_MATCHES_ALL)
            .find(debug.readText())?.value
            ?: error("DebugSessionReceiver is not in ${debug.path}")
        assertTrue(receiver.contains("""android:permission="android.permission.DUMP""""))
    }

    private fun manifests(): List<File> {
        val src = listOf(File("src"), File("app/src")).firstOrNull { File(it, "main/AndroidManifest.xml").isFile }
            ?: error("no src/main/AndroidManifest.xml from ${File(".").absolutePath}")
        return listOf(File(src, "main/AndroidManifest.xml"), File(src, "debug/AndroidManifest.xml"))
            .onEach { assertTrue("${it.path} is missing", it.isFile) }
    }
}
