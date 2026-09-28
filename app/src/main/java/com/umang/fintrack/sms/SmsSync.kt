package com.umang.fintrack.sms

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.umang.fintrack.notify.Notifier
import com.umang.fintrack.ui.CategorizeActivity
import java.util.concurrent.TimeUnit

/**
 * Safety net for SMS the live receiver missed (phones in battery-saving mode sometimes don't
 * wake the app for an incoming SMS). It re-reads the SMS inbox, which is on the phone, so no
 * internet is needed, and turns anything new into the usual category prompt.
 *
 * Runs when the app opens, after a reboot, every 15 minutes in the background, when the phone
 * goes back online, and from the ⟳ refresh button.
 */
object SmsSync {
    private const val PREFS = "sms_sync"
    private const val KEY_LAST = "last_scan"
    private const val OVERLAP = 60L * 60 * 1000

    fun canReadSms(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Automatic catch-up since the previous check (the first time: the last 3 days). */
    @Synchronized
    fun catchUp(context: Context, openPopup: Boolean = true): List<Long> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        val since = if (last == 0L) System.currentTimeMillis() - 3 * InboxImporter.DAY else last - OVERLAP
        return scan(context, since, openPopup)
    }

    /** The ⟳ button: look again at the last [days] days. */
    @Synchronized
    fun refresh(context: Context, days: Int = 7): List<Long> =
        scan(context, System.currentTimeMillis() - days * InboxImporter.DAY, openPopup = false)

    private fun scan(context: Context, since: Long, openPopup: Boolean): List<Long> {
        if (!canReadSms(context)) return emptyList()
        val now = System.currentTimeMillis()
        val ids = InboxImporter.scanInbox(context, since, pending = true)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, now).apply()
        ids.forEach { Notifier.show(context, it) }
        if (openPopup && ids.isNotEmpty() && Settings.canDrawOverlays(context)) {
            context.startActivity(CategorizeActivity.intent(context, ids.first()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return ids
    }

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "sms-catch-up", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CatchUpWorker>(15, TimeUnit.MINUTES).build(),
        )
        // Extra run as soon as the phone is back online (the case where SMS were seen to be missed).
        wm.enqueueUniquePeriodicWork(
            "sms-catch-up-online", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CatchUpWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}

class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SmsSync.catchUp(applicationContext)
        return Result.success()
    }
}
