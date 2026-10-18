package com.daoyu.tingshu.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.daoyu.tingshu.data.TtsSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The phone's own offline TTS engine – the last fallback. */
class SystemTts(private val context: Context) : TtsEngine {
    override val id = Engines.SYSTEM
    override val ext = "wav"

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val lock = Mutex()

    override fun voiceKey(s: TtsSettings) = "system|${s.edgeRate}"

    private suspend fun engine(): TextToSpeech {
        tts?.let { if (ready?.isCompleted == true && ready?.getCompleted() == true) return it }
        val d = CompletableDeferred<Boolean>()
        ready = d
        val t = TextToSpeech(context.applicationContext) { status -> d.complete(status == TextToSpeech.SUCCESS) }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { pending.remove(utteranceId)?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { pending.remove(utteranceId)?.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { pending.remove(utteranceId)?.complete(false) }
        })
        tts = t
        if (!withTimeout(8000) { d.await() }) throw TtsException("手机没有可用的系统语音引擎")
        t.language = Locale.SIMPLIFIED_CHINESE
        return t
    }

    override suspend fun synthesize(text: String, s: TtsSettings, out: File): SynthInfo = lock.withLock {
        val t = engine()
        t.setSpeechRate((1f + s.edgeRate / 100f).coerceIn(0.5f, 2f))
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        val r = t.synthesizeToFile(text, Bundle(), out, id)
        if (r != TextToSpeech.SUCCESS) { pending.remove(id); throw TtsException("系统语音合成失败") }
        val ok = withTimeout(60_000) { done.await() }
        if (!ok || !out.exists() || out.length() < 100) throw TtsException("系统语音合成失败")
        SynthInfo()
    }
}
