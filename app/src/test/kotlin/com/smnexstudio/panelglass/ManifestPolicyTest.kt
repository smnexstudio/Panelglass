package com.smnexstudio.panelglass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest's security and network policy as committed (CLAUDE.md › Rules): https only, no backup of keys, only the
 * launcher exported, and the update FileProvider sharing nothing but the downloaded APK's folder. A permission or a
 * cleartext switch added by accident fails here before it ships. Plain JVM: the source XML is read from disk.
 */
class ManifestPolicyTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element {
        val f = File(path).takeIf { it.isFile } ?: File("app", path)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No entity is ever resolved: the parser is used on our own files only, but stays strict anyway.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(f).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    private val manifest by lazy { xml("src/main/AndroidManifest.xml") }
    private val application by lazy { manifest.children("application").single() }

    /**
     * The network, the update installer, and what a Studio run in the background needs (a foreground service of the
     * special-use type, its progress notification, and a wake lock while it works). Nothing else: no storage, camera,
     * location or contacts.
     */
    @Test fun onlyTheExpectedPermissionsAreRequested() {
        val permissions = manifest.children("uses-permission").map { it.getAttributeNS(android, "name") }.toSet()
        assertEquals(
            setOf(
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.REQUEST_INSTALL_PACKAGES",
                "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
                "android.permission.POST_NOTIFICATIONS", "android.permission.WAKE_LOCK",
            ),
            permissions,
        )
    }

    /** The Studio's run service: not exported, special-use, and saying what it is used for, as Android 14+ requires. */
    @Test fun theStudioRunServiceIsPrivateAndSpecialUse() {
        val service = application.children("service").single { it.getAttributeNS(android, "name").endsWith(".StudioRunService") }
        assertEquals("false", service.getAttributeNS(android, "exported"))
        assertEquals("specialUse", service.getAttributeNS(android, "foregroundServiceType"))
        val subtype = service.children("property").single { it.getAttributeNS(android, "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" }
        assertTrue(subtype.getAttributeNS(android, "value").isNotBlank())
    }

    @Test fun cleartextIsNeverAllowed() {
        assertFalse(application.hasAttributeNS(android, "usesCleartextTraffic"), "usesCleartextTraffic must not be set (it defaults to false)")
        assertFalse(application.hasAttributeNS(android, "networkSecurityConfig"), "no network security config that could allow cleartext")
    }

    @Test fun nothingIsBackedUp() {
        assertEquals("false", application.getAttributeNS(android, "allowBackup"))
    }

    @Test fun onlyTheLauncherActivityIsExported() {
        val exported = (application.children("activity") + application.children("service") + application.children("receiver") + application.children("provider"))
            .filter { it.getAttributeNS(android, "exported") == "true" }
            .map { it.getAttributeNS(android, "name") }
        assertEquals(listOf(".MainActivity"), exported)
    }

    /**
     * The FileProvider grants per-URI reads only (not exported) and only over two folders: the downloaded update APK and
     * the cache's share folder. Never models, Studio pages, the database or settings.
     */
    @Test fun theFileProviderSharesOnlyItsTwoFolders() {
        val provider = application.children("provider").single { it.getAttributeNS(android, "name") == "androidx.core.content.FileProvider" }
        assertEquals("false", provider.getAttributeNS(android, "exported"))
        assertEquals("true", provider.getAttributeNS(android, "grantUriPermissions"))
        val paths = xml("src/main/res/xml/file_paths.xml")
        val shared = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filterIsInstance<Element>()
            .map { it.tagName to it.getAttribute("path") }.toSet()
        assertEquals(setOf("external-files-path" to "updates/", "cache-path" to "share/"), shared)
        assertTrue(shared.none { it.second.isEmpty() || it.second == "." || it.second == "/" }, "a whole root is never shared")
    }
}
