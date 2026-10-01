package bayern.kickner.ruinborn.client.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** German number and time formats for the UI. */
object Fmt {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
    private val time = DateTimeFormatter.ofPattern("HH:mm")

    /** 12345 → "12.345" */
    fun num(n: Long): String {
        val s = kotlin.math.abs(n).toString().reversed().chunked(3).joinToString(".").reversed()
        return if (n < 0) "-$s" else s
    }

    fun num(n: Int): String = num(n.toLong())

    /** Short form for tight bars: 12.345 → "12,3 Tsd.", 1.234.567 → "1,23 Mio." */
    fun short(n: Long): String = when {
        n >= 10_000_000 -> String.format(java.util.Locale.GERMANY, "%.1f Mio.", n / 1_000_000.0)
        n >= 1_000_000 -> String.format(java.util.Locale.GERMANY, "%.2f Mio.", n / 1_000_000.0)
        n >= 100_000 -> "${n / 1000} Tsd."
        n >= 10_000 -> String.format(java.util.Locale.GERMANY, "%.1f Tsd.", n / 1000.0)
        else -> num(n)
    }

    /** Readable duration: "1 h 43 min", "5 min 3 s", "12 s". */
    fun duration(ms: Long): String {
        val s = (ms + 999) / 1000
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> "$d d $h h"
            h > 0 -> "$h h $m min"
            m > 0 -> if (sec > 0) "$m min $sec s" else "$m min"
            else -> "$sec s"
        }
    }

    /** Countdown "01:02:03" or "02:13". */
    fun countdown(ms: Long): String {
        val s = ((ms.coerceAtLeast(0)) + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(dateTime)
    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(time)

    /** Percent with decimal comma: 0.05 → "5 %", 0.015 → "1,5 %". */
    fun percent(v: Double): String {
        val p = v * 100
        return if (kotlin.math.abs(p - kotlin.math.round(p)) < 1e-9) "${kotlin.math.round(p).toLong()} %"
        else String.format(java.util.Locale.GERMANY, "%.1f %%", p)
    }
}
