package com.voicerewriter

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * How hard the LLM polish edits, replacing the old on/off toggle. A graded
 * cleanup level — the `instruction` is appended to the dictation polish prompt. OFF skips
 * the model entirely (deterministic-only). The fine-tune ignores the level (it runs its
 * trained behavior); levels steer the cloud / generic on-device models.
 */
enum class PolishLevel(val key: String, val label: String, val blurb: String, val instruction: String) {
    OFF("off", "Off", "Deterministic cleanup only, no model. Fast and safe.", ""),
    LIGHT("light", "Light", "Model fixes only capitalization, spacing and punctuation.",
        "Only fix capitalization, spacing and punctuation. Keep all words the same."),
    MEDIUM("medium", "Medium", "Also splits run-on sentences and fixes small grammar.",
        "You may split run-on sentences and fix small grammar mistakes. " +
            "If the speaker corrects a previous word or number, keep only the corrected version."),
    FULL("full", "Full", "Fuller cleanup, with one small rewrite for clarity if needed.",
        "You may split run-on sentences and fix grammar, and use one small rewrite only if " +
            "needed for clarity. If the speaker corrects a previous word or number, keep only " +
            "the corrected version.");

    companion object {
        fun from(key: String?): PolishLevel = entries.firstOrNull { it.key == key } ?: FULL
    }
}

