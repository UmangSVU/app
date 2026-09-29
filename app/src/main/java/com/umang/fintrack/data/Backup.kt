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
import android.provider.OpenableColumns
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
import com.umang.fintrack.ui.MainActivity
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Daily backup of everything (transactions, categories, learned rules, SIM names) as one JSON
 * file that is overwritten each day, so there is only ever one backup file.
 *
 * You link a file in Google Drive once (Android's "save to" picker); after that the app rewrites
 * that same file every day and the Drive app uploads it. FinTrack itself never uses the internet.
 * A copy is also kept in Downloads/FinTrack. On a new phone, Setup → Restore merges it back in.
 */
object Backup {
    const val FILE_NAME = "FinTrack-backup.json"
    private const val PREFS = "backup"
    private const val CHANNEL = "backup"
    private const val NOTIFICATION_ID = 900_001

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun lastBackup(context: Context): Long = prefs(context).getLong("last", 0L)
    fun lastDriveBackup(context: Context): Long = prefs(context).getLong("last_drive", 0L)

    /** The Drive file picked in Setup, or null when not linked yet. */
    fun driveUri(context: Context): Uri? = prefs(context).getString("drive_uri", null)?.let(Uri::parse)

    /** Remembers the picked Drive file and keeps permission to write it after reboots. */
    fun linkDrive(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs(context).edit().putString("drive_uri", uri.toString()).apply()
    }

    fun unlinkDrive(context: Context) {
        driveUri(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs(context).edit().remove("drive_uri").remove("last_drive").apply()
    }

    /** File name of the linked Drive file, for display. */
    fun driveFileName(context: Context): String? = driveUri(context)?.let { uri ->
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: FILE_NAME
    }

    private fun backupJson(context: Context): String = JSONObject()
        .put("app", "FinTrack")
        .put("format", 1)
        .put("createdAt", System.currentTimeMillis())
        .put("data", FinanceDb.get(context).exportTables())
        .put("settings", JSONObject().apply {
            SimBooks.SIMS.forEach { put("sim_name_$it", SimBooks.name(context, it)) }
        })
        .toString()

    /**
     * Writes the backup to the phone, Downloads and (if linked) Google Drive, replacing the
     * previous one. Returns the local file and whether the Drive copy was written.
     */
    fun backupNow(context: Context): Pair<File, Boolean> {
        val json = backupJson(context)
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        // Only ever one file: drop the dated files older versions created.
        dir.listFiles()?.filter { it.name != FILE_NAME }?.forEach { it.delete() }
        val file = File(dir, FILE_NAME).apply { writeText(json) }
        runCatching { copyToDownloads(context, file) }
        prefs(context).edit().putLong("last", System.currentTimeMillis()).apply()

        val drive = driveUri(context)?.let { uri -> runCatching { writeTo(context, uri, json) }.isSuccess } ?: false
        if (drive) prefs(context).edit().putLong("last_drive", System.currentTimeMillis()).apply()
        return file to drive
    }

    private fun writeTo(context: Context, uri: Uri, json: String) {
        val resolver = context.contentResolver
        // "wt" truncates first; some providers only support plain "w", which still replaces the file.
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri, "w")
        (out ?: error("Cannot write to the Drive file")).use { it.write(json.toByteArray()) }
    }

    /** A second copy outside the app, so it survives an uninstall (Android 10+). Overwritten daily. */
    private fun copyToDownloads(context: Context, file: File) {
        if (Build.VERSION.SDK_INT < 29) return
        val resolver = context.contentResolver
        val folder = Environment.DIRECTORY_DOWNLOADS + "/FinTrack/"
        val existing: Uri? = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, folder), null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) else null }
        val uri = existing ?: resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
        }) ?: return
        resolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Manual fallback: share the file to any app (Drive, WhatsApp to yourself, email…). */
    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "FinTrack backup")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("backup", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share backup").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    /** Daily run. Silent when all went well; a notification only if Drive isn't linked or failed. */
    fun runDaily(context: Context) {
        val (_, drive) = backupNow(context)
        if (drive) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        notifyProblem(
            context,
            if (driveUri(context) == null) "Link Google Drive for automatic backups"
            else "Couldn't update the Drive backup",
            "Saved on the phone only. Tap to open Setup → Backup.",
        )
    }

    private fun notifyProblem(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Daily backup", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SETUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(context).notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
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
