package com.pocketgammu.core.gammu.engine

import com.pocketgammu.engine.LogSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine's log: every frame sent and received, driver and IrDA messages
 * (GammuDroid's log page). Keeps the most recent [maxLines] lines.
 */
class EngineLog(private val maxLines: Int = 6000) : LogSink {
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized override fun log(line: String) {
        lines.addLast("${time.format(Date())}  $line")
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized fun text(): String = lines.joinToString("\n")

    @Synchronized fun clear() = lines.clear()
}
