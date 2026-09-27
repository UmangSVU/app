package com.umang.fintrack.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val inr: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 0
}

fun formatMoney(amount: Double): String = inr.format(amount)

fun formatDateTime(millis: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(millis))
fun formatDay(millis: Long): String = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(millis))
fun formatMonth(millis: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(millis))

val DebitRed = Color(0xFFD32F2F)

/** Distinct colours so each category / account is easy to tell apart. */
private val PALETTE = listOf(
    0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFF8E24AA, 0xFF00ACC1,
    0xFFF4511E, 0xFF3949AB, 0xFF7CB342, 0xFFD81B60, 0xFF6D4C41, 0xFF00897B,
    0xFFFDD835, 0xFF5E35B1, 0xFF546E7A, 0xFFC0CA33,
).map { Color(it) }

fun categoryColor(index: Int): Color = PALETTE[Math.floorMod(index, PALETTE.size)]
val CreditGreen = Color(0xFF2E7D32)

@Composable
fun FinTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8AB4F8), secondary = Color(0xFF81C995))
    } else {
        lightColorScheme(primary = Color(0xFF1A56DB), secondary = Color(0xFF2E7D32))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
