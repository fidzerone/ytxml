package com.example.ytchat2xml

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.Reader
import java.io.Writer

/**
 * Converts a yt-dlp `.live_chat.json` file (one JSON object per line) into Niconico-style comment XML.
 *
 * Port of Bookyakuno's convert_chat_to_xml.py, streaming line by line so long streams do not need
 * to fit in memory. Differences from the script: every action in a line is handled (not only the
 * first), custom/standard emoji are handled, `&` is escaped first so entities are not double-escaped,
 * no padding spaces are added around the comment text, and vpos uses the exact millisecond offset.
 */
object ChatConverter {

    /** Reads chat JSON lines from [input], writes XML to [output], and returns the number of comments written. */
    fun convert(input: Reader, output: Writer): Int {
        val reader = if (input is BufferedReader) input else BufferedReader(input)
        output.write("<packet>\n")
        var count = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val root: JsonElement = try {
                JsonParser.parseString(line)
            } catch (e: RuntimeException) {
                continue
            }
            val rootObj = root.asObj() ?: continue
            val replay = rootObj.obj("replayChatItemAction") ?: continue
            val actions = replay.get("actions")
            if (actions == null || !actions.isJsonArray) continue
            val offsetMs = replay.str("videoOffsetTimeMsec") ?: rootObj.str("videoOffsetTimeMsec")

            for (action in actions.asJsonArray) {
                val renderer = action.asObj()
                    ?.obj("addChatItemAction")
                    ?.obj("item")
                    ?.obj("liveChatTextMessageRenderer")
                    ?: continue
                val text = messageText(renderer)
                if (text.isBlank()) continue
                val user = renderer.obj("authorName")?.str("simpleText") ?: ""
                val vpos = vposOf(offsetMs, renderer.obj("timestampText")?.str("simpleText"))
                count++
                output.write("<chat vpos=\"$vpos\" no=\"$count\" user_id=\"${escape(user)}\">${escape(text)}</chat>\n")
            }
        }
        output.write("</packet>\n")
        output.flush()
        return count
    }

    /** vpos is in 1/100 s. Prefers the exact offset in ms; falls back to the "H:MM:SS" text. Never negative. */
    internal fun vposOf(offsetMs: String?, timestampText: String?): Long {
        val ms = offsetMs?.trim()?.toLongOrNull()
        if (ms != null) return maxOf(0L, ms / 10)
        return parseTimestamp(timestampText) * 100
    }

    private fun parseTimestamp(text: String?): Long {
        if (text == null) return 0L
        val t = text.trim()
        if (t.startsWith("-")) return 0L
        val parts = t.split(":").map { it.toLongOrNull() ?: return 0L }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    private fun messageText(renderer: JsonObject): String {
        val runs = renderer.obj("message")?.get("runs")
        if (runs == null || !runs.isJsonArray) return ""
        val sb = StringBuilder()
        for (run in runs.asJsonArray) {
            val r = run.asObj() ?: continue
            val text = r.str("text")
            if (text != null) {
                sb.append(text)
                continue
            }
            val emoji = r.obj("emoji") ?: continue
            sb.append(emojiText(emoji))
        }
        return sb.toString()
    }

    private fun emojiText(emoji: JsonObject): String {
        if (emoji.bool("isCustomEmoji")) {
            val shortcuts = emoji.get("shortcuts")
            if (shortcuts != null && shortcuts.isJsonArray) {
                val first = shortcuts.asJsonArray.firstOrNull()
                if (first != null && first.isJsonPrimitive) return first.asString
            }
            return ""
        }
        return emoji.str("emojiId") ?: ""
    }

    /** XML-escapes [s] (text and attribute safe) and applies the same character clean-up as the original script. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#039;")
                ' ', ' ', ' ', '\n', '\r', '\t' -> sb.append(' ')
                '–', '—' -> sb.append('-')
                else -> if (ch >= ' ' && ch != '￾' && ch != '￿') sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun JsonElement?.asObj(): JsonObject? =
        if (this != null && this.isJsonObject) this.asJsonObject else null

    private fun JsonObject.obj(key: String): JsonObject? = this.get(key).asObj()

    private fun JsonObject.str(key: String): String? {
        val e = this.get(key)
        return if (e != null && e.isJsonPrimitive) e.asString else null
    }

    private fun JsonObject.bool(key: String): Boolean {
        val e = this.get(key)
        return e != null && e.isJsonPrimitive && e.asBoolean
    }
}
