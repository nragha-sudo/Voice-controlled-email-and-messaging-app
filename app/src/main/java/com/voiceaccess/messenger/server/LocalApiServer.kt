package com.voiceaccess.messenger.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.voiceaccess.messenger.accessibility.MessageAccessibilityService
import com.voiceaccess.messenger.controller.MessageReadSync
import com.voiceaccess.messenger.data.MessageEntity
import com.voiceaccess.messenger.data.MessageRepository
import com.voiceaccess.messenger.data.SourceApp
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Stage 5 of the pipeline: exposes the local message queue over HTTP so an
 * outside tool (Claude, driven by the user over Tailscale — see README) can
 * list and mark-as-read WhatsApp/Outlook/SMS messages per source, without
 * touching the device directly.
 *
 * Every route except `/health` requires a matching `X-Api-Key` header (or
 * `Authorization: Bearer <key>`) — see [ApiKeyStore]. This check is not
 * relaxed for Tailscale traffic: reachability over Tailscale replaces
 * open-internet exposure, it is not a substitute for authenticating the
 * request, per requirement 3.
 *
 * ## Endpoints
 * - `GET  /health` — liveness check, no auth (used to confirm the server is
 *   reachable at all, e.g. while setting up Tailscale, before worrying about
 *   the key).
 * - `GET  /api/sources` — each [SourceApp] with its display name and unread count.
 * - `GET  /api/messages?source=WHATSAPP&status=unread&limit=100` — `source`
 *   omitted means all three; `status` is `unread` (default) or `all`;
 *   `limit` caps `all` (1-500, default 100).
 * - `GET  /api/messages/{id}` — a single message's stored fields.
 * - `POST /api/messages/{id}/read` — marks it read locally, and (SMS/WhatsApp
 *   only, see [MessageReadSync]) attempts to mark it read on the actual
 *   source app; the response's `readOnSource`/`note` fields report what
 *   actually happened rather than assuming success.
 * - `POST /api/messages/{id}/open` — deep-links into the real conversation:
 *   for WhatsApp/Outlook this reuses the same accessibility flow the
 *   on-device read-aloud button already uses (requirement 4 — a message
 *   opened through Claude lands in the same WhatsApp chat a manual
 *   read-aloud would), returning the freshly-scraped full body if
 *   available; for SMS it opens the platform Messages app to that thread.
 */
