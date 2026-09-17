package com.tobevpn.app.vpn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import libv2ray.CoreCallbackHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class XRayCoreInstrumentedTest {

    @Test
    fun nativeLibraryReportsExpectedCoreVersion() {
        XRayCore.init(ApplicationProvider.getApplicationContext())

        val version = XRayCore.getVersion()

        assertTrue(
            "Unexpected native core version: $version",
            version.contains("Lib v40") && version.contains("Xray-core v26.9.9"),
        )
    }

    @Test
    fun nativeControllerStartsDrainsStatsAndStops() {
        XRayCore.init(ApplicationProvider.getApplicationContext())
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        XRayCore.createController(
            object : CoreCallbackHandler {
                override fun startup(): Long {
                    starts.incrementAndGet()
                    return 0L
                }

                override fun shutdown(): Long {
                    stops.incrementAndGet()
                    return 0L
                }

                override fun onEmitStatus(code: Long, status: String?): Long = 0L
            },
        )

        val generation = XRayCore.startLoop(MINIMAL_CONFIG, tunFd = -1)

        assertTrue(XRayCore.isRunning)
        assertEquals(1, starts.get())
        assertEquals(
            XRayOutboundTrafficStats(),
            XRayCore.queryOutboundTrafficStats("proxy"),
        )

        XRayCore.stopLoop(generation)

        assertFalse(XRayCore.isRunning)
        assertEquals(1, stops.get())
    }

    private companion object {
        val MINIMAL_CONFIG =
            """
            {
              "log": {"loglevel": "none"},
              "stats": {},
              "policy": {
                "system": {
                  "statsOutboundUplink": true,
                  "statsOutboundDownlink": true
                }
              },
              "inbounds": [],
              "outbounds": [
                {"tag": "proxy", "protocol": "freedom", "settings": {}}
              ]
            }
            """.trimIndent()
    }
}
