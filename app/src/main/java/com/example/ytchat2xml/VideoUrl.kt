package com.example.ytchat2xml

/** Extracts the 11-character video ID from the YouTube link formats this app accepts. */
object VideoUrl {
    private const val ID = "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"

    private val bareId = Regex("^[A-Za-z0-9_-]{11}$")

    private val patterns = listOf(
        // youtube.com/watch?v=ID (also m., www., music. and extra query params before v=)
        Regex("""youtube(?:-nocookie)?\.com/watch\?(?:[^#\s]*&)?v=$ID""", RegexOption.IGNORE_CASE),
        // youtube.com/live/ID, /shorts/ID, /embed/ID, /v/ID
        Regex("""youtube(?:-nocookie)?\.com/(?:live|shorts|embed|v)/$ID""", RegexOption.IGNORE_CASE),
        // youtu.be/ID
        Regex("""youtu\.be/$ID""", RegexOption.IGNORE_CASE),
    )

    /** Returns the video ID found in [input] (a link, shared text containing a link, or a bare ID), or null. */
    fun extractId(input: String): String? {
        val text = input.trim()
        if (bareId.matches(text)) return text
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun watchUrl(id: String): String = "https://www.youtube.com/watch?v=$id"
}
