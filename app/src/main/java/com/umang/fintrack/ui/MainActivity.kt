package com.umang.fintrack.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.umang.fintrack.data.Category
import com.umang.fintrack.data.FinanceDb
import com.umang.fintrack.data.TRANSFER
import com.umang.fintrack.data.Transaction
import com.umang.fintrack.notify.Notifier
import com.umang.fintrack.parser.CategorySuggester
import com.umang.fintrack.parser.TxnType
import com.umang.fintrack.sms.InboxImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        setContent { FinTheme { MainScreen(resumeTick) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        // Re-post prompts for anything still uncategorised (e.g. after the app was force-stopped).
        val app = applicationContext
        Thread { Notifier.showAllPending(app) }.start()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    History("History", Icons.Filled.List),
    Categories("Categories", Icons.Filled.Star),
    Setup("Setup", Icons.Filled.Settings),
}

private fun monthStart(offset: Int): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, offset)
}.timeInMillis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(resumeTick: Int) {
    val context = LocalContext.current
    val db = remember { FinanceDb.get(context) }
    val changes by db.changes.collectAsState()
    var tab by remember { mutableStateOf(if (hasSmsPermission(context)) Tab.Home else Tab.Setup) }
    var monthOffset by remember { mutableIntStateOf(0) }
    var txns by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }

    LaunchedEffect(changes, monthOffset, resumeTick) {
        val (t, c, p) = withContext(Dispatchers.IO) {
            Triple(db.transactionsBetween(monthStart(monthOffset), monthStart(monthOffset + 1)), db.categories(), db.pendingIds())
        }
        txns = t; categories = c; pending = p
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("FinTrack") }) },
        floatingActionButton = {
            if (tab == Tab.Home || tab == Tab.History) {
                FloatingActionButton(onClick = {
                    context.startActivity(CategorizeActivity.intent(context, CategorizeActivity.NEW_MANUAL))
                }) { Icon(Icons.Filled.Add, contentDescription = "Add cash transaction") }
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        val emojiOf = categories.associate { it.name to it.emoji }
        when (tab) {
            Tab.Home -> HomeTab(modifier, txns, pending, emojiOf, monthOffset) { monthOffset += it }
            Tab.History -> HistoryTab(modifier, txns, emojiOf, monthOffset) { monthOffset += it }
            Tab.Categories -> CategoriesTab(modifier, categories)
            Tab.Setup -> SetupTab(modifier, resumeTick)
        }
    }
}

@Composable
private fun MonthSwitcher(offset: Int, onShift: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onShift(-1) }) { Text("‹ Prev") }
        Text(formatMonth(monthStart(offset)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(enabled = offset < 0, onClick = { onShift(1) }) { Text("Next ›") }
    }
}

private fun Transaction.effectiveCategory() = category ?: suggestedCategory

