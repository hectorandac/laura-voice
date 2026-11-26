package dev.hect.lauravoice.core

import org.json.JSONArray
import org.json.JSONObject

object Close {
    const val NORMAL = 1000
    const val ABNORMAL = 1006
    const val TAKEOVER = 4000
    const val BAD_KEY = 4401
    /** Client-local: TLS failed (certificate not trusted or pin mismatch). */
    const val CERT = 4995
}

object Protocol {
    fun hello(client: String, c: Config, up: String, down: List<String>, context: Map<String, Any>): String =
        JSONObject()
            .put("type", "hello").put("version", 1).put("client", client)
            .put("lang", c.lang).put("mode", c.mode)
            .put("context", JSONObject(context))
            .put("audio", JSONObject().put("up", up).put("down", JSONArray(down)))
            .toString()

    fun simple(type: String): String = JSONObject().put("type", type).toString()
    fun value(type: String, value: String): String = JSONObject().put("type", type).put("value", value).toString()
    fun context(data: Map<String, Any>): String = JSONObject().put("type", "context").put("data", JSONObject(data)).toString()
    fun bool(type: String, value: Boolean): String = JSONObject().put("type", type).put("value", value).toString()
    fun approval(id: String, choice: String): String =
        JSONObject().put("type", "approval_response").put("approval_id", id).put("choice", choice).toString()
    fun taskCancel(id: String): String = JSONObject().put("type", "task_cancel").put("task_id", id).toString()
    fun interrupt(turnId: String?, playedMs: Long?): String = JSONObject().put("type", "interrupt").apply {
        if (turnId != null) put("turn_id", turnId)
        if (playedMs != null) put("played_ms", playedMs)
    }.toString()
    fun playback(turnId: String, playedMs: Long, state: String): String = JSONObject().put("type", "playback")
        .put("turn_id", turnId).put("played_ms", playedMs).put("state", state).toString()
    fun recall(turnId: String): String = JSONObject().put("type", "recall").put("turn_id", turnId).toString()
    fun wake(score: Float): String = JSONObject().put("type", "wake").put("score", Math.round(score * 1000) / 1000.0).toString()
    fun ping(t: Long): String = JSONObject().put("type", "ping").put("t", t).toString()
    fun stats(fields: Map<String, Any?>): String {
        val o = JSONObject().put("type", "client_stats")
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        return o.toString()
    }
}

sealed class ServerMsg {
    data class Session(val id: String) : ServerMsg()
    data class State(val value: String) : ServerMsg()
    data class Partial(val text: String) : ServerMsg()
    data class UserTurn(val turnId: String, val text: String, val lang: String) : ServerMsg()
    data class AssistantDelta(val turnId: String, val text: String, val source: Source? = null) : ServerMsg()
    data class AssistantDone(val turnId: String, val text: String, val source: Source? = null) : ServerMsg()
    data class AudioStart(val turnId: String, val sampleRate: Int, val codec: String, val source: Source? = null) : ServerMsg()
    data class AudioEnd(val turnId: String) : ServerMsg()
    data class Metrics(val asrMs: Long?, val llmFirstMs: Long?, val ttsFirstMs: Long?, val serverFirstAudioMs: Long?) : ServerMsg()
    data class Pong(val t: Long) : ServerMsg()
    data class Error(val message: String) : ServerMsg()
    data class Ignored(val turnId: String, val reason: String, val text: String) : ServerMsg()
    data class Interrupted(val turnId: String) : ServerMsg()
    data class Duck(val turnId: String, val on: Boolean, val gainDb: Double) : ServerMsg()
    data class ReplyCut(val turnId: String, val heard: String) : ServerMsg()
    data class Muted(val value: Boolean) : ServerMsg()
    data class SpeakerProfile(val available: Boolean, val enrolled: Boolean, val seconds: Double) : ServerMsg()
    data class EnrollPrompt(val index: Int, val total: Int, val text: String) : ServerMsg()
    data class EnrollProgress(val index: Int, val total: Int, val ok: Boolean, val reason: String) : ServerMsg()
    data class EnrollDone(val ok: Boolean, val reason: String) : ServerMsg()
    data class LauraStatus(val connected: Boolean) : ServerMsg()
    data class Task(val taskId: String, val state: String, val title: String) : ServerMsg()
    data class TaskProgress(val taskId: String, val text: String) : ServerMsg()
    data class LauraMessage(val taskId: String?, val text: String, val source: Source? = null) : ServerMsg()
    data class ApprovalRequest(
        val approvalId: String, val taskId: String, val text: String, val command: String,
        val description: String, val choices: List<String>,
    ) : ServerMsg()
    data class ApprovalClosed(val approvalId: String, val choice: String) : ServerMsg()
    data class Avatar(val reaction: String?, val emotion: String?) : ServerMsg()
    data class Unknown(val type: String, val raw: JSONObject) : ServerMsg()

