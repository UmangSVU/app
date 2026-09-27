package com.umang.fintrack.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SubscriptionManager

/**
 * Each SIM keeps its own separate book of accounts. SMS are filed by the SIM slot they
 * arrived on; the names ("Personal", "Business", …) are yours to choose.
 */
object SimBooks {
    val SIMS = listOf(1, 2)
    private const val PREFS = "sim_books"

    fun name(context: Context, sim: Int): String =
        prefs(context).getString("name_$sim", null)?.takeIf { it.isNotBlank() } ?: "SIM $sim"

    fun setName(context: Context, sim: Int, name: String) =
        prefs(context).edit().putString("name_$sim", name.trim()).apply()

    /** The book the main screen is showing (also used for cash entries). */
    fun selected(context: Context): Int = prefs(context).getInt("selected", 1)
    fun select(context: Context, sim: Int) = prefs(context).edit().putInt("selected", sim).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** SIM slot (1 or 2) of an incoming SMS broadcast. */
    fun simOf(context: Context, intent: Intent): Int {
        val extras = intent.extras ?: return 1
        // Standard slot extra (Android 11+), then the extras phone makers commonly add.
        for (key in listOf("android.telephony.extra.SLOT_INDEX", "slot", "simSlot", "slot_id", "simId", "phone")) {
            if (extras.containsKey(key)) {
                val v = extras.getInt(key, -1)
                if (v in 0..1) return v + 1
            }
        }
        for (key in listOf("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription")) {
            if (extras.containsKey(key)) {
                val sub = extras.getInt(key, -1)
                slotOfSubscription(sub)?.let { return it }
            }
        }
        return 1
    }

    /** SIM slot (1 or 2) for an inbox SMS's subscription id. */
    fun slotOfSubscription(subId: Int): Int? {
        if (subId < 0 || Build.VERSION.SDK_INT < 29) return null
        val slot = SubscriptionManager.getSlotIndex(subId)
        return if (slot in 0..1) slot + 1 else null
    }
}
