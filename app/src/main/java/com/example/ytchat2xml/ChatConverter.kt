package com.example.ytchat2xml

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.Reader
import java.io.Writer

/**
 * Converts a yt-dlp `.live_chat.json` file (one JSON object per line) into the comment XML that
 * JKCommentViewer writes: UTF-8 with BOM, CRLF line endings, one indented `<chat ...>` per comment
 * with the full set of `youtube_*` / flag attributes and a `yt_text_N / yt_emojiId_N / yt_emojiUrl_N`
 * triple for every message run.
 *
 * Streams line by line so long streams do not need to fit in memory.
 */
object ChatConverter {

    private const val CRLF = "\r\n"
    private const val COLOR_MODERATOR = "#5f84f1"
    private const val COLOR_MEMBER_EVENT = "#0f9d58"

    private enum class Kind { TEXT, PAID, STICKER, MEMBER, GIFT_SEND, GIFT_RECV }

    /** One message run: either plain text, or an emoji (text is empty, emojiId/emojiUrl are set). */
    private class Part(val text: String, val emojiId: String, val emojiUrl: String, val body: String)

    private class Comment(
        val date: Long,
        val vpos: Long,
        val userId: String,
        val userName: String,
        val iconUrl: String,
        val stickerUrl: String,
        val badgeUrl: String,
        val superChat: Boolean,
        val newSponsor: Boolean,
        val giftSend: Boolean,
        val giftRecv: Boolean,
        val sponsor: Boolean,
        val amountText: String,
        val moderator: Boolean,
        val owner: Boolean,
        val verified: Boolean,
        val headerPrimaryText: String,
        val mail: String,
        val parts: List<Part>?,
        val body: String,
    )

