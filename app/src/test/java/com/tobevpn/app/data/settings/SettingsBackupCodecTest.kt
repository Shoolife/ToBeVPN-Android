package com.tobevpn.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsBackupCodecTest {
    @Test
    fun roundTrip_preservesSelectedSections() {
        val source = SettingsBackupDocument(
            createdAtMillis = 123L,
            language = "ru",
            theme = "DARK",
            display = PortableDisplaySettings(
                interfaceScale = 1.2f,
                fontScale = 0.9f,
                boldText = true,
                outlinedText = false,
            ),
            appRouting = PortableAppRoutingSettings(
                mode = "WHITELIST",
                packages = listOf("com.example.first", "org.example.second"),
            ),
        )

        val restored = SettingsBackupCodec.decode(SettingsBackupCodec.encode(source))

        assertEquals(source, restored)
    }

    @Test
    fun partialFile_keepsMissingSectionsAbsent() {
        val restored = SettingsBackupCodec.decode(
            """{
                "format":"tobevpn-settings",
                "formatVersion":1,
                "createdAtMillis":123,
                "language":"en",
                "futureField":true
            }""".trimIndent(),
        )

        assertEquals("en", restored.language)
        assertNull(restored.theme)
        assertNull(restored.display)
        assertNull(restored.appRouting)
    }

    @Test
    fun invalidScale_isRejected() {
        assertFails {
            SettingsBackupCodec.decode(
                """{
                    "format":"tobevpn-settings",
                    "formatVersion":1,
                    "createdAtMillis":123,
                    "display":{
                        "interfaceScale":9.0,
                        "fontScale":1.0,
                        "boldText":false,
                        "outlinedText":false
                    }
                }""".trimIndent(),
            )
        }
    }

    @Test
    fun invalidTheme_isRejected() {
        assertFails {
            SettingsBackupCodec.decode(
                """{
                    "format":"tobevpn-settings",
                    "formatVersion":1,
                    "createdAtMillis":123,
                    "theme":"BLUE"
                }""".trimIndent(),
            )
        }
    }

    @Test
    fun unsafePackageName_isRejected() {
        assertFails {
            SettingsBackupCodec.decode(
                """{
                    "format":"tobevpn-settings",
                    "formatVersion":1,
                    "createdAtMillis":123,
                    "appRouting":{
                        "mode":"BLACKLIST",
                        "packages":["com.example.good", "not a package"]
                    }
                }""".trimIndent(),
            )
        }
    }

    @Test
    fun foreignAndEmptyFiles_areRejected() {
        assertFails {
            SettingsBackupCodec.decode(
                """{"format":"other-app","formatVersion":1,"createdAtMillis":123,"language":"ru"}""",
            )
        }
        assertFails {
            SettingsBackupCodec.decode(
                """{"format":"tobevpn-settings","formatVersion":1,"createdAtMillis":123}""",
            )
        }
    }

    private fun assertFails(block: () -> Unit) {
        var error: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            error = caught
        }
        assertNotNull(error)
    }
}
