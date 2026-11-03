package com.vyrk.core.account

import com.vyrk.core.addon.Descriptor
import com.vyrk.core.addon.ProtocolJson
import com.vyrk.core.library.LibraryItem
import com.vyrk.core.net.HttpException
import com.vyrk.core.net.HttpTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Stremio account service, verified against the live service and stremio-core
 * (src/types/api/request.rs, response.rs) on 2026-11-03:
 *
 * - Link pairing: GET https://link.stremio.com/api/v2/create?type=Create
 *   -> {"result":{"success":true,"code","link","qrcode"}}
 *   GET https://link.stremio.com/api/v2/read?type=Read&code=CODE
 *   -> {"result":{"authKey"}} or {"error":{"code":101,...}} while pending.
 * - Account API: POST https://api.strem.io/api/<method> with a JSON body whose "type" is the
 *   request variant. Invalid sessions answer {"error":{"code":1,"message":"Session does not exist"}}.
 */
object StremioEndpoints {
    const val LINK_API = "https://link.stremio.com/api/v2/"
    const val API = "https://api.strem.io/api/"
    const val LIBRARY_COLLECTION = "libraryItem"
}

class ApiError(val code: Long, message: String) : Exception(message) {
    val isSessionExpired: Boolean get() = code == 1L
    val isLinkPending: Boolean get() = code == 101L
}

@Serializable
data class LinkCode(val code: String, val link: String, val qrcode: String)

@Serializable
data class StremioUser(
    val _id: String = "",
    val email: String = "",
    val avatar: String? = null,
)

@Serializable
private data class CollectionResult(val addons: List<Descriptor> = emptyList(), val lastModified: String? = null)

/** A library item's id and its remote modification time in epoch milliseconds. */
data class RemoteMeta(val id: String, val mtimeMs: Long)

class StremioAccountApi(private val http: HttpTransport) {

    suspend fun createLink(): LinkCode {
        val result = getResult(StremioEndpoints.LINK_API + "create?type=Create")
        return ProtocolJson.decodeFromJsonElement(LinkCode.serializer(), result)
    }

    /** Returns the authKey once the user approves on another device; null while still pending. */
    suspend fun readLink(code: String): String? {
        return try {
            val result = getResult(StremioEndpoints.LINK_API + "read?type=Read&code=" + code)
            (result.jsonObject["authKey"] as? JsonPrimitive)?.contentOrNull
        } catch (e: ApiError) {
            if (e.isLinkPending) null else throw e
        }
    }

    suspend fun getUser(authKey: String): StremioUser {
        val body = buildJsonObject { put("type", "GetUser"); put("authKey", authKey) }
        return ProtocolJson.decodeFromJsonElement(StremioUser.serializer(), post("getUser", body))
    }

    suspend fun logout(authKey: String) {
        post("logout", buildJsonObject { put("type", "Logout"); put("authKey", authKey) })
    }

    suspend fun addonCollectionGet(authKey: String): List<Descriptor> {
        val body = buildJsonObject { put("type", "AddonCollectionGet"); put("authKey", authKey); put("update", true) }
        val result = post("addonCollectionGet", body)
        // Descriptors that fail to parse are skipped rather than failing the whole collection.
        val arr = (result as? JsonObject)?.get("addons") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { ProtocolJson.decodeFromJsonElement(Descriptor.serializer(), el) }.getOrNull()
        }
    }

    suspend fun addonCollectionSet(authKey: String, addons: List<Descriptor>) {
        val body = buildJsonObject {
            put("type", "AddonCollectionSet")
            put("authKey", authKey)
            put("addons", ProtocolJson.encodeToJsonElement(ListSerializer(Descriptor.serializer()), addons))
        }
        post("addonCollectionSet", body)
    }

    /** datastoreMeta returns `[[id, mtimeMs], …]` for the library collection. */
    suspend fun datastoreMeta(authKey: String): List<RemoteMeta> {
        val body = buildJsonObject { put("authKey", authKey); put("collection", StremioEndpoints.LIBRARY_COLLECTION) }
        val result = post("datastoreMeta", body) as? JsonArray ?: return emptyList()
        return result.mapNotNull { pair ->
            val a = pair as? JsonArray ?: return@mapNotNull null
            val id = (a.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val ms = (a.getOrNull(1) as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            RemoteMeta(id, ms)
        }
    }

    suspend fun datastoreGet(authKey: String, ids: List<String>, all: Boolean = false): List<LibraryItem> {
        val body = buildJsonObject {
            put("authKey", authKey)
            put("collection", StremioEndpoints.LIBRARY_COLLECTION)
            putJsonArray("ids") { ids.forEach { add(it) } }
            put("all", all)
        }
        val result = post("datastoreGet", body) as? JsonArray ?: return emptyList()
        // Mirrors LibraryItemsResponse: invalid items are skipped.
        return result.mapNotNull { runCatching { LibraryItem.fromJson(it) }.getOrNull() }
    }

    suspend fun datastorePut(authKey: String, changes: List<LibraryItem>) {
        val body = buildJsonObject {
            put("authKey", authKey)
            put("collection", StremioEndpoints.LIBRARY_COLLECTION)
            put("changes", JsonArray(changes.map { it.toJson() }))
        }
        post("datastorePut", body)
    }

    private suspend fun post(method: String, body: JsonObject): JsonElement {
        val res = http.postJson(StremioEndpoints.API + method, body.toString())
        return unwrap(res.code, res.body)
    }

    private suspend fun getResult(url: String): JsonElement {
        val res = http.get(url)
        return unwrap(res.code, res.body)
    }

    companion object {
        /** Parses the `{"result":…}` / `{"error":{code,message}}` envelope used by both services. */
        fun unwrap(code: Int, body: String): JsonElement {
            val root = runCatching { ProtocolJson.parseToJsonElement(body) }.getOrNull() as? JsonObject
                ?: throw HttpException(code, "Unexpected response ($code)")
            val err = root["error"]
            if (err is JsonObject) {
                val c = (err["code"] as? JsonPrimitive)?.longOrNull ?: -1
                val m = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Error"
                throw ApiError(c, m)
            }
            return root["result"] ?: throw HttpException(code, "Missing result ($code)")
        }
    }
}