    /** Reads chat JSON lines from [input], writes XML to [output], and returns the number of comments written. */
    fun convert(input: Reader, output: Writer): Int {
        val reader = if (input is BufferedReader) input else BufferedReader(input)
        output.write("﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>$CRLF<packet>$CRLF")
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
                val item = action.asObj()?.obj("addChatItemAction")?.obj("item") ?: continue
                val comment = parseItem(item, offsetMs) ?: continue
                output.write(toXml(comment))
                count++
            }
        }
        output.write("</packet>")
        output.flush()
        return count
    }

    // ---- JSON -> Comment --------------------------------------------------------------------

    private fun parseItem(item: JsonObject, offsetMs: String?): Comment? {
        item.obj("liveChatTextMessageRenderer")?.let { return build(it, it, offsetMs, Kind.TEXT) }
        item.obj("liveChatPaidMessageRenderer")?.let { return build(it, it, offsetMs, Kind.PAID) }
        item.obj("liveChatPaidStickerRenderer")?.let { return build(it, it, offsetMs, Kind.STICKER) }
        item.obj("liveChatMembershipItemRenderer")?.let { return build(it, it, offsetMs, Kind.MEMBER) }
        item.obj("liveChatSponsorshipsGiftRedemptionAnnouncementRenderer")
            ?.let { return build(it, it, offsetMs, Kind.GIFT_RECV) }
        item.obj("liveChatSponsorshipsGiftPurchaseAnnouncementRenderer")?.let {
            // Author details live in the header of a gift purchase announcement.
            val header = it.obj("header")?.obj("liveChatSponsorshipsHeaderRenderer") ?: return null
            return build(it, header, offsetMs, Kind.GIFT_SEND)
        }
        return null
    }

    private fun build(renderer: JsonObject, author: JsonObject, offsetMs: String?, kind: Kind): Comment? {
        val parts: List<Part>? = when (kind) {
            Kind.TEXT, Kind.PAID, Kind.GIFT_RECV -> partsOf(renderer.obj("message"))
            else -> null
        }

        var body = when (kind) {
            Kind.TEXT, Kind.PAID, Kind.GIFT_RECV -> parts.orEmpty().joinToString("") { it.body }
            Kind.STICKER -> ""
            Kind.MEMBER -> {
                val sub = textOf(renderer.obj("headerSubtext"))
                val extra = partsOf(renderer.obj("message")).joinToString("") { it.body }
                if (extra.isBlank()) sub else "$sub $extra"
            }
            Kind.GIFT_SEND -> textOf(author.obj("primaryText"))
        }

        val amountText = if (kind == Kind.PAID || kind == Kind.STICKER) textOf(renderer.obj("purchaseAmountText")) else ""
        // A Super Chat / sticker without a message would otherwise be blank; show the amount instead.
        if (body.isBlank() && amountText.isNotEmpty()) body = amountText
        if (body.isBlank()) return null

        // Badges. Like JKCommentViewer: the badge image comes from the first badge only, and
        // "sponsor" is true for members and moderators.
        var member = false
        var moderator = false
        var owner = false
        var verified = false
        var badgeUrl = ""
        val badges = author.get("authorBadges")
        if (badges != null && badges.isJsonArray) {
            for ((index, badge) in badges.asJsonArray.withIndex()) {
                val br = badge.asObj()?.obj("liveChatAuthorBadgeRenderer") ?: continue
                val thumb = br.obj("customThumbnail")
                if (thumb != null) {
                    member = true
                    if (index == 0) badgeUrl = lastUrl(thumb)
                }
                val iconType = br.obj("icon")?.str("iconType") ?: ""
                if (iconType == "MODERATOR") moderator = true
                if (iconType == "OWNER") owner = true
                if (iconType.startsWith("VERIFIED")) verified = true
            }
        }

        val mail = when {
            kind == Kind.MEMBER || kind == Kind.GIFT_SEND -> COLOR_MEMBER_EVENT
            moderator -> COLOR_MODERATOR
            else -> ""
        }

        return Comment(
            date = (renderer.str("timestampUsec")?.trim()?.toLongOrNull() ?: 0L) / 1_000_000L,
            vpos = vposOf(offsetMs, renderer.obj("timestampText")?.str("simpleText")),
            userId = renderer.str("authorExternalChannelId") ?: author.str("authorExternalChannelId") ?: "",
            userName = textOf(author.obj("authorName")),
            iconUrl = lastUrl(author.obj("authorPhoto")),
            stickerUrl = if (kind == Kind.STICKER) lastUrl(renderer.obj("sticker")) else "",
            badgeUrl = badgeUrl,
            superChat = kind == Kind.PAID || kind == Kind.STICKER,
            newSponsor = kind == Kind.MEMBER || kind == Kind.GIFT_RECV,
            giftSend = kind == Kind.GIFT_SEND,
            giftRecv = kind == Kind.GIFT_RECV,
            sponsor = member || moderator,
            amountText = amountText,
            moderator = moderator,
            owner = owner,
            verified = verified,
            headerPrimaryText = if (kind == Kind.MEMBER) textOf(renderer.obj("headerPrimaryText")) else "",
            mail = mail,
            parts = if (parts != null && parts.isNotEmpty()) parts else null,
            body = body,
        )
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

    private fun partsOf(message: JsonObject?): List<Part> {
        if (message == null) return emptyList()
        val simple = message.str("simpleText")
        if (simple != null) return listOf(Part(simple, "", "", simple))
        val runs = message.get("runs")
        if (runs == null || !runs.isJsonArray) return emptyList()
        val out = ArrayList<Part>()
        for (run in runs.asJsonArray) {
            val r = run.asObj() ?: continue
            val text = r.str("text")
            if (text != null) {
                out.add(Part(text, "", "", text))
                continue
            }
            val emoji = r.obj("emoji") ?: continue
            val id = emoji.str("emojiId") ?: ""
            out.add(Part("", id, lastUrl(emoji.obj("image")), emojiBody(emoji, id)))
        }
        return out
    }

    /** Emoji appear in the comment text as their `:shortcut:` name, like JKCommentViewer. */
    private fun emojiBody(emoji: JsonObject, emojiId: String): String {
        val shortcuts = emoji.get("shortcuts")
        if (shortcuts != null && shortcuts.isJsonArray) {
            val first = shortcuts.asJsonArray.firstOrNull()
            if (first != null && first.isJsonPrimitive && first.asString.isNotEmpty()) return first.asString
        }
        return emojiId
    }

    /** Text of a YouTube text object: `simpleText`, or the joined `runs`. */
    private fun textOf(obj: JsonObject?): String {
        if (obj == null) return ""
        val simple = obj.str("simpleText")
        if (simple != null) return simple
        return partsOf(obj).joinToString("") { it.body }
    }

    /** URL of the last (largest) entry in a `thumbnails` list, or "". */
    private fun lastUrl(obj: JsonObject?): String {
        val thumbs = obj?.get("thumbnails")
        if (thumbs == null || !thumbs.isJsonArray) return ""
        val last = thumbs.asJsonArray.lastOrNull().asObj() ?: return ""
        val url = last.str("url") ?: return ""
        return if (url.startsWith("//")) "https:$url" else url
    }

    // ---- Comment -> XML ---------------------------------------------------------------------

    private fun toXml(c: Comment): String {
        val sb = StringBuilder(512)
        sb.append("  <chat date=\"").append(c.date)
            .append("\" user_id=\"").append(escapeAttr(c.userId))
            .append("\" id=\"\" vpos=\"").append(c.vpos)
            .append("\" mail=\"").append(escapeAttr(c.mail))
            .append("\" premium=\"\" user_name=\"").append(escapeAttr(c.userName))
            .append("\" youtube_icon_url=\"").append(escapeAttr(c.iconUrl))
            .append("\" youtube_sticker_url=\"").append(escapeAttr(c.stickerUrl))
            .append("\" badge_image_url=\"").append(escapeAttr(c.badgeUrl))
            .append("\" youtube_super_chat=\"").append(c.superChat)
            .append("\" new_sponsor=\"").append(c.newSponsor)
            .append("\" gift_send=\"").append(c.giftSend)
            .append("\" gift_recv=\"").append(c.giftRecv)
            .append("\" sponsor=\"").append(c.sponsor)
            .append("\" amount_text=\"").append(escapeAttr(c.amountText))
            .append("\" moderator=\"").append(c.moderator)
            .append("\" owner=\"").append(c.owner)
            .append("\" verified=\"").append(c.verified)
            .append("\" header_primary_text=\"").append(escapeAttr(c.headerPrimaryText))
            .append('"')
        val parts = c.parts
        if (parts != null) {
            for ((i, p) in parts.withIndex()) {
                sb.append(" yt_text_").append(i).append("=\"").append(escapeAttr(p.text)).append('"')
                    .append(" yt_emojiId_").append(i).append("=\"").append(escapeAttr(p.emojiId)).append('"')
                    .append(" yt_emojiUrl_").append(i).append("=\"").append(escapeAttr(p.emojiUrl)).append('"')
            }
        }
        sb.append('>').append(escapeText(c.body)).append("</chat>").append(CRLF)
        return sb.toString()
    }

    /** Escapes `& < >` (comment text keeps `"` as is, like JKCommentViewer). */
    fun escapeText(s: String): String = escape(s, false)

    /** Escapes `& < > "` for use inside a double-quoted attribute. */
    fun escapeAttr(s: String): String = escape(s, true)

    private fun escape(s: String, quotes: Boolean): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' && quotes -> sb.append("&quot;")
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                // Control characters and U+FFFE/U+FFFF are not allowed in XML 1.0, so drop them.
                ch < ' ' || ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ---- small Gson helpers -----------------------------------------------------------------

    private fun JsonElement?.asObj(): JsonObject? =
        if (this != null && this.isJsonObject) this.asJsonObject else null

    private fun JsonObject.obj(key: String): JsonObject? = this.get(key).asObj()

    private fun JsonObject.str(key: String): String? {
        val e = this.get(key)
        return if (e != null && e.isJsonPrimitive) e.asString else null
    }
}