/** The app's default settings. */
data class Settings(
    val provider: String = Defaults.DEFAULT_PROVIDER,
    val model: String = Defaults.DEFAULT_MODEL,
    val customEndpoint: String = "",
    val apiKey: String = "",
    val voice: String = "",
    val antiAI: Boolean = true,
    val temperature: Double = Defaults.DEFAULT_TEMPERATURE,
    // --- Speech-to-text (Whisper) ---
    val sttProvider: String = Defaults.DEFAULT_STT_PROVIDER,
    val sttEndpoint: String = "", // only used when sttProvider == "custom"
    val sttKey: String = "",
    val sttModel: String = "",
    // --- Deepgram's own shaping options (only read when sttProvider == "deepgram").
    // Deepgram is not an OpenAI-shaped endpoint, so the handful of knobs that decide what the
    // transcript looks like are configured here rather than inferred. Everything is opt-in
    // formatting; the app's deterministic cleanup and polish still run on top. ---
    val dgEndpoint: String = "",       // blank = the US endpoint; set for EU (api.eu.deepgram.com)
    val dgLanguage: String = "",       // BCP-47 hint; blank lets Deepgram default to en, "multi" for multilingual
    val dgSmartFormat: Boolean = true, // punctuation + numerals + dates, currency, emails, URLs
    val dgPunctuate: Boolean = false,  // only when smart_format is off (smart_format implies it)
    val dgNumerals: Boolean = false,   // only when smart_format is off
    val dgDictation: Boolean = false,  // honour spoken formatting/punctuation commands
    val dgParagraphs: Boolean = false, // break the transcript into paragraphs
    val dgMeasurements: Boolean = false, // "five kilometres" -> "5 km"
    val dgDiarize: Boolean = false,    // speaker labels (diarize_model)
    val dgFillerWords: Boolean = false, // keep "uh"/"um" instead of dropping them
    val dgProfanityFilter: Boolean = false,
    val dgRedact: String = "",         // comma-separated: numbers, pci, pii, phi, ssn
    val dgKeyterms: Boolean = true,    // boost the personal dictionary as Deepgram key terms
    val dgMipOptOut: Boolean = false,  // Model Improvement Program opt-out (deepgram.com/pricing)
    // --- Dictation / rewrite behavior ---
    val defaultMode: String = Defaults.MODE_DICTATE, // "dictate" | "rewrite"
    val deterministicCleanup: Boolean = true, // fast rule-based cleanup (fillers, spoken forms, numbers, self-corrections)
    val polishLevel: PolishLevel = PolishLevel.FULL, // LLM polish intensity (replaces the old on/off toggle)
    val vadAutoStop: Boolean = true, // Silero VAD: auto-stop when the speaker pauses
    val bubbleOnlyOnFields: Boolean = true, // show the bubble only while a text field is focused (needs accessibility)
    val hasCompletedOnboarding: Boolean = false, // first-run onboarding shown once; re-launchable from Settings
    // Experimental: Parakeet vocab-bias via sherpa hotwords + modified_beam_search. Off by
    // default — that decode path has an open upstream bug (k2-fsa/sherpa-onnx#3267) that
    // hallucinates or returns empty text on ~20% of NeMo-TDT requests; greedy_search (used
    // otherwise) is unaffected. See LocalParakeetStt for the full story.
    val parakeetHotwordsExperimental: Boolean = false,
) {
    /**
     * Whether to run the LLM polish layer on dictation. On by default — onboarding downloads
     * the polish model up front, and [RewriteActivity] falls back to the deterministic text
     * whenever the model is missing or over-edits. Dial it down via [polishLevel].
     */
    val llmPolishEnabled: Boolean
        get() = polishLevel != PolishLevel.OFF
    /** Cloud gate: needs an API key before it can rewrite via a cloud provider. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** Voice features need a speech-to-text key as well as the LLM key. */
    val isSttConfigured: Boolean get() = sttKey.isNotBlank()

    /** Resolved STT endpoint (custom override, else the provider default). */
    val sttEndpointResolved: String
        get() = if (sttProvider == "custom") sttEndpoint.trim()
                else Defaults.STT_PROVIDERS[sttProvider]?.endpoint.orEmpty()

    /** Resolved STT model (explicit override, else the provider default). */
    val sttModelResolved: String
        get() = sttModel.trim().ifEmpty {
            Defaults.STT_PROVIDERS[sttProvider]?.defaultModel.orEmpty()
        }

    /** Deepgram endpoint: the US default unless the user points at another region. */
    val dgEndpointResolved: String
        get() = dgEndpoint.trim().ifEmpty { Defaults.STT_PROVIDERS["deepgram"]?.endpoint.orEmpty() }

    /** Deepgram redact values, in the order typed. Empty when nothing is being redacted. */
    val dgRedactValues: List<String>
        get() = dgRedact.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL = stringPreferencesKey("model")
        val CUSTOM_ENDPOINT = stringPreferencesKey("customEndpoint")
        val API_KEY = stringPreferencesKey("apiKey")
        val VOICE = stringPreferencesKey("voice")
        val ANTI_AI = booleanPreferencesKey("antiAI")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val STT_PROVIDER = stringPreferencesKey("sttProvider")
        val STT_ENDPOINT = stringPreferencesKey("sttEndpoint")
        val STT_KEY = stringPreferencesKey("sttKey")
        val STT_MODEL = stringPreferencesKey("sttModel")
        val DG_ENDPOINT = stringPreferencesKey("dgEndpoint")
        val DG_LANGUAGE = stringPreferencesKey("dgLanguage")
        val DG_SMART_FORMAT = booleanPreferencesKey("dgSmartFormat")
        val DG_PUNCTUATE = booleanPreferencesKey("dgPunctuate")
        val DG_NUMERALS = booleanPreferencesKey("dgNumerals")
        val DG_DICTATION = booleanPreferencesKey("dgDictation")
        val DG_PARAGRAPHS = booleanPreferencesKey("dgParagraphs")
        val DG_MEASUREMENTS = booleanPreferencesKey("dgMeasurements")
        val DG_DIARIZE = booleanPreferencesKey("dgDiarize")
        val DG_FILLER_WORDS = booleanPreferencesKey("dgFillerWords")
        val DG_PROFANITY_FILTER = booleanPreferencesKey("dgProfanityFilter")
        val DG_REDACT = stringPreferencesKey("dgRedact")
        val DG_KEYTERMS = booleanPreferencesKey("dgKeyterms")
        val DG_MIP_OPT_OUT = booleanPreferencesKey("dgMipOptOut")
        val DEFAULT_MODE = stringPreferencesKey("defaultMode")
        val DETERMINISTIC_CLEANUP = booleanPreferencesKey("deterministicCleanup")
        val POLISH_LEVEL = stringPreferencesKey("polishLevel")
        val VAD_AUTO_STOP = booleanPreferencesKey("vadAutoStop")
        val BUBBLE_ONLY_ON_FIELDS = booleanPreferencesKey("bubbleOnlyOnFields")
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("hasCompletedOnboarding")
        val PARAKEET_HOTWORDS_EXPERIMENTAL = booleanPreferencesKey("parakeetHotwordsExperimental")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val defaults = Settings()
        Settings(
            provider = p[Keys.PROVIDER] ?: defaults.provider,
            model = p[Keys.MODEL] ?: defaults.model,
            customEndpoint = p[Keys.CUSTOM_ENDPOINT] ?: defaults.customEndpoint,
            apiKey = p[Keys.API_KEY] ?: defaults.apiKey,
            voice = p[Keys.VOICE] ?: defaults.voice,
            antiAI = p[Keys.ANTI_AI] ?: defaults.antiAI,
            temperature = p[Keys.TEMPERATURE] ?: defaults.temperature,
            sttProvider = p[Keys.STT_PROVIDER] ?: defaults.sttProvider,
            sttEndpoint = p[Keys.STT_ENDPOINT] ?: defaults.sttEndpoint,
            sttKey = p[Keys.STT_KEY] ?: defaults.sttKey,
            sttModel = p[Keys.STT_MODEL] ?: defaults.sttModel,
            dgEndpoint = p[Keys.DG_ENDPOINT] ?: defaults.dgEndpoint,
            dgLanguage = p[Keys.DG_LANGUAGE] ?: defaults.dgLanguage,
            dgSmartFormat = p[Keys.DG_SMART_FORMAT] ?: defaults.dgSmartFormat,
            dgPunctuate = p[Keys.DG_PUNCTUATE] ?: defaults.dgPunctuate,
            dgNumerals = p[Keys.DG_NUMERALS] ?: defaults.dgNumerals,
            dgDictation = p[Keys.DG_DICTATION] ?: defaults.dgDictation,
            dgParagraphs = p[Keys.DG_PARAGRAPHS] ?: defaults.dgParagraphs,
            dgMeasurements = p[Keys.DG_MEASUREMENTS] ?: defaults.dgMeasurements,
            dgDiarize = p[Keys.DG_DIARIZE] ?: defaults.dgDiarize,
            dgFillerWords = p[Keys.DG_FILLER_WORDS] ?: defaults.dgFillerWords,
            dgProfanityFilter = p[Keys.DG_PROFANITY_FILTER] ?: defaults.dgProfanityFilter,
            dgRedact = p[Keys.DG_REDACT] ?: defaults.dgRedact,
            dgKeyterms = p[Keys.DG_KEYTERMS] ?: defaults.dgKeyterms,
            dgMipOptOut = p[Keys.DG_MIP_OPT_OUT] ?: defaults.dgMipOptOut,
            defaultMode = p[Keys.DEFAULT_MODE] ?: defaults.defaultMode,
            deterministicCleanup = p[Keys.DETERMINISTIC_CLEANUP] ?: defaults.deterministicCleanup,
            polishLevel = PolishLevel.from(p[Keys.POLISH_LEVEL]),
            vadAutoStop = p[Keys.VAD_AUTO_STOP] ?: defaults.vadAutoStop,
            bubbleOnlyOnFields = p[Keys.BUBBLE_ONLY_ON_FIELDS] ?: defaults.bubbleOnlyOnFields,
            hasCompletedOnboarding = p[Keys.HAS_COMPLETED_ONBOARDING] ?: defaults.hasCompletedOnboarding,
            parakeetHotwordsExperimental = p[Keys.PARAKEET_HOTWORDS_EXPERIMENTAL] ?: defaults.parakeetHotwordsExperimental,
        )
    }

    suspend fun get(): Settings = settings.first()

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[Keys.PROVIDER] = s.provider
            p[Keys.MODEL] = s.model
            p[Keys.CUSTOM_ENDPOINT] = s.customEndpoint
            p[Keys.API_KEY] = s.apiKey
            p[Keys.VOICE] = s.voice
            p[Keys.ANTI_AI] = s.antiAI
            p[Keys.TEMPERATURE] = s.temperature
            p[Keys.STT_PROVIDER] = s.sttProvider
            p[Keys.STT_ENDPOINT] = s.sttEndpoint
            p[Keys.STT_KEY] = s.sttKey
            p[Keys.STT_MODEL] = s.sttModel
            p[Keys.DG_ENDPOINT] = s.dgEndpoint
            p[Keys.DG_LANGUAGE] = s.dgLanguage
            p[Keys.DG_SMART_FORMAT] = s.dgSmartFormat
            p[Keys.DG_PUNCTUATE] = s.dgPunctuate
            p[Keys.DG_NUMERALS] = s.dgNumerals
            p[Keys.DG_DICTATION] = s.dgDictation
            p[Keys.DG_PARAGRAPHS] = s.dgParagraphs
            p[Keys.DG_MEASUREMENTS] = s.dgMeasurements
            p[Keys.DG_DIARIZE] = s.dgDiarize
            p[Keys.DG_FILLER_WORDS] = s.dgFillerWords
            p[Keys.DG_PROFANITY_FILTER] = s.dgProfanityFilter
            p[Keys.DG_REDACT] = s.dgRedact
            p[Keys.DG_KEYTERMS] = s.dgKeyterms
            p[Keys.DG_MIP_OPT_OUT] = s.dgMipOptOut
            p[Keys.DEFAULT_MODE] = s.defaultMode
            p[Keys.DETERMINISTIC_CLEANUP] = s.deterministicCleanup
            p[Keys.POLISH_LEVEL] = s.polishLevel.key
            p[Keys.VAD_AUTO_STOP] = s.vadAutoStop
            p[Keys.BUBBLE_ONLY_ON_FIELDS] = s.bubbleOnlyOnFields
            p[Keys.HAS_COMPLETED_ONBOARDING] = s.hasCompletedOnboarding
            p[Keys.PARAKEET_HOTWORDS_EXPERIMENTAL] = s.parakeetHotwordsExperimental
        }
    }

    /** Persist just the onboarding-complete flag without disturbing other prefs. */
    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { it[Keys.HAS_COMPLETED_ONBOARDING] = complete }
    }
}
