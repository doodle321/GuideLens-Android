package com.guidelens.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * TextToSpeech wrapper enforcing blind-user requirements:
 *  - queue cap (2 pending): fresh info beats stale info
 *  - priority speak: flushes the queue and cuts off non-priority speech
 *  - coalescing: the same sentence is never queued twice
 *  - repeat(): last message on demand (double-tap anywhere)
 *  - watchdog: some engines drop completion callbacks (engine swap, stop()
 *    mid-utterance). Without recovery the queue silently stalls for the whole
 *    session — a 45 s timeout force-advances a stuck utterance.
 *  - TTS language follows the system locale with fallback to English.
 */
class Speaker(private val context: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    @Volatile private var ready = false
    private val lock = Any()
    private val queue = ArrayDeque<Pair<String, Boolean>>()
    private var current: Pair<String, Boolean>? = null
    private var currentId: String? = null
    private var lastText: String? = null
    private var seq = 0L
    private val watchdog = Handler(Looper.getMainLooper())
    private var currentStartedAt = 0L

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        applyLanguage()
        ready = true
        synchronized(lock) { pumpLocked() }
    }

    private fun applyLanguage() {
        val target = if (Locale.getDefault().language == "ur") Locale("ur", "PK") else Locale.US
        val r = tts.setLanguage(target)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }
        tts.setSpeechRate(prefs.rate)
    }

    fun speak(text: String, priority: Boolean = false) {
        if (!prefs.audio) return
        synchronized(lock) {
            if (priority) {
                queue.clear()
                val cur = current
                if (cur != null && !cur.second) {
                    try { tts.stop() } catch (e: Exception) { }
                    current = null
                    currentId = null
                }
            }
            if (current?.first == text) return
            if (queue.any { it.first == text }) return
            if (queue.size >= 2) queue.removeFirst()
            queue.addLast(text to priority)
            pumpLocked()
        }
    }

    private fun pumpLocked() {
        if (!ready || current != null || queue.isEmpty()) return
        val item = queue.removeFirst()
        current = item
        lastText = item.first
        val id = "gl${++seq}"
        currentId = id
        currentStartedAt = System.currentTimeMillis()
        watchdog.removeCallbacksAndMessages(null)
        watchdog.postDelayed({ watchdogAdvance(id) }, WATCHDOG_MS)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { done(id) }
            override fun onError(utteranceId: String?, errorCode: Int) { done(utteranceId) }
            override fun onDone(id: String?) { done(id) }
            private fun done(doneId: String?) {
                synchronized(lock) {
                    if (doneId != null && doneId != currentId) return@synchronized
                    watchdog.removeCallbacksAndMessages(null)
                    current = null
                    currentId = null
                    pumpLocked()
                }
            }
        })
        try {
            tts.speak(item.first, TextToSpeech.QUEUE_ADD, null, id)
        } catch (e: Exception) {
            watchdog.removeCallbacksAndMessages(null)
            current = null
            currentId = null
        }
    }

    /** Fires when an utterance never reported completion — unstick the queue. */
    private fun watchdogAdvance(id: String) {
        synchronized(lock) {
            if (currentId != id) return
            // only declare a stall if this utterance really ran the full window
            if (System.currentTimeMillis() - currentStartedAt < WATCHDOG_MS) return
            current = null
            currentId = null
            pumpLocked()
        }
    }

    fun repeat() {
        lastText?.let { speak(it) }
    }

    fun setRate(r: Float) {
        try { tts.setSpeechRate(r) } catch (e: Exception) { }
    }

    fun shutdown() {
        watchdog.removeCallbacksAndMessages(null)
        try { tts.stop(); tts.shutdown() } catch (e: Exception) { }
    }

    companion object {
        /** Longer than any legitimate utterance, far shorter than "stalled forever". */
        private const val WATCHDOG_MS = 45_000L
    }
}
