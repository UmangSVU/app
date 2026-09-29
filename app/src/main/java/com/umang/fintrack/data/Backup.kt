package com.umang.fintrack.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.umang.fintrack.R
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Daily backup of everything (transactions, categories, learned rules, SIM names) as one JSON
 * file. It is kept on the phone, copied to Downloads/FinTrack, and offered for email with one
 * tap. The app itself never uses the internet: your email app does the sending.
 * On a new phone, Setup → Restore merges a backup file back in.
 */
object Backup {
    private const val PREFS = "backup"
    private const val CHANNEL = "backup"
    private const val NOTIFICATION_ID = 900_001
    private const val KEEP_FILES = 10

    fun email(context: Context): String = prefs(context).getString("email", "") ?: ""
    fun setEmail(context: Context, email: String) = prefs(context).edit().putString("email", email.trim()).apply()
    fun lastBackup(context: Context): Long = prefs(context).getLong("last", 0L)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Writes today's backup and returns the file (inside the app, shareable via FileProvider). */
    fun create(context: Context): File {
        val json = JSONObject()
            .put("app", "FinTrack")
            .put("format", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("data", FinanceDb.get(context).exportTables())
            .put("settings", JSONObject().apply {
                SimBooks.SIMS.forEach { put("sim_name_$it", SimBooks.name(context, it)) }
                put("backup_email", email(context))
            })
            .toString()

        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val file = File(dir, "FinTrack-backup-${today()}.json")
        file.writeText(json)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP_FILES)?.forEach { it.delete() }
        runCatching { copyToDownloads(context, file) }
        prefs(context).edit().putLong("last", System.currentTimeMillis()).apply()
        return file
    }

    /** A second copy outside the app, so it survives an uninstall (Android 10+). */
    private fun copyToDownloads(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29) return
        val resolver = context.contentResolver
        val folder = Environment.DIRECTORY_DOWNLOADS + "/FinTrack/"
        val existing: Uri? = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(file.name, folder), null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) else null }
        val uri = existing ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
        }) ?: return
        resolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Opens the email app with the backup attached and your address filled in. */
    fun emailIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email(context)).filter { it.isNotBlank() }.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, "FinTrack backup ${today()}")
            putExtra(Intent.EXTRA_TEXT, "FinTrack backup from ${today()}.\n\nTo restore on a new phone: install FinTrack, " +
                "save this attachment, then open Setup → Restore from backup and pick the file.")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("backup", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Email backup").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Merges a backup file into this phone's data. Returns the number of transactions added. */
    fun restore(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not open the file")
        val json = JSONObject(text)
        require(json.optString("app") == "FinTrack") { "This is not a FinTrack backup file" }
        val added = FinanceDb.get(context).importTables(json.getJSONObject("data"))
        json.optJSONObject("settings")?.let { s ->
            SimBooks.SIMS.forEach { sim ->
                val name = s.optString("sim_name_$sim")
                if (name.isNotBlank() && SimBooks.name(context, sim) == "SIM $sim") SimBooks.setName(context, sim, name)
            }
            val mail = s.optString("backup_email")
            if (mail.isNotBlank() && email(context).isBlank()) setEmail(context, mail)
        }
        return added
    }

    /** Every day at about 9 PM. */
    fun schedule(context: Context) {
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "daily-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(next.timeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** Daily run: save the file, then remind you to email it (one tap). */
    fun runDaily(context: Context) {
        val file = create(context)
        if (email(context).isBlank()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily backup", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID, emailIntent(context, file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Today's FinTrack backup is ready")
                .setContentText("Tap to email it to ${email(context)}")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Backup.runDaily(applicationContext)
        return Result.success()
    }
}
