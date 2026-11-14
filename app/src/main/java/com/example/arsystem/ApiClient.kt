package com.example.arsystem

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RegistrationReply(val accepted: Boolean, val verificationRequired: Boolean, val message: String, val localCode: String? = null)
data class LoginReply(val accepted: Boolean, val emailVerified: Boolean, val name: String, val position: String, val role: String, val message: String)

/** Optional online bridge. The field client remains usable when this endpoint is unreachable. */
object ApiClient {
    private val baseUrl: String = BuildConfig.KST_API_BASE_URL.trimEnd('/') + "/"

    fun register(email: String, password: String, name: String, position: String): RegistrationReply {
        val body = JSONObject().apply { put("email", email); put("password", password); put("name", name); put("position", position) }
        val response = request("api/auth/register", "POST", body.toString())
        if (response.first !in 200..299) return RegistrationReply(false, false, errorMessage(response.second, "Сервер отклонил регистрацию"))
        val json = JSONObject(response.second)
        return RegistrationReply(true, json.optBoolean("verification_required", true), json.optString("verification_hint", "Проверьте почту"), json.optString("development_verification_code").takeIf { it.isNotBlank() })
    }

    fun verify(email: String, code: String): Boolean {
        val body = JSONObject().apply { put("email", email); put("code", code) }
        val response = request("api/auth/verify-email", "POST", body.toString())
        return response.first in 200..299 && JSONObject(response.second).optBoolean("verified", false)
    }

    fun login(email: String, password: String): LoginReply {
        val body = JSONObject().apply { put("email", email); put("password", password) }
        val response = request("api/auth/login", "POST", body.toString())
        if (response.first !in 200..299) return LoginReply(false, false, "", "", "engineer", errorMessage(response.second, "Сервер отклонил вход"))
        val json = JSONObject(response.second)
        return LoginReply(true, json.optBoolean("email_verified", true), json.optString("name", email), json.optString("position", "Инженер по эксплуатации"), json.optString("role", "engineer"), "Онлайн-сессия подтверждена")
    }

    private fun request(path: String, method: String, body: String): Pair<Int, String> {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2500
            readTimeout = 4000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "{}")
        } finally {
            connection.disconnect()
        }
    }

    private fun errorMessage(body: String, fallback: String): String {
        return runCatching {
            val json = JSONObject(body)
            val detail = json.optString("detail").trim()
            if (detail.isNotBlank()) detail else fallback
        }.getOrDefault(fallback)
    }
}
