@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.vyrk.core.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stremio add-on protocol models.
 *
 * Shapes follow stremio-core (src/types/addon/manifest.rs and the src/types/resource module).
 * Every class ignores unknown keys so newer add-ons keep working.
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ExtraProp(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    val optionsLimit: Int = 1,
)

@Serializable
data class ManifestCatalog(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraProp> = emptyList(),
    /** Legacy short form of extra names. */
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
) {
    fun supportsExtra(name: String): Boolean =
        extra.any { it.name == name } || extraSupported.contains(name)

    fun requiresExtra(name: String): Boolean =
        extra.any { it.name == name && it.isRequired } || extraRequired.contains(name)

    /** Extras that must be supplied; a catalogue with any we can't satisfy is not a browse row. */
    val requiredExtras: List<String>
        get() = (extra.filter { it.isRequired }.map { it.name } + extraRequired).distinct()
}

/** A manifest resource is either a bare name or an object narrowing types and ID prefixes. */
data class ManifestResource(
    val name: String,
    val types: List<String>?,
    val idPrefixes: List<String>?,
)

@Serializable
data class ManifestBehaviorHints(
    val adult: Boolean = false,
    val p2p: Boolean = false,
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
)

@Serializable
data class Manifest(
    val id: String,
    val version: String = "0.0.0",
    val name: String,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<String> = emptyList(),
    @SerialName("resources") val rawResources: List<JsonElement> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<ManifestCatalog> = emptyList(),
    val behaviorHints: ManifestBehaviorHints = ManifestBehaviorHints(),
) {
    val resources: List<ManifestResource>
        get() = rawResources.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.let { ManifestResource(it, null, null) }
                is JsonObject -> {
                    val name = (el["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    ManifestResource(name, el.stringList("types"), el.stringList("idPrefixes"))
                }
                else -> null
            }
        }

    /**
     * Port of stremio-core `Manifest::is_resource_supported` for non-catalogue resources.
     * A short resource ("stream") uses the manifest's types and prefixes; a full resource object
     * uses only its own (missing types means unsupported). Null or empty prefixes accept any ID.
     */
    fun supports(resource: String, type: String, id: String): Boolean {
        val res = rawResources.firstNotNullOfOrNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.takeIf { it == resource }?.let { ManifestResource(it, types, idPrefixes) }
                is JsonObject -> if ((el["name"] as? JsonPrimitive)?.contentOrNull == resource)
                    ManifestResource(resource, el.stringList("types"), el.stringList("idPrefixes")) else null
                else -> null
            }
        } ?: return false
        val typeOk = res.types?.contains(type) == true
        val prefixes = res.idPrefixes
        val idOk = prefixes == null || prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
        return typeOk && idOk
    }

    /** Port of the catalogue branch: type and ID match and every required extra is supplied. */
    fun supportsCatalog(type: String, id: String, extraNames: Set<String>): Boolean =
        catalogs.any { c -> c.type == type && c.id == id && c.requiredExtras.all { it in extraNames } &&
            extraNames.all { c.supportsExtra(it) } }

    fun hasResource(resource: String): Boolean = resources.any { it.name == resource }
}

private fun JsonObject.stringList(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

@Serializable
data class Link(val name: String = "", val category: String = "", val url: String = "")

@Serializable
data class Trailer(val source: String = "", val type: String = "Trailer")

@Serializable
data class TrailerStream(val ytId: String? = null, val title: String? = null)

@Serializable
data class MetaBehaviorHints(
    val defaultVideoId: String? = null,
    val featuredVideoId: String? = null,
    val hasScheduledVideos: Boolean = false,
)

@Serializable
data class SeriesInfo(val season: Int = 0, val episode: Int = 0)

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val released: String? = null,
    val overview: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val streams: List<Stream> = emptyList(),
    val trailers: List<Stream> = emptyList(),
    /** Episode runtime in minutes or add-on text (for example "45 min"). */
    val runtime: String? = null,
) {
    val seriesInfo: SeriesInfo?
        get() = if (season != null && episode != null) SeriesInfo(season, episode) else null
    val displayTitle: String get() = title ?: name ?: ""
    val synopsis: String? get() = overview ?: description
}

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    @kotlinx.serialization.json.JsonNames("original_language")
    val originalLanguage: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val genre: List<String> = emptyList(),
    val links: List<Link> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val trailerStreams: List<TrailerStream> = emptyList(),
    val behaviorHints: MetaBehaviorHints = MetaBehaviorHints(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    /** Artwork mapping only; keep the original add-on ID for metadata, streams and episodes. */
    @kotlinx.serialization.json.JsonNames("imdb_id")
    val imdbId: String? = null,
)

