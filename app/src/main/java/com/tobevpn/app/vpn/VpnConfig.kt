package com.tobevpn.app.vpn

import com.tobevpn.app.domain.model.RealityFingerprintPolicy
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object VpnConfig {

    const val LOCAL_SOCKS_PORT = 28080

    fun buildConfigJson(
        server: Server,
        realityFingerprintOverride: String? = null,
    ): String {
        return JSONObject().apply {
            put("stats", JSONObject())
            put("log", JSONObject().put("loglevel", "info"))
            put("policy", buildPolicy())
            put("inbounds", buildInbounds())
            put("outbounds", buildOutbounds(server, realityFingerprintOverride))
            put("dns", buildDns())
            put("routing", buildRouting())
            put("xudp", JSONObject().apply {
                put("baseKey", server.uuid)
            })
        }.toString(2)
    }

    /**
     * Builds the same outbound as the real VPN connection, trimmed for
     * libv2ray's outbound-delay probe. A successful result therefore proves
     * that VLESS, the selected transport and TLS/REALITY can carry an HTTP
     * request; unlike a raw TCP connect it does not merely prove that the port
     * is open.
     */
    fun buildOutboundDelayConfigJson(server: Server): String {
        val config = JSONObject(buildConfigJson(server))
        config.put("inbounds", JSONArray())
        config.optJSONObject("routing")?.put("rules", JSONArray())
        config.remove("dns")
        config.remove("stats")
        config.remove("policy")
        config.optJSONArray("outbounds")?.let { outbounds ->
            repeat(outbounds.length()) { index ->
                outbounds.optJSONObject(index)?.remove("mux")
            }
        }
        return config.toString()
    }

    private fun buildPolicy(): JSONObject {
        return JSONObject().apply {
            put("levels", JSONObject().apply {
                put("8", JSONObject().apply {
                    put("handshake", 4)
                    put("connIdle", 300)
                    put("uplinkOnly", 1)
                    put("downlinkOnly", 1)
                })
            })
            put("system", JSONObject().apply {
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            })
        }
    }

    private fun buildInbounds(): JSONArray {
        return JSONArray().apply {
            put(JSONObject().apply {
                put("tag", "socks")
                put("port", LOCAL_SOCKS_PORT)
                put("protocol", "socks")
                put("listen", "127.0.0.1")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                    put("userLevel", 8)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                    })
                    put("routeOnly", false)
                })
            })
            put(JSONObject().apply {
                put("tag", "tun")
                put("port", 0)
                put("protocol", "tun")
                put("settings", JSONObject().apply {
                    put("name", "xray0")
                    put("MTU", 1500)
                    put("userLevel", 8)
                })
                put("sniffing", JSONObject().apply {
                    put("enabled", true)
                    put("destOverride", JSONArray().apply {
                        put("http")
                        put("tls")
                    })
                    put("routeOnly", false)
                })
            })
        }
    }

    private fun buildOutbounds(
        server: Server,
        realityFingerprintOverride: String?,
    ): JSONArray {
        return JSONArray().apply {
            if (server.protocol == ServerProtocol.HYSTERIA2) {
                put(buildHysteria2Outbound(server))
            } else put(JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", server.address)
                            put("port", server.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", server.uuid)
                                    put("level", 8)
                                    put("encryption", "none")
                                    if (server.flow.isNotEmpty()) {
                                        put("flow", server.flow)
                                    }
                                })
                            })
                        })
                    })
                })
                put(
                    "streamSettings",
                    buildStreamSettings(server, realityFingerprintOverride),
                )
                if (server.network != "xhttp") {
                    put("mux", JSONObject().apply {
                        put("enabled", false)
                        put("concurrency", -1)
                    })
                }
            })
            // The link's own finalmask (fm=) replaces whatever was built
            // above, salamander and port hopping included, the way v2rayN
            // applies it: the server published the exact masks it expects.
            server.finalMask.takeIf(String::isNotBlank)
                ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                ?.let { mask -> optJSONObject(0)?.optJSONObject("streamSettings")?.put("finalmask", mask) }
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "UseIP")
                })
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject().apply {
                    put("response", JSONObject().put("type", "http"))
                })
            })
        }
    }

    /**
     * Hysteria2 through Xray's "hysteria" outbound (QUIC over UDP). The format
     * was checked end to end against an Xray 26.10 Hysteria2 server:
     * - auth goes in hysteriaSettings, the server address in settings;
     * - TLS is mandatory with ALPN h3; a self-signed server is accepted only by
     *   its pinned certificate hash, since this Xray removed allowInsecure;
     * - salamander obfuscation and port hopping are UDP masks in finalmask.
     */
    private fun buildHysteria2Outbound(server: Server): JSONObject = JSONObject().apply {
        put("tag", "proxy")
        put("protocol", "hysteria")
        put("settings", JSONObject().apply {
            put("version", 2)
            put("address", server.address)
            put("port", server.port)
        })
        put("streamSettings", JSONObject().apply {
            put("network", "hysteria")
            put("security", "tls")
            put("tlsSettings", JSONObject().apply {
                put("serverName", server.sni.ifBlank { server.address })
                put("alpn", serverAlpn(server) ?: JSONArray().put("h3"))
                if (server.pinSha256.isNotBlank()) {
                    put("pinnedPeerCertSha256", server.pinSha256)
                }
                if (server.echConfigList.isNotBlank()) {
                    put("echConfigList", server.echConfigList)
                }
            })
            put("hysteriaSettings", JSONObject().apply {
                put("version", 2)
                put("auth", server.uuid)
            })
            val masks = JSONArray()
            if (server.portHopping.isNotBlank()) {
                masks.put(JSONObject().apply {
                    put("type", "udphop")
                    put("settings", JSONObject().apply {
                        put("mode", "intervalRemote")
                        put("remotePorts", server.portHopping)
                    })
                })
            }
            if (server.obfsPassword.isNotBlank()) {
                masks.put(JSONObject().apply {
                    put("type", "salamander")
                    put("settings", JSONObject().put("password", server.obfsPassword))
                })
            }
            if (masks.length() > 0) {
                put("finalmask", JSONObject().put("udp", masks))
            }
        })
    }

    private fun buildStreamSettings(
        server: Server,
        realityFingerprintOverride: String?,
    ): JSONObject {
        return JSONObject().apply {
            put("network", server.network)
            put("security", server.security)
            when (server.network) {
                "xhttp" -> {
                    put("xhttpSettings", JSONObject().apply {
                        if (server.path.isNotEmpty()) put("path", server.path)
                        if (server.host.isNotEmpty()) put("host", server.host)
                        if (server.mode.isNotEmpty()) put("mode", server.mode)
                        server.extra.takeIf(String::isNotBlank)
                            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                            ?.let { extra -> put("extra", extra) }
                    })
                }
                "ws" -> {
                    put("wsSettings", JSONObject().apply {
                        if (server.path.isNotEmpty()) put("path", server.path)
                        if (server.host.isNotEmpty()) {
                            put("host", server.host)
                            put(
                                "headers",
                                JSONObject().put("Host", server.host),
                            )
                        }
                    })
                }
                "grpc" -> {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", server.serviceName)
                        put(
                            "multiMode",
                            server.mode.equals("multi", ignoreCase = true),
                        )
                        if (server.host.isNotEmpty()) {
                            put("authority", server.host)
                        }
                    })
                }
                else -> {
                    put("tcpSettings", JSONObject().apply {
                        val headerType = server.headerType
                            .lowercase(Locale.ROOT)
                            .takeIf { it == "http" }
                            ?: "none"
                        put("header", JSONObject().apply {
                            put("type", headerType)
                            if (headerType == "http") {
                                put("request", JSONObject().apply {
                                    server.path.takeIf(String::isNotBlank)?.let { path ->
                                        put("path", JSONArray().put(path))
                                    }
                                    server.host.takeIf(String::isNotBlank)?.let { host ->
                                        put(
                                            "headers",
                                            JSONObject().put("Host", JSONArray().put(host)),
                                        )
                                    }
                                })
                                put("response", JSONObject())
                            }
                        })
                    })
                }
            }
            if (server.security == "reality") {
                put("realitySettings", JSONObject().apply {
                    put("allowInsecure", false)
                    put("serverName", server.sni)
                    put(
                        "fingerprint",
                        RealityFingerprintPolicy.fingerprintForConfig(
                            server,
                            realityFingerprintOverride,
                        ),
                    )
                    put("publicKey", server.publicKey)
                    put("shortId", server.shortId)
                    put("spiderX", server.spx.ifEmpty { "/" })
                })
            } else if (server.security == "tls") {
                put("tlsSettings", JSONObject().apply {
                    put("allowInsecure", false)
                    put("serverName", server.sni)
                    put("fingerprint", server.effectiveFingerprint)
                    serverAlpn(server)?.let { put("alpn", it) }
                })
            }
        }
    }

    private fun serverAlpn(server: Server): JSONArray? {
        val protocols = server.alpn
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (protocols.isEmpty()) return null
        return JSONArray().apply { protocols.forEach { put(it) } }
    }

    private fun buildDns(): JSONObject {
        return JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
                put("2606:4700:4700::1111")
                put("2001:4860:4860::8888")
            })
            put("queryStrategy", "UseIP")
            put("tag", "dns-module")
        }
    }

    private fun buildRouting(): JSONObject {
        return JSONObject().apply {
            put("domainStrategy", "AsIs")
            put("rules", JSONArray())
        }
    }
}