    companion object {
        fun parse(text: String): ServerMsg? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val turn = o.optString("turn_id")
            fun num(k: String): Long? = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
            val src = o.optJSONObject("source")?.let { Source(it.optString("engine"), it.optString("model"), it.optString("lane").ifEmpty { null }) }
            return when (val type = o.optString("type")) {
                "session" -> Session(o.optString("id"))
                "state" -> State(o.optString("value"))
                "partial" -> Partial(o.optString("text"))
                "user_turn" -> UserTurn(turn, o.optString("text"), o.optString("lang"))
                "assistant_delta" -> AssistantDelta(turn, o.optString("text"), src)
                "assistant_done" -> AssistantDone(turn, o.optString("text"), src)
                "audio_start" -> AudioStart(turn, o.optInt("sample_rate", 16000), o.optString("codec", "pcm").ifEmpty { "pcm" }, src)
                "audio_end" -> AudioEnd(turn)
                "metrics" -> Metrics(num("asr_ms"), num("llm_first_ms"), num("tts_first_ms"), num("server_first_audio_ms"))
                "pong" -> Pong(o.optLong("t"))
                "error" -> Error(o.optString("message"))
                "ignored" -> Ignored(turn, o.optString("reason"), o.optString("text"))
                "interrupted" -> Interrupted(turn)
                "duck" -> Duck(turn, o.optBoolean("on"), o.optDouble("gain_db", -12.0))
                "reply_cut" -> ReplyCut(turn, o.optString("heard"))
                "muted" -> Muted(o.optBoolean("value"))
                "speaker_profile" -> SpeakerProfile(o.optBoolean("available"), o.optBoolean("enrolled"), o.optDouble("seconds", 0.0))
                "enroll_prompt" -> EnrollPrompt(o.optInt("index"), o.optInt("total"), o.optString("text"))
                "enroll_progress" -> EnrollProgress(o.optInt("index"), o.optInt("total"), o.optBoolean("ok"), o.optString("reason"))
                "enroll_done" -> EnrollDone(o.optBoolean("ok"), o.optString("reason"))
                "laura_status" -> LauraStatus(o.optBoolean("connected"))
                "task" -> Task(o.optString("task_id"), o.optString("state"), o.optString("title"))
                "task_progress" -> TaskProgress(o.optString("task_id"), o.optString("text"))
                "laura_message" -> LauraMessage(if (o.isNull("task_id")) null else o.optString("task_id"), o.optString("text"), src)
                "approval_request" -> ApprovalRequest(
                    o.optString("approval_id"), o.optString("task_id"), o.optString("text"), o.optString("command"),
                    o.optString("description"),
                    o.optJSONArray("choices")?.let { a -> List(a.length()) { a.getString(it) } } ?: listOf("once", "deny"),
                )
                "approval_closed" -> ApprovalClosed(o.optString("approval_id"), o.optString("choice"))
                "avatar" -> Avatar(o.optString("reaction").ifEmpty { null }, o.optString("emotion").ifEmpty { null })
                else -> Unknown(type, o)
            }
        }
    }
}