class LocalApiServer(
    port: Int,
    private val context: Context,
    private val repository: MessageRepository,
    private val readSync: MessageReadSync,
    private val apiKeyStore: ApiKeyStore,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        routeUnauthenticated(session) ?: run {
            if (!isAuthorized(session)) unauthorized() else routeAuthenticated(session)
        }
    } catch (e: Exception) {
        Log.e(TAG, "serve: unhandled error for ${session.method} ${session.uri}", e)
        jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "internal error"))
    }

    private fun routeUnauthenticated(session: IHTTPSession): Response? =
        if (session.method == Method.GET && session.uri == "/health") {
            jsonResponse(Response.Status.OK, JSONObject().put("status", "ok"))
        } else {
            null
        }

    private fun routeAuthenticated(session: IHTTPSession): Response {
        val segments = session.uri.trim('/').split('/')
        return when {
            session.method == Method.GET && session.uri == "/api/sources" ->
                handleSources()
            session.method == Method.GET && session.uri == "/api/messages" ->
                handleListMessages(session)
            session.method == Method.GET && segments.matches("api", "messages", null) ->
                handleGetMessage(segments[2].toLongOrNull())
            session.method == Method.POST && segments.matches("api", "messages", null, "read") ->
                handleMarkRead(segments[2].toLongOrNull())
            session.method == Method.POST && segments.matches("api", "messages", null, "open") ->
                handleOpen(segments[2].toLongOrNull())
            else -> notFound()
        }
    }

    /** True if this path has exactly [parts].size segments, matching literal entries and skipping `null` ones (wildcards, e.g. an id). */
    private fun List<String>.matches(vararg parts: String?): Boolean {
        if (size != parts.size) return false
        return parts.indices.all { i -> parts[i] == null || parts[i] == this[i] }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val provided = session.headers["x-api-key"]
            ?: session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
            ?: return false
        val expected = apiKeyStore.getOrCreateKey()
        // Constant-time comparison: this key is the only thing standing
        // between the Tailscale network and the user's actual messages.
        return provided.length == expected.length &&
            MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())
    }

    private fun handleSources(): Response = runBlocking {
        val array = JSONArray()
        for (source in SourceApp.entries) {
            array.put(
                JSONObject()
                    .put("source", source.name)
                    .put("displayName", source.displayName)
                    .put("unread", repository.getUnread(source).size),
            )
        }
        jsonResponse(Response.Status.OK, JSONObject().put("sources", array))
    }

    private fun handleListMessages(session: IHTTPSession): Response = runBlocking {
        val params = session.parameters

        val sourceParam = params["source"]?.firstOrNull()
        val source = if (sourceParam == null) {
            null
        } else {
            runCatching { SourceApp.valueOf(sourceParam.uppercase()) }.getOrNull()
                ?: return@runBlocking badRequest(
                    "unknown source \"$sourceParam\" (expected one of ${SourceApp.entries.joinToString { it.name }})",
                )
        }

        val status = params["status"]?.firstOrNull() ?: "unread"
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull()?.coerceIn(1, 500) ?: 100

        val messages = when (status) {
            "unread" -> repository.getUnread(source)
            "all" -> repository.getRecent(source, limit)
            else -> return@runBlocking badRequest("unknown status \"$status\" (expected \"unread\" or \"all\")")
        }

        jsonResponse(Response.Status.OK, JSONObject().put("messages", messages.take(limit).toJsonArray()))
    }

    private fun handleGetMessage(id: Long?): Response = runBlocking {
        if (id == null) return@runBlocking badRequest("invalid message id")
        val message = repository.getById(id) ?: return@runBlocking notFound()
        jsonResponse(Response.Status.OK, message.toJson())
    }

    private fun handleMarkRead(id: Long?): Response = runBlocking {
        if (id == null) return@runBlocking badRequest("invalid message id")
        val message = repository.getById(id) ?: return@runBlocking notFound()

        val result = readSync.markRead(repository, message)
        jsonResponse(
            Response.Status.OK,
            JSONObject()
                .put("id", message.id)
                .put("readAloud", true)
                .put("readOnSource", result.readOnSource)
                .apply { result.note?.let { put("note", it) } },
        )
    }

    private fun handleOpen(id: Long?): Response = runBlocking {
        if (id == null) return@runBlocking badRequest("invalid message id")
        val message = repository.getById(id) ?: return@runBlocking notFound()

        when (message.sourceApp) {
            SourceApp.SMS -> jsonResponse(
                Response.Status.OK,
                JSONObject().put("id", message.id).put("opened", openSmsThread(message)),
            )
            SourceApp.WHATSAPP, SourceApp.OUTLOOK -> {
                val accessibility = MessageAccessibilityService.currentInstance()
                if (accessibility == null) {
                    jsonResponse(
                        Response.Status.OK,
                        JSONObject().put("id", message.id).put("opened", false)
                            .put("note", "Accessibility service is not enabled on-device."),
                    )
                } else {
                    val body = accessibility.openAndReadMessage(message)
                    jsonResponse(
                        Response.Status.OK,
                        JSONObject().put("id", message.id).put("opened", body != null)
                            .apply { body?.let { put("body", it) } },
                    )
                }
            }
        }
    }

    /** SMS has no accessibility scraping step (its content already arrives complete via broadcast), so "opening" it just deep-links into the platform Messages app. */
    private fun openSmsThread(message: MessageEntity): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sms:${message.phoneNumber ?: message.sender}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "openSmsThread: id=${message.id} failed", e)
        false
    }

    private fun badRequest(message: String) = jsonResponse(Response.Status.BAD_REQUEST, JSONObject().put("error", message))
    private fun notFound() = jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
    private fun unauthorized() = jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("error", "missing or invalid X-Api-Key"))

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun List<MessageEntity>.toJsonArray(): JSONArray =
        JSONArray().apply { forEach { put(it.toJson()) } }

    private fun MessageEntity.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("source", sourceApp.name)
        .put("sender", sender)
        .put("previewText", previewText)
        .put("timestamp", timestamp)
        .put("readAloud", readAloud)
        .put("readOnSource", readOnSource)

    private companion object {
        private const val TAG = "VAM-ApiServer"
    }
}
