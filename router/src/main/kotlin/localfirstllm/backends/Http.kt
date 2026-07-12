/*
 * Small, shared HTTP + JSON plumbing for the HTTP-based backends
 * (OpenAI-compatible, Anthropic). Kept internal — not part of the public API.
 */
package localfirstllm.backends

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Lenient JSON: provider responses carry many fields we don't model. */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}

private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

/** A raw HTTP response: status code plus the full body text. */
internal data class HttpResponse(val status: Int, val body: String) {
    val isSuccessful: Boolean get() = status in 200..299
}

/**
 * POST [jsonBody] to [url] with [headers], blocking on [Dispatchers.IO]. Returns
 * the status and body; the caller decides what a non-2xx status means (each
 * backend raises its own [BackendHttpException]).
 */
internal suspend fun OkHttpClient.postJson(
    url: String,
    headers: Map<String, String>,
    jsonBody: String,
): HttpResponse = withContext(Dispatchers.IO) {
    val builder = Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody(JsonMediaType))
    for ((key, value) in headers) {
        builder.header(key, value)
    }
    newCall(builder.build()).execute().use { response ->
        HttpResponse(response.code, response.body?.string().orEmpty())
    }
}

/** A default client with a sane overall call timeout; backends may pass their own. */
internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(java.time.Duration.ofSeconds(60))
    .build()
