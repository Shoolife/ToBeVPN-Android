package com.tobevpn.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FileProviderPathsSourceSetTest {
    @Test
    fun `main and Play release providers expose the intended QR cache directory`() {
        assertSharedQrPath("src/main/res/xml/file_provider_paths.xml")
        assertSharedQrPath("src/playRelease/res/xml/file_provider_paths.xml")
    }

    private fun assertSharedQrPath(relativePath: String) {
        val source = sequenceOf(File(relativePath), File("app", relativePath))
            .firstOrNull(File::isFile)
            ?: error("Missing source resource: $relativePath")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(source)
        val cachePaths = document.getElementsByTagName("cache-path")
        val sharedQrPathExists = (0 until cachePaths.length)
            .map { cachePaths.item(it) as Element }
            .any { element ->
                element.getAttribute("name") == "shared_auth_qr" &&
                    element.getAttribute("path") == "shared_qr/"
            }

        assertTrue(
            "$relativePath must expose cache/shared_qr for user-initiated QR sharing",
            sharedQrPathExists,
        )
    }
}
