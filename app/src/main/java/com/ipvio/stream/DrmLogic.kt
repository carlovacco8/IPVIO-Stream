package com.ipvio.stream

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * DRM for M3U entries (pure logic, no Android imports – testable with JUnit).
 *
 * Playlists describe protected streams with Kodi properties above the address, e.g.
 * ```
 * #KODIPROP:inputstream.adaptive.license_type=clearkey
 * #KODIPROP:inputstream.adaptive.license_key=<kid hex>:<key hex>
 * #EXTINF:-1 tvg-id="…",Channel
 * https://example.com/manifest.mpd
 * ```
 * Supported:
 * - `inputstream.adaptive.license_type` = `clearkey` / `org.w3.clearkey` / `com.widevine.alpha` / `widevine` /
 *   `com.microsoft.playready` / `playready`
 * - `inputstream.adaptive.license_key` = license server address (optionally Kodi style `url|Header=v&H2=v|R{SSM}|`),
 *   ClearKey `kid:key[,kid:key…]` (hex or base64url), JSON `{"kid hex":"key hex"}` or a JWK set `{"keys":[…]}`
 * - `inputstream.adaptive.drm_legacy` = `system|license|headers` (newer Kodi form)
 * - `inputstream.adaptive.manifest_type` = `mpd` / `hls` (for addresses without .mpd/.m3u8 ending)
 * - `inputstream.adaptive.license_headers` / `stream_headers` = `Header=v&H2=v` (license request headers)
 *
 * The details travel with the address as a fragment ([MARK]): no change of the stored playlist format
 * ([PlaylistCacheIO]) and of the [Item] class. The fragment is never sent to a server (HTTP does not transmit it)
 * and is removed before playback anyway ([base]).
 */
object DrmLogic {
    const val MARK = "#ipvio-drm="

    enum class Type { CLEARKEY, WIDEVINE, PLAYREADY }

    /**
     * DRM details of a stream.
     * [licenseUrl] = license server (Widevine/PlayReady, or ClearKey with a server); [clearKeys] = JWK set JSON for
     * ClearKey with local keys (no server needed); [mime] = "mpd"/"hls" if the address does not show the format.
     */
    data class Info(
        val type: Type?,
        val licenseUrl: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val clearKeys: String? = null,
        val mime: String? = null
    ) {
        /** Nothing usable: no DRM system and no manifest hint. */
        val empty: Boolean get() = type == null && mime == null
    }

    private const val P = "inputstream.adaptive."

    /** Is this line one of the property lines that belong to the next entry? */
    fun isPropLine(l: String): Boolean = l.startsWith("#KODIPROP:", ignoreCase = true)

    /** True if the address carries DRM details. */
    fun has(url: String): Boolean = url.indexOf(MARK) >= 0

    /** Address without the DRM details (what the player really opens). */
    fun base(url: String): String {
        val i = url.indexOf(MARK)
        return if (i < 0) url else url.substring(0, i)
    }

    /**
     * Reads the property lines of an entry ("#KODIPROP:key=value").
     * null = no DRM and no manifest hint → the address stays unchanged.
     */
    fun fromProps(lines: List<String>): Info? {
        var type: Type? = null
        var key: String? = null
        var mime: String? = null
        val headers = LinkedHashMap<String, String>()
        for (raw in lines) {
            val l = raw.trim()
            if (!isPropLine(l)) continue
            val kv = l.substring(l.indexOf(':') + 1)
            val eq = kv.indexOf('=')
            if (eq <= 0) continue
            val k = kv.substring(0, eq).trim().lowercase()
            val v = kv.substring(eq + 1).trim()
            if (v.isEmpty()) continue
            when (k) {
                P + "license_type" -> type = typeOf(v) ?: type
                P + "license_key" -> key = v
                P + "manifest_type" -> mime = when (v.lowercase()) { "mpd", "dash" -> "mpd"; "hls" -> "hls"; else -> mime }
                P + "license_headers", P + "stream_headers" -> headers.putAll(headerList(v))
                P + "drm_legacy" -> {
                    // system|license|headers
                    val parts = v.split('|')
                    typeOf(parts[0])?.let { type = it }
                    if (parts.size > 1 && parts[1].isNotBlank()) key = parts[1].trim()
                    if (parts.size > 2) headers.putAll(headerList(parts[2]))
                }
            }
        }
        if (type == null && key != null) type = guessType(key)
        val info = build(type, key, headers, mime)
        return if (info.empty) null else info
    }

    private fun typeOf(v: String): Type? = when (v.trim().lowercase()) {
        "clearkey", "org.w3.clearkey" -> Type.CLEARKEY
        "widevine", "com.widevine.alpha" -> Type.WIDEVINE
        "playready", "com.microsoft.playready" -> Type.PLAYREADY
        else -> null
    }

    /** license_key without license_type: local keys mean ClearKey, an address means Widevine (most common). */
    private fun guessType(key: String): Type? = when {
        parseKeys(key) != null -> Type.CLEARKEY
        key.startsWith("http", ignoreCase = true) -> Type.WIDEVINE
        else -> null
    }

