package com.dertefter.wearfiles

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.dertefter.wearfiles.data.TransferAck
import com.dertefter.wearfiles.data.TransferAckRegistry
import com.dertefter.wearfiles.data.TransferProtocol
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.UUID

object AutoTransfer {
    private const val PERIODIC = 9131
    private const val CHANGE = 9132
    private const val MANUAL = 9133
    fun prefs(c: Context) = c.getSharedPreferences("chatgpt_auto", Context.MODE_PRIVATE)
    fun enable(c: Context, node: String) {
        val p = prefs(c)
        p.edit().putString("node", node).putLong("since", System.currentTimeMillis())
            .putBoolean("enabled", true).apply()
        val scheduler = c.getSystemService(JobScheduler::class.java)
        val component = ComponentName(c, AutoTransferJob::class.java)
        scheduler.schedule(JobInfo.Builder(PERIODIC, component)
            .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        watch(c)
    }
    fun watch(c: Context) {
        if (!prefs(c).getBoolean("enabled", false)) return
        c.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(CHANGE, ComponentName(c, AutoTransferJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(2000).setTriggerContentMaxDelay(10000).build())
    }
    fun scanNow(c: Context) {
        if (!prefs(c).getBoolean("enabled", false)) return
        val job = JobInfo.Builder(MANUAL, ComponentName(c, AutoTransferJob::class.java))
            .setOverrideDeadline(0L).build()
        c.getSystemService(JobScheduler::class.java).schedule(job)
    }
    fun disable(c: Context) {
        prefs(c).edit().putBoolean("enabled", false).apply()
        c.getSystemService(JobScheduler::class.java).apply { cancel(PERIODIC); cancel(CHANGE); cancel(MANUAL) }
    }
    fun status(c: Context, value: String) { prefs(c).edit().putString("diagnostic", value).apply() }
    fun isChange(p: JobParameters) = p.jobId == CHANGE
}

class AutoTransferJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = mutableMapOf<Int, Job>()
    companion object { private val lock = kotlinx.coroutines.sync.Mutex() }
    override fun onStartJob(params: JobParameters): Boolean {
        tasks[params.jobId] = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    lock.lock()
                    try { scan() } finally { lock.unlock() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AutoTransfer.status(this@AutoTransferJob, "Błąd skanowania: ${e.javaClass.simpleName}: ${e.message}")
                android.util.Log.w("WearFilesAuto", "Scan failed", e)
            }
            finally {
                if (isActive) {
                    tasks.remove(params.jobId)
                    jobFinished(params, false)
                    if (AutoTransfer.isChange(params)) AutoTransfer.watch(this@AutoTransferJob)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.cancel()
        return true
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun scan() {
        val p = AutoTransfer.prefs(this)
        if (!p.getBoolean("enabled", false)) return
        AutoTransfer.status(this, "Skanowanie folderu Pictures...")
        val node = p.getString("node", null) ?: return
        val reachable = Wearable.getCapabilityClient(this)
            .getCapability("wear_files_app", CapabilityClient.FILTER_REACHABLE).await().nodes
        if (reachable.none { it.id == node }) {
            AutoTransfer.status(this, "Zegarek nieosiągalny przez Wear OS Data Layer")
            return
        }
        val media = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf("_id", "_display_name", "relative_path", "owner_package_name",
            "mime_type", "date_added", "_size", "is_pending")
        val files = mutableListOf<Triple<Uri, String, Long>>()
        var candidates = 0
        var rejected = 0
        contentResolver.query(media, columns, "date_added >= ?",
            arrayOf((p.getLong("since", Long.MAX_VALUE) / 1000).toString()), "date_added ASC")?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(2)?.trimEnd('/') != "Pictures") continue
                if (!Regex("file_[0-9a-fA-F]+[.](png|jpe?g)", RegexOption.IGNORE_CASE).matches(name)) continue
                candidates++
                // The owner column may be unavailable on Android 16.
                // If missing, fall back to the narrow path and filename filter.
                val owner = c.getString(3)
                if (owner != null && owner != "com.openai.chatgpt") { rejected++; continue }
                if (c.getString(4) !in listOf("image/png", "image/jpeg")) continue
                if (c.getInt(7) != 0 || c.getLong(6) <= 0) continue
                if (p.getBoolean("sent:"+name, false)) continue
                files.add(Triple(ContentUris.withAppendedId(media, c.getLong(0)), name, c.getLong(6)))
            }
        }
        AutoTransfer.status(this, "Dopasowane: ${files.size}, nazwy: $candidates, inne źródło: $rejected")
        for ((uri, name, size) in files.take(10)) {
            if (!p.getBoolean("enabled", false) || p.getString("node", null) != node) break
            if (p.getBoolean("sent:"+name, false)) continue
            AutoTransfer.status(this, "Wysyłanie: $name")
            if (send(node, uri, name, size)) {
                p.edit().putBoolean("sent:"+name, true).commit()
                AutoTransfer.status(this, "Potwierdzono: $name")
                NotificationHelper(this).showTransferNotification(name, com.dertefter.wearfiles.data.TransferStatus.SUCCESS)
            } else {
                AutoTransfer.status(this, "Nieudana wysyłka: $name")
                break
            }
        }
    }

    private suspend fun send(node: String, uri: Uri, name: String, size: Long): Boolean {
        val client = Wearable.getChannelClient(this)
        val id = UUID.randomUUID().toString()
        val ack = TransferAckRegistry.register(id)
        var channel: com.google.android.gms.wearable.ChannelClient.Channel? = null
        try {
            return withTimeout(120000) {
                val opened = client.openChannel(node, "${TransferProtocol.CHANNEL_PATH_PREFIX}/$id/$size/${Uri.encode(name)}").await()
                channel = opened
                val input = contentResolver.openInputStream(uri) ?: return@withTimeout false
                input.use { source ->
                    client.getOutputStream(opened).await().use { out ->
                        if (source.copyTo(out) != size) return@withTimeout false
                        out.flush()
                    }
                }
                ack.await() == TransferAck.SUCCESS
            }
        } catch (e: TimeoutCancellationException) { return false }
        finally {
            TransferAckRegistry.unregister(id)
            withContext(NonCancellable) { channel?.let { runCatching { withTimeout(5000) { client.close(it).await() } } } }
        }
    }
}
