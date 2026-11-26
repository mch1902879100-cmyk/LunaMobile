package org.lunatranslator.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Debug acceptance fingerprints only. Never copies headers, endpoint, key, or prompt contents. */
object RequestFingerprint {
    fun of(body: JSONObject): JSONObject {
        val result = JSONObject()
        listOf("model", "stream", "temperature", "max_tokens", "thinking").forEach { key ->
            if (body.has(key)) result.put(key, body.get(key))
        }
        val messages = body.getJSONArray("messages")
        val hashes = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val content = message.getString("content")
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            hashes.put(JSONObject().put("role", message.getString("role")).put("chars", content.length).put("sha256", digest))
        }
        return result.put("messages", hashes)
    }
}