    private fun build(type: Type?, key: String?, headers: Map<String, String>, mime: String?): Info {
        if (type == null || key == null) return Info(type, null, headers, null, mime)
        if (type == Type.CLEARKEY) {
            parseKeys(key)?.let { return Info(type, null, headers, it, mime) }
        }
        // License server, Kodi style: url|Header=v&H2=v|R{SSM}|…
        val parts = key.split('|')
        val url = parts[0].trim()
        val all = LinkedHashMap(headers)
        if (parts.size > 1) all.putAll(headerList(parts[1]))
        return Info(type, url.takeIf { it.startsWith("http", ignoreCase = true) }, all, null, mime)
    }

    /** "A=1&B=2" → map; values may be URL-encoded (Kodi style). */
    internal fun headerList(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in s.split('&')) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val k = p.substring(0, eq).trim()
            val v = runCatching { URLDecoder.decode(p.substring(eq + 1), "UTF-8") }.getOrDefault(p.substring(eq + 1)).trim()
            if (k.isNotEmpty() && !k.contains('{')) out[k] = v
        }
        return out
    }

    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")
    private val B64 = Regex("^[A-Za-z0-9_\\-+/]{20,24}={0,2}$")
    private val JSON_PAIR = Regex("\"([0-9a-fA-F]{32})\"\\s*:\\s*\"([0-9a-fA-F]{32})\"")

    /**
     * ClearKey keys → JWK set JSON as Media3's local ClearKey callback expects it.
     * Accepts "kid:key[,kid:key]" (hex 32 characters or base64url), {"kid":"key"} (hex) or a JWK set. null = not keys.
     */
    fun parseKeys(s: String): String? {
        val t = s.trim()
        if (t.startsWith("{")) {
            if (t.contains("\"keys\"")) return t
            val pairs = JSON_PAIR.findAll(t).map { it.groupValues[1] to it.groupValues[2] }.toList()
            return if (pairs.isEmpty()) null else jwk(pairs.map { b64url(hex(it.first)) to b64url(hex(it.second)) })
        }
        if (t.startsWith("http", ignoreCase = true)) return null
        val pairs = ArrayList<Pair<String, String>>()
        for (p in t.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val c = p.indexOf(':')
            if (c <= 0) return null
            val kid = keyPart(p.substring(0, c)) ?: return null
            val k = keyPart(p.substring(c + 1)) ?: return null
            pairs.add(kid to k)
        }
        return if (pairs.isEmpty()) null else jwk(pairs)
    }

    /** One half of "kid:key" as base64url without padding. */
    private fun keyPart(s: String): String? {
        val t = s.trim()
        return when {
            HEX32.matches(t) -> b64url(hex(t))
            B64.matches(t) -> t.trimEnd('=').replace('+', '-').replace('/', '_')
            else -> null
        }
    }

    private fun jwk(pairs: List<Pair<String, String>>): String =
        pairs.joinToString(",", "{\"keys\":[", "],\"type\":\"temporary\"}") { (kid, k) ->
            "{\"kty\":\"oct\",\"k\":\"$k\",\"kid\":\"$kid\"}"
        }

    internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Base64url without padding (own code: java.util.Base64 would need API 26, the app runs from 24). */
    internal fun b64url(b: ByteArray): String {
        val sb = StringBuilder((b.size * 4 + 2) / 3)
        var i = 0
        while (i < b.size) {
            val n = (b[i].toInt() and 0xFF shl 16) or
                ((if (i + 1 < b.size) b[i + 1].toInt() and 0xFF else 0) shl 8) or
                (if (i + 2 < b.size) b[i + 2].toInt() and 0xFF else 0)
            sb.append(ALPHA[n ushr 18 and 63]).append(ALPHA[n ushr 12 and 63])
            if (i + 1 < b.size) sb.append(ALPHA[n ushr 6 and 63])
            if (i + 2 < b.size) sb.append(ALPHA[n and 63])
            i += 3
        }
        return sb.toString()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** Address with the DRM details attached (replaces details that are already there). */
    fun attach(url: String, info: Info): String {
        val sb = StringBuilder()
        fun put(k: String, v: String?) { if (v != null) { if (sb.isNotEmpty()) sb.append('&'); sb.append(k).append('=').append(enc(v)) } }
        put("t", info.type?.name?.lowercase())
        put("l", info.licenseUrl)
        put("k", info.clearKeys)
        put("m", info.mime)
        for ((k, v) in info.headers) put("h." + k, v)
        return base(url) + MARK + sb
    }

    /** DRM details of an address; null = none. */
    fun of(url: String): Info? {
        val i = url.indexOf(MARK)
        if (i < 0) return null
        var type: Type? = null
        var lic: String? = null
        var keys: String? = null
        var mime: String? = null
        val headers = LinkedHashMap<String, String>()
        for (p in url.substring(i + MARK.length).split('&')) {
            val eq = p.indexOf('=')
            if (eq <= 0) continue
            val k = p.substring(0, eq)
            val v = dec(p.substring(eq + 1))
            when {
                k == "t" -> type = typeOf(v)
                k == "l" -> lic = v
                k == "k" -> keys = v
                k == "m" -> mime = v
                k.startsWith("h.") -> headers[k.substring(2)] = v
            }
        }
        return Info(type, lic, headers, keys, mime).takeUnless { it.empty }
    }

    /** Entry address plus the property lines collected above it. */
    fun withProps(url: String, props: List<String>): String {
        if (props.isEmpty()) return url
        val info = fromProps(props) ?: return url
        return attach(url, info)
    }
}
