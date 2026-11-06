package com.tunespark.music.provider

import android.util.Base64
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private const val TAG = "JioSaavn"

@Serializable
data class RawJioSaavnArtist(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class RawJioSaavnArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<RawJioSaavnArtist> = emptyList(),
    @SerialName("featured_artists") val featuredArtists: List<RawJioSaavnArtist> = emptyList(),
)

@Serializable
data class RawJioSaavnMoreInfo(
    @SerialName("album_id") val albumId: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    /**
     * Whether a 320 kbps rendition exists, as `"true"`/`"false"` (some
     * responses send a real boolean). Never assume it exists.
     */
    @SerialName("320kbps") val has320: JsonElement? = null,
    /** Comma-separated fallback artist string used by older responses. */
    val singers: String = "",
    val music: String = "",
    val artistMap: RawJioSaavnArtistMap? = null,
) {
    val supports320: Boolean
        get() = when (val value = has320) {
            is JsonPrimitive -> value.booleanOrNull == true ||
                value.contentOrNull.equals("true", ignoreCase = true) ||
                value.contentOrNull == "1"
            else -> false
        }

    /** Primary artists in display order, falling back to `singers` / `music`. */
    val primaryArtists: List<String>
        get() {
            val mapped = artistMap?.primaryArtists?.map { it.name }?.filter { it.isNotBlank() }.orEmpty()
            if (mapped.isNotEmpty()) return mapped
            val fallback = singers.ifBlank { music }
            return fallback.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
}

@Serializable
data class RawJioSaavnSong(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    /** JioSaavn sends `"1"`/`"0"` or a boolean. */
    @SerialName("explicit_content") val explicitContent: JsonElement? = null,
    @SerialName("more_info") val moreInfo: RawJioSaavnMoreInfo = RawJioSaavnMoreInfo(),
) {
    val isExplicit: Boolean
        get() = when (val value = explicitContent) {
            is JsonPrimitive -> value.booleanOrNull == true ||
                value.contentOrNull == "1" ||
                value.contentOrNull.equals("true", ignoreCase = true)
            else -> false
        }

    val albumName: String?
        get() = moreInfo.album.trim().takeIf { it.isNotBlank() && !it.equals("false", ignoreCase = true) }

    val durationSeconds: Int?
        get() = moreInfo.duration.trim().toIntOrNull()?.takeIf { it > 0 }

    /** The catalogue's verified maximum, or null when it says nothing. */
    val verifiedMaxKbps: Int?
        get() = if (moreInfo.supports320) 320 else null
}

@Serializable
data class RawJioSaavnSearchResponse(
    val results: List<RawJioSaavnSong> = emptyList(),
)

@Serializable
data class RawJioSaavnSongsResponse(
    val songs: List<RawJioSaavnSong> = emptyList(),
)

/** A decrypted JioSaavn CDN URL and the bitrate it will actually deliver. */
data class JioSaavnStream(val url: String, val kbps: Int?)

/** A resolved JioSaavn song plus its playable stream. */
data class JioSaavnSongDetails(
    val song: RawJioSaavnSong,
    val stream: JioSaavnStream?,
)

/**
 * Select the highest genuinely available JioSaavn CDN rendition.
 *
 * The catalogue states per track whether a parallel 320 kbps object exists.
 * The rewrite is therefore conditional: asking the CDN for `_320` on a track
 * that has no 320 rendition does not produce one. When the URL's rendition
 * cannot be recognised, the bitrate is reported as unknown rather than
 * inventing a value from the catalogue flag.
 */
internal fun selectBestJioSaavnStream(
    decryptedUrl: String,
    supports320: Boolean,
): JioSaavnStream? {
    if (decryptedUrl.isBlank()) return null

    val rendition = Regex(
        "_(48|96|160|320)\\.(mp4|aac|mp3)(?=[?#]|$)",
        RegexOption.IGNORE_CASE,
    ).find(decryptedUrl)
        ?: return JioSaavnStream(decryptedUrl, null)

    val offeredKbps = rendition.groupValues[1].toInt()
    if (!supports320) return JioSaavnStream(decryptedUrl, offeredKbps)

    val extension = rendition.groupValues[2]
    return JioSaavnStream(
        decryptedUrl.replaceRange(rendition.range, "_320.$extension"),
        320,
    )
}

/**
 * Networking and JSON handling for JioSaavn's public catalogue API.
 *
 * This class only knows about JioSaavn: raw search results, song details,
 * URL decryption and stream renditions. Mapping into Tunespark's models and
 * source-selection decisions live in [JioSaavnSource].
 */
object JioSaavnService {
    private const val BASE_URL = "https://www.jiosaavn.com/api.php"
    private const val DES_KEY = "38346591"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 6_000
                connectTimeoutMillis = 4_000
                socketTimeoutMillis = 6_000
            }
            defaultRequest {
                url(BASE_URL)
                headers.append(HttpHeaders.Accept, "application/json")
                headers.append(
                    HttpHeaders.UserAgent,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
                )
                headers.append(HttpHeaders.AcceptLanguage, "en-IN,en;q=0.9")
                headers.append(HttpHeaders.Cookie, "explicit_content=1")
                // The public catalogue occasionally geo-restricts requests
                // outside India; these mirror a normal Indian client.
                headers.append("X-Forwarded-For", "49.36.0.1")
                headers.append("X-Real-IP", "49.36.0.1")
            }
            expectSuccess = false
        }
    }

    /** Decrypts JioSaavn's DES-encrypted CDN URL. */
    internal fun decryptUrl(encryptedUrl: String): String {
        if (encryptedUrl.isBlank()) return ""
        return try {
            val secretKey = SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decrypted = cipher.doFinal(Base64.decode(encryptedUrl, Base64.DEFAULT))
            String(decrypted, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "URL decryption failed", e)
            ""
        }
    }

    suspend fun searchSongs(query: String, limit: Int = 10): List<RawJioSaavnSong> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()

        val response = client.get(BASE_URL) {
            parameter("__call", "search.getResults")
            parameter("_format", "json")
            parameter("_marker", "0")
            parameter("api_version", "4")
            parameter("ctx", "android")
            parameter("q", query)
            parameter("p", "1")
            parameter("n", limit.coerceIn(1, 20))
        }

        if (response.status != HttpStatusCode.OK) {
            Log.w(TAG, "search failed: HTTP ${response.status.value}")
            return@runCatching emptyList()
        }

        json.decodeFromString<RawJioSaavnSearchResponse>(response.bodyAsText()).results
    }.getOrElse {
        Log.w(TAG, "search error: ${it.message}")
        emptyList()
    }

    suspend fun getSongDetails(saavnSongId: String): JioSaavnSongDetails? = runCatching {
        if (saavnSongId.isBlank()) return@runCatching null

        val response = client.get(BASE_URL) {
            parameter("__call", "song.getDetails")
            parameter("_format", "json")
            parameter("_marker", "0")
            parameter("api_version", "4")
            parameter("ctx", "android")
            parameter("pids", saavnSongId)
        }

        if (response.status != HttpStatusCode.OK) {
            Log.w(TAG, "getDetails failed: HTTP ${response.status.value}")
            return@runCatching null
        }

        // `song.getDetails` answers either with a `{"songs":[…]}` envelope or
        // with a map keyed by the requested id — read both shapes.
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@runCatching null
        val songElement = (root["songs"] as? JsonArray)?.firstOrNull()
            ?: root.values.firstOrNull { it is JsonObject }
            ?: run {
                Log.w(TAG, "getDetails held no song for $saavnSongId")
                return@runCatching null
            }
        val song = json.decodeFromJsonElement(RawJioSaavnSong.serializer(), songElement)

        val decrypted = decryptUrl(song.moreInfo.encryptedMediaUrl)
        JioSaavnSongDetails(
            song = song,
            stream = selectBestJioSaavnStream(decrypted, song.moreInfo.supports320),
        )
    }.onFailure {
        Log.w(TAG, "getDetails error: ${it.message}")
    }.getOrNull()
}