@Composable
private fun HomeTab(
    modifier: Modifier, txns: List<Transaction>, pending: List<Long>, emojiOf: Map<String, String>,
    monthOffset: Int, onShift: (Int) -> Unit,
) {
    val context = LocalContext.current
    val counted = txns.filter { it.effectiveCategory() != TRANSFER }
    val spent = counted.filter { it.type == TxnType.DEBIT }.sumOf { it.amount }
    val received = counted.filter { it.type == TxnType.CREDIT }.sumOf { it.amount }
    val byCategory = counted.filter { it.type == TxnType.DEBIT }.groupBy { it.effectiveCategory() }
        .mapValues { (_, v) -> v.sumOf { it.amount } }.entries.sortedByDescending { it.value }
    val byAccount = txns.filter { it.type == TxnType.DEBIT }.groupBy { it.sourceLabel }
        .mapValues { (_, v) -> v.sumOf { it.amount } to v.size }.entries.sortedByDescending { it.value.first }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pending.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pending.size} transaction(s) need a category", modifier = Modifier.weight(1f))
                    Button(onClick = { context.startActivity(CategorizeActivity.intent(context, pending.first())) }) { Text("Review") }
                }
            }
        }
        item { MonthSwitcher(monthOffset, onShift) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Spent", spent, DebitRed, Modifier.weight(1f))
                StatCard("Received", received, CreditGreen, Modifier.weight(1f))
            }
        }
        item {
            Text("Net: ${formatMoney(received - spent)}  ·  ${txns.size} transactions  ·  transfers excluded",
                style = MaterialTheme.typography.bodySmall)
        }
        if (byCategory.isNotEmpty()) {
            item { Text("Spending by category", style = MaterialTheme.typography.titleMedium) }
            items(byCategory, key = { "c" + it.key }) { (name, amount) ->
                Column {
                    Row {
                        Text("${emojiOf[name] ?: "🏷️"} $name", modifier = Modifier.weight(1f))
                        Text(formatMoney(amount), fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { if (spent > 0) (amount / spent).toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        }
        if (byAccount.isNotEmpty()) {
            item { Text("Spending by bank / card", style = MaterialTheme.typography.titleMedium) }
            items(byAccount, key = { "a" + it.key }) { (label, v) ->
                Row {
                    Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${v.second} · ${formatMoney(v.first)}", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (txns.isEmpty()) item {
            Text("No transactions this month yet. New bank SMS will show up here automatically — " +
                "or import past SMS from the Setup tab.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatCard(label: String, amount: Double, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(formatMoney(amount), style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistoryTab(
    modifier: Modifier, txns: List<Transaction>, emojiOf: Map<String, String>, monthOffset: Int, onShift: (Int) -> Unit,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf<TxnType?>(null) }
    val shown = txns.filter { filter == null || it.type == filter }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { MonthSwitcher(monthOffset, onShift) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "All", TxnType.DEBIT to "Debits", TxnType.CREDIT to "Credits").forEach { (t, label) ->
                    androidx.compose.material3.FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(label) })
                }
            }
        }
        shown.groupBy { formatDay(it.timestamp) }.forEach { (day, list) ->
            item(key = "d$day") { Text(day, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp)) }
            items(list, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { context.startActivity(CategorizeActivity.intent(context, t.id)) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(emojiOf[t.effectiveCategory()] ?: "🏷️", style = MaterialTheme.typography.titleLarge)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(t.merchant ?: t.effectiveCategory(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (t.pending) "⚠ Needs category · " else t.effectiveCategory() + " · ") + t.sourceLabel,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (t.pending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            t.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        }
                        Text(
                            (if (t.type == TxnType.DEBIT) "−" else "+") + formatMoney(t.amount),
                            color = if (t.type == TxnType.DEBIT) DebitRed else CreditGreen, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        if (shown.isEmpty()) item { Text("Nothing here for this month.") }
    }
}

@Composable
private fun CategoriesTab(modifier: Modifier, categories: List<Category>) {
    val context = LocalContext.current
    val db = remember { FinanceDb.get(context) }
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ Add category") }
        }
        items(categories, key = { it.name }) { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name)
                    Text(when (c.kind) { "EXPENSE" -> "Expense"; "INCOME" -> "Income"; else -> "Expense & income" },
                        style = MaterialTheme.typography.bodySmall)
                }
                if (c.name != CategorySuggester.OTHER && c.name != CategorySuggester.INCOME) {
                    IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${c.name}") }
                }
            }
        }
    }
    if (adding) NewCategoryDialog(onDismiss = { adding = false }) { name, emoji ->
        adding = false
        scope.launch(Dispatchers.IO) { db.addCategory(name, emoji) }
    }
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("Existing transactions keep this label; it just won't be offered any more.") },
            confirmButton = { TextButton(onClick = { deleting = null; scope.launch(Dispatchers.IO) { db.deleteCategory(c.name) } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun hasSmsPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SetupTab(modifier: Modifier, resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var importing by remember { mutableStateOf(false) }

    // Re-read on every resume so the status updates after returning from system settings.
    val refresh = resumeTick + tick
    val sms = remember(refresh) { hasSmsPermission(context) && granted(context, Manifest.permission.READ_SMS) }
    val notifications = remember(refresh) {
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val overlay = remember(refresh) { Settings.canDrawOverlays(context) }
    val fullScreen = remember(refresh) {
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Give these permissions once so every debit/credit SMS pops up for a category.",
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            PermissionRow("1. Read SMS", "Detects bank, card & UPI messages. Nothing leaves your phone.", sms) {
                val perms = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(perms.toTypedArray())
            }
        }
        item {
            PermissionRow("2. Notifications", "Sticky reminder that stays until you choose a category.", notifications) {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        item {
            PermissionRow("3. Display over other apps", "Lets the category pop-up open instantly while you use your phone.", overlay) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
        }
        if (Build.VERSION.SDK_INT >= 34) item {
            PermissionRow("4. Full-screen alerts", "Shows the pop-up on the lock screen.", fullScreen) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
            }
        }
        item {
            Text("If Android says the SMS permission is a \"restricted setting\": open App info → ⋮ menu → " +
                "\"Allow restricted settings\", then come back and tap Allow again.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Import past SMS", style = MaterialTheme.typography.titleMedium)
                    Text("Reads the last 90 days of bank messages and files them under the suggested category " +
                        "(no pop-ups). You can change any of them from History.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = sms && !importing, onClick = {
                        importing = true
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { InboxImporter.import(context) }
                            importing = false
                            Toast.makeText(context, "Imported $n transactions", Toast.LENGTH_LONG).show()
                        }
                    }) { Text(if (importing) "Importing…" else "Import last 90 days") }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, ok: Boolean, onGrant: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (ok) Text("✓ On", color = CreditGreen, fontWeight = FontWeight.Bold)
            else Button(onClick = onGrant) { Text("Allow") }
        }
    }
}
