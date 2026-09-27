package com.umang.fintrack.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.umang.fintrack.data.FinanceDb
import com.umang.fintrack.data.SimBooks
import com.umang.fintrack.notify.Notifier

/** Fires for every incoming SMS; transaction messages become a pending entry + a sticky prompt. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Long SMS arrive in several parts; stitch them back together per sender.
        val bySender = messages.groupBy { it.originatingAddress }
            .mapValues { (_, parts) -> parts.joinToString("") { it.messageBody ?: "" } to parts.first().timestampMillis }

        val sim = SimBooks.simOf(context, intent)

        val pending = goAsync()
        Thread {
            try {
                val db = FinanceDb.get(context)
                bySender.forEach { (sender, value) ->
                    val (body, time) = value
                    db.insertFromSms(sender, body, time, sim)?.let { id -> Notifier.promptFor(context, id) }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

object InboxImporter {
    /**
     * Reads past bank SMS from the inbox. Old messages are saved with their suggested category
     * (no popups) so the history is filled in; you can re-categorise any of them later.
     */
    fun import(context: Context, days: Int = 90): Int {
        val since = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val db = FinanceDb.get(context)
        var added = 0
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.SUBSCRIPTION_ID),
            "${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(1) ?: continue
                val sim = (if (c.isNull(3)) null else SimBooks.slotOfSubscription(c.getInt(3))) ?: 1
                if (db.insertFromSms(c.getString(0), body, c.getLong(2), sim, pending = false) != null) added++
            }
        }
        return added
    }
}
