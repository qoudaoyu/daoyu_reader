package com.daoyu.tingshu.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReaderSettings(
    val fontSizeSp: Float = 20f,
    val lineSpacing: Float = 1.75f,      // multiple of font size
    val paraSpacing: Float = 0.38f,      // extra space after paragraph, in em
    val marginH: Float = 26f,            // dp
    val font: String = "wenkai",         // wenkai / serif / system / custom
    val customFontPath: String? = null,
    val theme: Int = 1,                  // index into ReaderThemes
    val followSystemDark: Boolean = true,
    val pageAnim: String = "slide",      // slide / cover / none
    val volumeKeyTurn: Boolean = true,
    val keepScreenOn: Boolean = true,
    val brightness: Float = -1f,         // -1 = follow system
    val showInfoBar: Boolean = true,
    val indent: Boolean = true,
    val boldText: Boolean = false,
)

@Serializable
data class TtsSettings(
    val engine: String = "edge",                       // edge / cosy / gemini / system
    // Microsoft Edge
    val edgeVoice: String = "zh-CN-XiaoxiaoNeural",
    val edgeRate: Int = 0,                             // percent, -50..100
    val edgePitch: Int = 0,
    // Aliyun CosyVoice (DashScope)
    val cosyModel: String = "cosyvoice-v3-flash",
    val cosyVoice: String = "longxiu_v3",
    val cosyRate: Float = 1.0f,                        // 0.5..2.0
    val cosyEndpoint: String = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/",
    val cosyPricePer10k: Float = 1.0f,                 // 元 / 万计费字符（汉字算 2 个）
    val cosyMonthlyLimit: Int = 0,                     // 万字, 0 = unlimited
    // Google Gemini
    val geminiModel: String = "gemini-3.8-flash-tts",
    val geminiVoice: String = "Charon",
    val geminiStyle: String = "评书艺人说书的口吻，声情并茂、娓娓道来，对白要有人物的情绪",
    val geminiRateUsdPerM: Float = 9f,                 // audio output price, USD per 1M tokens
    val geminiMonthlyLimit: Int = 0,                   // 万字, 0 = unlimited
    val geminiFallback: String = "cosy",               // when Gemini is unreachable
    // Aliyun Qwen3-TTS (same key as CosyVoice)
    val qwenVoice: String = "Vincent",
    val qwenVoiceChecked: Boolean = false,             // 1.0.4: the old default "Elias" turned out to be female
    val qwenStyle: String = "说书人的口吻，从容不迫、娓娓道来",
    val qwenPricePer10k: Float = 0.8f,                 // 元 / 万计费字符
    val qwenMonthlyLimit: Int = 0,                     // 万字, 0 = unlimited
    // multi-role mode: free Edge narrator + Qwen character voices, speakers worked out by Qwen-Plus
    val dramaNarratorVoice: String = "zh-CN-YunxiNeural",
    val directorModel: String = "qwen3-max",
    val directorInPrice: Float = 2.5f,                 // 元 / 百万 tokens
    val directorOutPrice: Float = 10f,
    val directorChecked: Boolean = false,
    val grokRescue: Boolean = true,                    // parts refused by the Aliyun content filter go to Grok (if its key is set and reachable)              // 1.3.3: default moved from qwen-plus to qwen3-max (as accurate, 2.5× faster)
    val dramaDesignVoices: Boolean = true,             // main characters get a custom designed voice (0.2 元 each)
    val dramaVoiceEngine: String = "doubao",           // dialogue voices: doubao (豆包 2.0) / qwen; doubao needs its key
    val doubaoPricePer10k: Float = 2.8f,               // 元 / 万字 (resource pack: 28 元 / 10 万字)
    val doubaoMonthlyLimit: Int = 0,                   // 万字, 0 = unlimited
    // general
    val prefetch: Int = 3,
    val sleepMinutes: Int = 0,
)

private val Context.ds by preferencesDataStore("settings")

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private val readerKey = stringPreferencesKey("reader")
    private val ttsKey = stringPreferencesKey("tts")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> decode(s: String?, def: T, f: (String) -> T): T = try { if (s == null) def else f(s) } catch (_: Exception) { def }

    val reader: StateFlow<ReaderSettings> = context.ds.data
        .map { p -> decode(p[readerKey], ReaderSettings()) { json.decodeFromString<ReaderSettings>(it) } }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { context.ds.data.first().let { p -> decode(p[readerKey], ReaderSettings()) { json.decodeFromString<ReaderSettings>(it) } } })

    val tts: StateFlow<TtsSettings> = context.ds.data
        .map { p -> decode(p[ttsKey], TtsSettings()) { json.decodeFromString<TtsSettings>(it) } }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { context.ds.data.first().let { p -> decode(p[ttsKey], TtsSettings()) { json.decodeFromString<TtsSettings>(it) } } })

    suspend fun updateReader(f: (ReaderSettings) -> ReaderSettings) {
        context.ds.edit { it[readerKey] = json.encodeToString(ReaderSettings.serializer(), f(reader.value)) }
    }

    suspend fun updateTts(f: (TtsSettings) -> TtsSettings) {
        context.ds.edit { it[ttsKey] = json.encodeToString(TtsSettings.serializer(), f(tts.value)) }
    }
}

/** API keys, encrypted with the Android Keystore; never leave the phone. */
class SecureKeys(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_keys", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("secure_keys_fallback", Context.MODE_PRIVATE)
    }

    var cosyKey: String
        get() = prefs.getString("cosy", "") ?: ""
        set(v) = prefs.edit().putString("cosy", v.trim()).apply()

    var geminiKey: String
        get() = prefs.getString("gemini", "") ?: ""
        set(v) = prefs.edit().putString("gemini", v.trim()).apply()

    /** Volcengine (豆包语音) new-console API key. */
    /** xAI Grok key (analysis only; needs the proxy). */
    var grokKey: String
        get() = prefs.getString("grok", "") ?: ""
        set(v) = prefs.edit().putString("grok", v.trim()).apply()

    var volcKey: String
        get() = prefs.getString("volc", "") ?: ""
        set(v) = prefs.edit().putString("volc", v.trim()).apply()
}
