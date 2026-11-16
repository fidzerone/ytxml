package com.example.ytchat2xml

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

private const val PROCESS_ID = "live-chat-download"

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var convertButton: Button
    private lateinit var cancelButton: Button
    private lateinit var updateButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var engineReady = false
    @Volatile private var busy = false
    @Volatile private var converting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        convertButton = findViewById(R.id.convertButton)
        cancelButton = findViewById(R.id.cancelButton)
        updateButton = findViewById(R.id.updateButton)
        progress = findViewById(R.id.progress)
        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)

        convertButton.setOnClickListener { startConversion() }
        cancelButton.setOnClickListener { cancelConversion() }
        updateButton.setOnClickListener { startUpdate() }

        // Lets you use YouTube's Share button -> this app.
        val launchIntent = intent
        if (launchIntent != null && launchIntent.action == Intent.ACTION_SEND) {
            val shared = launchIntent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) urlInput.setText(shared.trim())
        }

        refreshUi()
        log("Preparing yt-dlp (the first launch takes a few seconds)...")
        worker.execute { initEngine() }
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    // ---- engine ---------------------------------------------------------------------------

    private fun initEngine() {
        try {
            YoutubeDL.init(applicationContext)
            engineReady = true
            val version = try {
                YoutubeDL.version(applicationContext)
            } catch (e: Exception) {
                null
            }
            ui {
                log("Ready. yt-dlp version: ${version ?: "unknown"}")
                refreshUi()
            }
        } catch (e: Exception) {
            ui { log("Could not start yt-dlp: ${tail(e.message ?: e.toString())}") }
        }
    }

    private fun startUpdate() {
        busy = true
        refreshUi()
        log("Updating yt-dlp...")
        worker.execute {
            try {
                val status = YoutubeDL.updateYoutubeDL(applicationContext)
                ui { log("Update finished: ${status ?: "no status"}") }
            } catch (e: Exception) {
                ui { log("Update failed: ${tail(e.message ?: e.toString())}") }
            } finally {
                busy = false
                ui { refreshUi() }
            }
        }
    }

    // ---- conversion -----------------------------------------------------------------------

    private fun startConversion() {
        val id = VideoUrl.extractId(urlInput.text.toString())
        if (id == null) {
            log("That is not a YouTube video link. Use youtube.com/live/ID or youtube.com/watch?v=ID.")
            return
        }
        logView.text = ""
        statusView.text = ""
        busy = true
        converting = true
        refreshUi()
        worker.execute {
            try {
                convertVideo(id)
            } catch (e: YoutubeDL.CanceledException) {
                ui { log("Cancelled.") }
            } catch (e: Exception) {
                ui { log("Failed: ${tail(e.message ?: e.toString())}") }
            } finally {
                busy = false
                converting = false
                ui {
                    statusView.text = ""
                    refreshUi()
                }
            }
        }
    }

    private fun cancelConversion() {
        // destroyProcessById shells out, so keep it off the UI thread.
        Thread { YoutubeDL.destroyProcessById(PROCESS_ID) }.start()
    }

    private fun convertVideo(id: String) {
        val workDir = File(cacheDir, "chat-work")
        workDir.deleteRecursively()
        workDir.mkdirs()
        try {
            ui { log("Downloading live chat for $id (long streams can take several minutes)...") }

            val request = YoutubeDLRequest(VideoUrl.watchUrl(id))
            request.addOption("--skip-download")
            request.addOption("--write-subs")
            request.addOption("--sub-langs", "live_chat")
            request.addOption("--no-playlist")
            request.addOption("--ignore-no-formats-error")
            request.addOption("-o", File(workDir, "%(id)s.%(ext)s").absolutePath)

            val onLine: (Float, Long, String) -> Unit = { _, _, line -> showStatus(line) }
            YoutubeDL.execute(request, PROCESS_ID, onLine)

            val chatFile = workDir.listFiles()?.firstOrNull { it.name.endsWith(".live_chat.json") }
            if (chatFile == null) {
                ui {
                    log(
                        "No live chat found for this video. Chat replay may be disabled, the stream may " +
                            "still be live or not processed yet, or the video may be private or members-only."
                    )
                }
                return
            }

            ui { log("Converting to XML...") }
            val count = saveXml(id, chatFile)
            ui {
                if (count == 0) {
                    log("The chat file contained no text messages, so no XML was saved.")
                } else {
                    log("Done: $count comments saved to Downloads/$id.xml")
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Streams [chatFile] into a new XML file in the public Downloads folder. Returns the comment count. */
    private fun saveXml(id: String, chatFile: File): Int {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$id.xml")
            put(MediaStore.Downloads.MIME_TYPE, "text/xml")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in Downloads")

        var count = 0
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open the new file in Downloads")
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                chatFile.bufferedReader(Charsets.UTF_8).use { reader ->
                    count = ChatConverter.convert(reader, writer)
                }
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        if (count == 0) {
            resolver.delete(uri, null, null)
            return 0
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        return count
    }

    // ---- UI helpers -----------------------------------------------------------------------

    private fun showStatus(line: String) {
        val text = line.trim()
        if (text.isNotEmpty()) ui { statusView.text = text }
    }

    private fun refreshUi() {
        convertButton.isEnabled = engineReady && !busy
        updateButton.isEnabled = engineReady && !busy
        cancelButton.isEnabled = converting
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun log(message: String) {
        logView.append(message + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread { block() }
    }

    private fun tail(text: String, max: Int = 600): String =
        if (text.length <= max) text.trim() else "..." + text.takeLast(max).trim()
}
