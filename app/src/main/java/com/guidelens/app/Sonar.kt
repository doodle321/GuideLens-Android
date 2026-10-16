package com.guidelens.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * "Sonar" earcon channel: short beeps whose rate and pitch rise as the nearest
 * obstacle approaches, stereo-panned toward its side. Runs on its own thread,
 * generated in-memory, bypasses TTS entirely.
 *
 * Uses ONE stream-mode AudioTrack created up front (no per-beep allocation)
 * and USAGE_ASSISTANCE_SONIFICATION so the safety channel plays through
 * Do-Not-Disturb instead of competing with notification sounds.
 */
class Sonar {

    @Volatile var urgency = 0f
    @Volatile var pan = 0f
    @Volatile var muted = false

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { thread?.join(300) } catch (e: Exception) { }
        thread = null
    }

    fun silence() { urgency = 0f }

    private fun loop() {
        val sr = 22050
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = minBuf.coerceAtLeast(sr * 2)   // 2 s of stereo PCM headroom
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
        } catch (e: Exception) {
            try { track?.release() } catch (ignored: Exception) { }
            return
        }

        var lastBeat = 0L
        try {
            while (running) {
                val u = urgency
                if (!muted && u > 0.03f) {
                    beep(track, freqHz = 620f + 520f * u, durMs = 70, volume = 0.45f, pan = pan)
                    sleepMs((1000f - 750f * u).toLong().coerceAtLeast(120L))
                } else {
                    val now = System.currentTimeMillis()
                    if (!muted && now - lastBeat > 10_000L) {
                        lastBeat = now
                        beep(track, 520f, 40, 0.10f, 0f)
                    }
                    sleepMs(200)
                }
            }
        } finally {
            try { track.pause(); track.flush(); track.release() } catch (e: Exception) { }
        }
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { }
    }

    private fun beep(track: AudioTrack, freqHz: Float, durMs: Int, volume: Float, pan: Float) {
        try {
            val sr = 22050
            val n = sr * durMs / 1000
            val buf = ByteArray(4 * n)
            val lg0 = kotlin.math.cos((pan + 1f) * (PI / 4f)).toFloat()
            val rg0 = sin((pan + 1f) * (PI / 4f)).toFloat()
            for (i in 0 until n) {
                val t = i.toFloat() / sr
                val env = min(1f, i.toFloat() / (sr * 0.01f)) *
                        min(1f, (n - i).toFloat() / (n * 0.35f))
                val s = (sin(2.0 * PI * freqHz * t) * 32767.0 * volume * env).toInt()
                val l = (s * lg0).toInt().toShort()
                val r = (s * rg0).toInt().toShort()
                buf[4 * i] = (l.toInt() and 0xFF).toByte()
                buf[4 * i + 1] = (l.toInt() shr 8 and 0xFF).toByte()
                buf[4 * i + 2] = (r.toInt() and 0xFF).toByte()
                buf[4 * i + 3] = (r.toInt() shr 8 and 0xFF).toByte()
            }
            track.write(buf, 0, buf.size)
            sleepMs(durMs + 40L)
        } catch (e: Exception) { }
    }
}