@Serializable
data class MetaItem(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val released: String? = null,
    @kotlinx.serialization.json.JsonNames("original_language")
    val originalLanguage: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val year: String? = null,
    val country: String? = null,
    val certification: String? = null,
    val genres: List<String> = emptyList(),
    val genre: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val writer: List<String> = emptyList(),
    val links: List<Link> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val trailerStreams: List<TrailerStream> = emptyList(),
    val videos: List<Video> = emptyList(),
    val behaviorHints: MetaBehaviorHints = MetaBehaviorHints(),
    @kotlinx.serialization.json.JsonNames("imdb_id")
    val imdbId: String? = null,
) {
    /** Genres from `genres`, legacy `genre`, or Genre links, in that order. */
    val allGenres: List<String>
        get() = genres.ifEmpty { genre }.ifEmpty { links.filter { it.category == "Genres" }.map { it.name } }

    val castNames: List<String>
        get() = cast.ifEmpty { links.filter { it.category == "Cast" }.map { it.name } }

    val directorNames: List<String>
        get() = director.ifEmpty { links.filter { it.category == "Directors" }.map { it.name } }

    val writerNames: List<String>
        get() = writer.ifEmpty { links.filter { it.category == "Writers" }.map { it.name } }

    val youtubeTrailerId: String?
        get() = trailerStreams.firstNotNullOfOrNull { it.ytId }
            ?: trailers.firstOrNull { it.type.equals("Trailer", true) }?.source?.takeIf { it.isNotBlank() }

    fun toPreview(): MetaPreview = MetaPreview(
        id = id, type = type, name = name, poster = poster, posterShape = posterShape,
        background = background, logo = logo, description = description, releaseInfo = releaseInfo,
        originalLanguage = originalLanguage, runtime = runtime, imdbRating = imdbRating, genres = allGenres, links = links,
        trailers = trailers, trailerStreams = trailerStreams, behaviorHints = behaviorHints,
        cast = castNames, director = directorNames, imdbId = imdbId,
    )
}

@Serializable
data class ProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap(),
)

@Serializable
data class StreamBehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val countryWhitelist: List<String>? = null,
    val proxyHeaders: ProxyHeaders? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
)

@Serializable
data class StreamSubtitle(
    val id: String = "",
    val url: String,
    val lang: String = "",
)

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val playerFrameUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val subtitles: List<StreamSubtitle> = emptyList(),
    val sources: List<String> = emptyList(),
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
) {
    /** All human-readable text an add-on attached to the stream, used by the parser. */
    val text: String get() = listOfNotNull(name, title, description, behaviorHints.filename).joinToString("\n")
}

@Serializable
data class Subtitle(
    val id: String = "",
    val url: String,
    val lang: String = "",
)

@Serializable
data class CatalogResponse(val metas: List<MetaPreview> = emptyList())

@Serializable
data class MetaResponse(val meta: MetaItem? = null)

@Serializable
data class StreamsResponse(val streams: List<Stream> = emptyList())

@Serializable
data class SubtitlesResponse(val subtitles: List<Subtitle> = emptyList())

/** Add-on flags stored with a descriptor in the account collection. */
@Serializable
data class DescriptorFlags(val official: Boolean = false, val protected: Boolean = false)

/**
 * An installed add-on: its manifest plus the transport URL it was loaded from.
 * The transport URL is a secret: it often carries credentials or API keys.
 */
@Serializable
data class Descriptor(
    val manifest: Manifest,
    val transportUrl: String,
    val flags: DescriptorFlags = DescriptorFlags(),
)

internal fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
internal fun JsonElement?.strOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonElement.str(): String = jsonPrimitive.content
