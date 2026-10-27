package com.blecky.assistant.brain

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import java.io.File

/**
 * Free AI models that run entirely on the phone with Google's LiteRT-LM: no internet, no key.
 * Downloaded once from Hugging Face (huggingface.co/litert-community).
 */
class OfflineModels(private val context: Context) {
    class Model(
        val id: String,
        val label: String,
        private val repo: String,
        val file: String,
        val sizeMb: Int,
        /** Phones with less memory than this will struggle. */
        val minRamGb: Int,
        /** Big enough to use tools (calls, reminders...) reliably. */
        val canUseTools: Boolean,
    ) {
        val url get() = "https://huggingface.co/litert-community/$repo/resolve/main/$file"
    }

    private val prefs = context.getSharedPreferences("offline_models", Context.MODE_PRIVATE)

    fun path(model: Model) = File(context.getExternalFilesDir("models"), model.file)

    fun isInstalled(model: Model) = path(model).let { it.exists() && it.length() > model.sizeMb * 900_000L } &&
        prefs.getLong("download_${model.id}", -1) == -1L

    fun installed() = MODELS.filter(::isInstalled)

    /** The chosen model if it's downloaded, else the most capable one that is. */
    fun active(chosenId: String): Model? =
        MODELS.firstOrNull { it.id == chosenId && isInstalled(it) } ?: installed().maxByOrNull { it.sizeMb }

    /** Total memory in GB, to recommend a model that fits. */
    fun phoneRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    /** Downloads with Android's download manager, so big files survive leaving the app. */
    suspend fun download(model: Model, progress: (String) -> Unit) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val target = path(model)
        var id = prefs.getLong("download_${model.id}", -1)
        if (id == -1L) {
            target.delete()
            val request = DownloadManager.Request(Uri.parse(model.url))
                .setTitle("Blecky: ${model.label}")
                .setDescription("On-phone AI brain")
                .setDestinationUri(Uri.fromFile(target))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            id = manager.enqueue(request)
            prefs.edit().putLong("download_${model.id}", id).apply()
        }
        while (true) {
            val (status, done, total) = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (!c.moveToFirst()) return@use null
                Triple(
                    c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                )
            } ?: Triple(DownloadManager.STATUS_FAILED, 0L, 0L)
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove("download_${model.id}").apply()
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove("download_${model.id}").apply()
                    target.delete()
                    throw IllegalStateException("The download failed. Check your internet and try again.")
                }
                else -> {
                    val pct = if (total > 0) " ${done * 100 / total}%" else ""
                    progress("Downloading ${model.label} (${model.sizeMb} MB):$pct. You can leave the app; it keeps going.")
                }
            }
            delay(1_000)
        }
    }

    fun delete(model: Model) {
        path(model).delete()
    }

    companion object {
        val MODELS = listOf(
            // Tested before shipping: Gemma 4 E2B gave the best answers by far (including Hindi and
            // Gujarati) at about 1.5 GB of RAM; Qwen3 0.6B and Gemma 4 E4B didn't make the cut.
            Model("lfm2-700m", "LFM2 700M by Liquid AI, fastest, English only", "LFM2-700M", "LFM2-700M_int4.litertlm", 481, 3, canUseTools = false),
            Model("qwen3-1.7b", "Qwen3 1.7B by Alibaba, smaller download", "Qwen3-1.7B", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", 977, 3, canUseTools = true),
            Model(
                "gemma4-e2b", "Gemma 4 E2B by Google, smartest, great at Hindi and Gujarati",
                "gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm", 2588, 4, canUseTools = true,
            ),
        )

        /** The best model for a phone with [ramGb] of memory. */
        fun recommended(ramGb: Double): Model = MODELS.last { it.minRamGb <= ramGb + 0.5 }
    }
}
