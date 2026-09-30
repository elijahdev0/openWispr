package com.voicerewriter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text over the network, in two shapes:
 *
 *  - **OpenAI-compatible** (`/v1/audio/transcriptions`) — Groq, OpenAI, and anything else that speaks
 *    that dialect: multipart form with `file` + `model`, `Bearer` auth, a `text` field back.
 *  - **Deepgram** (`/v1/listen`) — its own thing entirely: `Token` auth, model and every formatting
 *    option in the query string, raw audio bytes as the body, and the transcript nested under
 *    `results.channels[0].alternatives[0].transcript`. Handing Deepgram OpenAI-shaped multipart is a
 *    400 from their side, which is why this is a separate path and not another endpoint preset.
 *
 * Kept deliberately small and interface-stable so an on-device backend can replace the network call
 * without touching callers.
 */
object SttEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // transcription is a single blocking response
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val M4A = "audio/m4a".toMediaType()
    private val WAV = "audio/wav".toMediaType()

    /** Deepgram takes one query parameter per key term; this keeps the request URL sane. */
    private const val MAX_KEYTERMS = 50

    /**
     * Transcribe [audio] using the configured STT provider. Suspends on IO.
     *
     * [biasPrompt] is the OpenAI/Groq `prompt` field: a hint that biases decoding toward given
     * spellings (our personal-vocab glossary), the cloud equivalent of whisper.cpp's initial_prompt.
     * [keyterms] is the same idea for Deepgram, which has no `prompt` field and takes a list of terms
     * instead. Each provider ignores the one it cannot use.
     */
    suspend fun transcribe(
        settings: Settings,
        audio: File,
        biasPrompt: String? = null,
        keyterms: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        if (settings.sttKey.isBlank()) {
            throw IllegalStateException("No speech-to-text key set. Open OpenWispr settings.")
        }
        if (settings.sttProvider == "deepgram") {
            transcribeDeepgram(settings, audio, keyterms)
        } else {
            transcribeOpenAi(settings, audio, biasPrompt)
        }
    }

    // ---------------- OpenAI-compatible (Groq, OpenAI, custom) ----------------

    private fun transcribeOpenAi(settings: Settings, audio: File, biasPrompt: String?): String {
        val url = settings.sttEndpointResolved
        if (url.isEmpty()) throw IllegalStateException("Speech-to-text endpoint not configured.")
        val model = settings.sttModelResolved
        if (model.isEmpty()) throw IllegalStateException("No speech-to-text model configured.")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(M4A))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply { if (!biasPrompt.isNullOrBlank()) addFormDataPart("prompt", biasPrompt) }
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.sttKey}")
            .post(body)
            .build()

        client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw IllegalStateException("STT ${res.code}: ${text.take(300).ifEmpty { res.message }}")
            }
            // OpenAI/Groq return {"text":"..."} for response_format=json.
            return try {
                JSONObject(text).optString("text", "")
            } catch (_: Exception) {
                text // some endpoints return raw text
            }.trim()
        }
    }

    // ---------------- Deepgram ----------------

    /**
     * Builds the pre-recorded `POST /v1/listen` request. Everything a transcript's shape depends on
     * is a query parameter here, so most of this is the settings that were chosen in Settings →
     * Voice → Deepgram; see [Settings] for what each one does and the defaults.
     */
    private fun transcribeDeepgram(settings: Settings, audio: File, keyterms: List<String>): String {
        val base = settings.dgEndpointResolved
        if (base.isEmpty()) throw IllegalStateException("Deepgram endpoint not configured.")
        val model = settings.sttModelResolved.ifEmpty { "nova-3" }

        val params = mutableListOf("model" to model)
        settings.dgLanguage.trim().takeIf { it.isNotEmpty() }?.let { params += "language" to it }

        // smart_format already implies punctuation, so punctuate/numerals are only sent when it is
        // off. Dictation mode is the exception: Deepgram's docs require `punctuate=true` alongside
        // `dictation=true` for spoken commands ("comma", "new line") to become punctuation, so it
        // is sent explicitly whenever dictation is on, even with smart_format doing the formatting.
        if (settings.dgSmartFormat) {
            params += "smart_format" to "true"
            if (settings.dgDictation) params += "punctuate" to "true"
        } else {
            if (settings.dgPunctuate || settings.dgDictation) params += "punctuate" to "true"
            if (settings.dgNumerals) params += "numerals" to "true"
        }
        if (settings.dgDictation) params += "dictation" to "true"
        if (settings.dgParagraphs) params += "paragraphs" to "true"
        if (settings.dgMeasurements) params += "measurements" to "true"
        // diarize_model is the current spelling; the plain diarize flag is deprecated.
        if (settings.dgDiarize) params += "diarize_model" to "latest"
        if (settings.dgFillerWords) params += "filler_words" to "true"
        if (settings.dgProfanityFilter) params += "profanity_filter" to "true"
        if (settings.dgMipOptOut) params += "mip_opt_out" to "true"
        settings.dgRedactValues.forEach { params += "redact" to it }

        if (settings.dgKeyterms) {
            // Nova-3 uses `keyterm`: plain terms, one parameter each, and a weight in the value is
            // silently treated as part of the term rather than rejected. Older models only know
            // `keywords`, which Nova-3 rejects in turn.
            val param = if (model.startsWith("nova-3")) "keyterm" else "keywords"
            keyterms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .take(MAX_KEYTERMS)
                .forEach { params += param to it }
        }

        val url = try {
            base.toHttpUrl().newBuilder()
                .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
        } catch (e: Exception) {
            throw IllegalStateException("Not a usable Deepgram endpoint: $base")
        }

        val request = Request.Builder()
            .url(url)
            // `Bearer` on Deepgram is for JWTs only; an API key has to be sent as `Token`.
            .header("Authorization", "Token ${settings.sttKey}")
            // Raw audio, not multipart: the same WAV the on-device path runs on, header and all,
            // which is exactly what the endpoint expects.
            .post(audio.asRequestBody(WAV))
            .build()

        client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw IllegalStateException("STT ${res.code}: ${text.take(300).ifEmpty { res.message }}")
            }
            return try {
                JSONObject(text)
                    .optJSONObject("results")
                    ?.optJSONArray("channels")?.optJSONObject(0)
                    ?.optJSONArray("alternatives")?.optJSONObject(0)
                    ?.optString("transcript", "")
                    ?.trim()
                    .orEmpty()
            } catch (_: Exception) {
                ""
            }
        }
    }
}
