package com.umang.fintrack.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.umang.fintrack.R
import com.umang.fintrack.data.FinanceDb
import com.umang.fintrack.data.SimBooks
import com.umang.fintrack.data.Transaction
import com.umang.fintrack.parser.TxnType
import com.umang.fintrack.sms.SmsSync
import com.umang.fintrack.ui.CategorizeActivity
import com.umang.fintrack.ui.formatMoney

object Notifier {
    private const val CHANNEL = "categorize"
    const val ACTION_ACCEPT = "com.umang.fintrack.ACCEPT"
    const val ACTION_REPOST = "com.umang.fintrack.REPOST"
    const val ACTION_IGNORE = "com.umang.fintrack.IGNORE"
    const val EXTRA_ID = "txn_id"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Categorize transactions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks you to pick a category for every debit / credit SMS"
                setShowBadge(true)
            }
        )
    }

    /**
     * Opens the category popup right away. Android only lets a background app start an activity
     * when it may draw over other apps, so without that permission the full-screen notification
     * intent (shown on the lock screen / as a heads-up) does the job instead.
     */
    fun promptFor(context: Context, id: Long) {
        show(context, id)
        if (Settings.canDrawOverlays(context)) {
            context.startActivity(CategorizeActivity.intent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun show(context: Context, id: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 33
        ) return
        val txn = FinanceDb.get(context).getTransaction(id) ?: return
        if (!txn.pending) return
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, id.toInt(), CategorizeActivity.intent(context, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val accept = PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_ACCEPT).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ignore = PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_IGNORE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // If the user swipes the notification away it is posted again: it only goes once a category is chosen.
        val repost = PendingIntent.getBroadcast(
            context, id.toInt(),
            Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_REPOST).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title(txn))
            .setSubText(SimBooks.name(context, txn.sim))
            .setContentText("${txn.merchant ?: txn.sourceLabel} · Suggested: ${txn.suggestedCategory}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(
                        txn.merchant?.let { (if (txn.type == TxnType.DEBIT) "To: " else "From: ") + it },
                        txn.sourceLabel,
                        "Suggested category: ${txn.suggestedCategory}",
                        "Tap to choose a category",
                    ).joinToString("\n")
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setDeleteIntent(repost)
            .addAction(0, "✓ ${txn.suggestedCategory}", accept)
            .addAction(0, "Change", open)
            .addAction(0, "Not a transaction", ignore)
            .build()

        NotificationManagerCompat.from(context).notify(id.toInt(), notification)
    }

    fun cancel(context: Context, id: Long) = NotificationManagerCompat.from(context).cancel(id.toInt())

    fun showAllPending(context: Context) = FinanceDb.get(context).pendingIds().forEach { show(context, it) }

    private fun title(t: Transaction): String {
        val verb = if (t.type == TxnType.DEBIT) "debited" else "credited"
        return "${formatMoney(t.amount)} $verb" + (t.bank?.let { " · $it" } ?: "") + (t.account?.let { " $it" } ?: "")
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Notifier.EXTRA_ID, -1)
        if (id < 0) return
        val pending = goAsync()
        Thread {
            try {
                val db = FinanceDb.get(context)
                when (intent.action) {
                    Notifier.ACTION_ACCEPT -> db.getTransaction(id)?.let {
                        db.categorize(id, it.suggestedCategory)
                        Notifier.cancel(context, id)
                    }
                    Notifier.ACTION_REPOST -> Notifier.show(context, id)
                    Notifier.ACTION_IGNORE -> {
                        db.ignore(id)
                        Notifier.cancel(context, id)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Thread {
            try {
                SmsSync.schedule(context)
                Notifier.showAllPending(context)
                SmsSync.catchUp(context, openPopup = false)
            } finally { pending.finish() }
        }.start()
    }
}
