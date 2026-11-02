package com.daoyu.tingshu.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import java.io.File
import kotlin.math.pow

/**
 * Per-clip volume correction applied while a multi-role clip plays: the character's own volume setting from the cast
 * page, plus — only through the phone's built-in speaker — the correction for voices whose energy sits below what the
 * speaker can reproduce (see [Loudness.speakerOffsetDb]). Raising goes through [LoudnessEnhancer], which limits peaks,
 * lowering through [MediaPlayer.setVolume].
 */
class PlaybackGain(context: Context, private val attrs: AudioAttributes) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var enhancer: LoudnessEnhancer? = null

    private val offsets = object : LinkedHashMap<String, Double>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > 300
    }

    /** True when media currently comes out of the phone's own speaker (no headphones, no Bluetooth audio). */
    fun onBuiltInSpeaker(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            val devs = am.getAudioDevicesForAttributes(attrs)
            if (devs.isNotEmpty()) return@runCatching devs.all { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }
        }
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in EXTERNAL }
    }.getOrDefault(false)

    /** How much [f] (a cached WAV) should be raised through the speaker; measured once per file. */
    fun speakerOffset(f: File): Double {
        val key = "${f.name}:${f.length()}"
        synchronized(offsets) { offsets[key] }?.let { return it }
        val w = if (f.extension == "wav") Loudness.readWav(f) else null
        val v = if (w == null) 0.0 else Loudness.speakerOffsetDb(w.samples, w.rate).coerceIn(-MAX_CUT, MAX_BOOST)
        synchronized(offsets) { offsets[key] = v }
        return v
    }

    /** Applies [db] to a prepared player (before it starts). */
    fun apply(p: MediaPlayer, db: Double) {
        release()
        when {
            db < -0.5 -> { val f = 10.0.pow(db / 20).toFloat(); p.setVolume(f, f) }
            db > 0.5 -> enhancer = runCatching {
                LoudnessEnhancer(p.audioSessionId).apply { setTargetGain((db * 100).toInt()); enabled = true }
            }.getOrNull()
        }
    }

    fun release() {
        enhancer?.let { runCatching { it.release() } }
        enhancer = null
    }

    companion object {
        const val MAX_BOOST = 8.0
        const val MAX_CUT = 4.0
        private val EXTERNAL = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_HDMI, 26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */,
        )
    }
}
