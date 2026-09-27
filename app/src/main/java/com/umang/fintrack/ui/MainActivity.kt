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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
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
import com.umang.fintrack.data.SimBooks
import com.umang.fintrack.data.TRANSFER_CATEGORIES
import com.umang.fintrack.data.KIND_EXPENSE
import com.umang.fintrack.data.KIND_INCOME
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
    var sim by remember { mutableIntStateOf(SimBooks.selected(context)) }
    var namesVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(changes, monthOffset, resumeTick, sim) {
        val (t, c, p) = withContext(Dispatchers.IO) {
            Triple(db.transactionsBetween(sim, monthStart(monthOffset), monthStart(monthOffset + 1)), db.categories(), db.pendingIds())
        }
        txns = t; categories = c; pending = p
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("FinTrack") })
                if (tab != Tab.Categories) {
                    // Each SIM is a separate book of accounts.
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimBooks.SIMS.forEach { s ->
                            FilterChip(
                                selected = sim == s,
                                onClick = { sim = s; SimBooks.select(context, s) },
                                label = { Text("📱 " + remember(namesVersion, s) { SimBooks.name(context, s) }) },
                            )
                        }
                    }
                }
            }
        },
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
            Tab.Home -> HomeTab(modifier, txns, pending, categories, monthOffset) { monthOffset += it }
            Tab.History -> HistoryTab(modifier, txns, emojiOf, monthOffset) { monthOffset += it }
            Tab.Categories -> CategoriesTab(modifier, categories)
            Tab.Setup -> SetupTab(modifier, resumeTick) { namesVersion++ }
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
    modifier: Modifier, txns: List<Transaction>, pending: List<Long>, categories: List<Category>,
    monthOffset: Int, onShift: (Int) -> Unit,
) {
    val context = LocalContext.current
    val emojiOf = categories.associate { it.name to it.emoji }
    val colorOf = { name: String -> categoryColor(categories.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: name.hashCode()) }

    val live = txns.filter { !it.ignored }
    val counted = live.filter { it.effectiveCategory() !in TRANSFER_CATEGORIES }
    val debits = counted.filter { it.type == TxnType.DEBIT }
    val credits = counted.filter { it.type == TxnType.CREDIT }
    val transfers = live.filter { it.effectiveCategory() in TRANSFER_CATEGORIES }
    val spent = debits.sumOf { it.countedAmount }
    val received = credits.sumOf { it.countedAmount }
    val forOthers = debits.sumOf { it.othersShare }

    fun categoryGroups(list: List<Transaction>, total: Double, amountColor: Color) =
        list.groupBy { it.effectiveCategory() }.map { (name, v) ->
            val sum = v.sumOf { it.countedAmount }
            BreakdownGroup(
                key = name, title = "${emojiOf[name] ?: "🏷️"} $name", color = colorOf(name),
                amount = sum, amountColor = amountColor,
                subtitle = "${v.size} transaction${if (v.size == 1) "" else "s"} · ${percent(sum, total)}",
                fraction = if (total > 0) (sum / total).toFloat() else 0f,
                txns = v.sortedByDescending { it.timestamp }, showCategory = false,
            )
        }.sortedByDescending { it.amount }

    val accountGroups = live.groupBy { it.sourceLabel }.entries
        .sortedByDescending { (_, v) -> v.sumOf { it.amount } }
        .mapIndexed { i, (label, v) ->
            val out = v.filter { it.type == TxnType.DEBIT }.sumOf { it.amount }
            val inn = v.filter { it.type == TxnType.CREDIT }.sumOf { it.amount }
            BreakdownGroup(
                key = label, title = "🏦 $label", color = categoryColor(i + 3),
                amount = inn - out, amountColor = if (inn - out >= 0) CreditGreen else DebitRed,
                subtitle = "Out ${formatMoney(out)} · In ${formatMoney(inn)} · ${v.size} txns",
                fraction = null, txns = v.sortedByDescending { it.timestamp }, showCategory = true,
            )
        }

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                StatCard("Income", received, CreditGreen, Modifier.weight(1f))
            }
        }
        item {
            Column {
                val net = received - spent
                Text("Saved this month: ${formatMoney(net)}", fontWeight = FontWeight.SemiBold,
                    color = if (net >= 0) CreditGreen else DebitRed)
                Text("${live.size} transactions · transfers between your own accounts are not counted",
                    style = MaterialTheme.typography.bodySmall)
                if (forOthers > 0) {
                    Text("Paid for others in group bills: ${formatMoney(forOthers)} (not in Spent)",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (debits.isNotEmpty()) item(key = "spend") {
            BreakdownSection("💸 Spending by category", spent, DebitRed, categoryGroups(debits, spent, DebitRed))
        }
        if (credits.isNotEmpty()) item(key = "income") {
            BreakdownSection("💰 Income by category", received, CreditGreen, categoryGroups(credits, received, CreditGreen))
        }
        if (accountGroups.isNotEmpty()) item(key = "accounts") {
            BreakdownSection("🏦 Banks & cards (full amounts)", null, MaterialTheme.colorScheme.primary, accountGroups)
        }
        if (transfers.isNotEmpty()) item(key = "transfers") {
            val t = transfers.sumOf { it.amount }
            BreakdownSection("🔁 Transfers (not counted)", t, MaterialTheme.colorScheme.outline, listOf(
                BreakdownGroup("transfers", "Between your own accounts", MaterialTheme.colorScheme.outline, t,
                    MaterialTheme.colorScheme.onSurface, "${transfers.size} transactions", null,
                    transfers.sortedByDescending { it.timestamp }, showCategory = true)
            ))
        }
        if (live.isEmpty()) item {
            Text("No transactions this month yet. New bank SMS will show up here automatically — " +
                "or import past SMS from the Setup tab.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun percent(part: Double, total: Double) = if (total > 0) "${Math.round(part / total * 100)}%" else "–"

private class BreakdownGroup(
    val key: String, val title: String, val color: Color, val amount: Double, val amountColor: Color,
    val subtitle: String, val fraction: Float?, val txns: List<Transaction>, val showCategory: Boolean,
)

/** A coloured card of rows; tapping a row expands its transactions, and each of those opens the transaction. */
@Composable
private fun BreakdownSection(title: String, total: Double?, accent: Color, groups: List<BreakdownGroup>) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(setOf<String>()) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(accent.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            total?.let { Text(formatMoney(it), color = accent, fontWeight = FontWeight.Bold) }
        }
        groups.forEachIndexed { index, g ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            val open = g.key in expanded
            Column(
                Modifier.fillMaxWidth()
                    .clickable { expanded = if (open) expanded - g.key else expanded + g.key }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 6.dp, height = 36.dp).clip(RoundedCornerShape(3.dp)).background(g.color))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(g.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(g.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatMoney(g.amount), fontWeight = FontWeight.Bold, color = g.amountColor)
                    Text(if (open) "  ▾" else "  ▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                g.fraction?.let { f ->
                    LinearProgressIndicator(
                        progress = { f }, color = g.color, trackColor = g.color.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    )
                }
            }
            if (open) {
                Column(Modifier.fillMaxWidth().background(g.color.copy(alpha = 0.07f)).padding(vertical = 4.dp)) {
                    g.txns.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = g.color.copy(alpha = 0.2f))
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { context.startActivity(CategorizeActivity.intent(context, t.id)) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.merchant ?: t.effectiveCategory(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    formatDay(t.timestamp) + " · " + (if (g.showCategory) t.effectiveCategory() else t.sourceLabel) +
                                        (t.myShare?.let { " · my share of ${formatMoney(t.amount)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                (if (t.type == TxnType.DEBIT) "−" else "+") + formatMoney(t.countedAmount),
                                color = if (t.type == TxnType.DEBIT) DebitRed else CreditGreen,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            )
                            Text("  ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
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
    // null = all, "DEBIT", "CREDIT", "SPLIT" (group bills) or "IGNORED" (not counted).
    var filter by remember { mutableStateOf<String?>(null) }
    val shown = txns.filter {
        when (filter) {
            null -> !it.ignored
            "IGNORED" -> it.ignored
            "SPLIT" -> !it.ignored && it.myShare != null
            else -> !it.ignored && it.type.name == filter
        }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { MonthSwitcher(monthOffset, onShift) }
        item {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(null to "All", "DEBIT" to "Debits", "CREDIT" to "Credits", "SPLIT" to "Group bills",
                    "IGNORED" to "Not counted")) { (t, label) ->
                    FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(label) })
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
                            t.myShare?.let {
                                Text("My share ${formatMoney(it)} of ${formatMoney(t.amount)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            if (t.ignored) Text("Not counted", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            t.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        }
                        Text(
                            (if (t.type == TxnType.DEBIT) "−" else "+") + formatMoney(t.countedAmount),
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
    var addingKind by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(KIND_EXPENSE to "💸 Spending categories", KIND_INCOME to "💰 Income categories").forEach { (kind, title) ->
            val accent = if (kind == KIND_EXPENSE) DebitRed else CreditGreen
            val list = categories.filter { it.kind == kind }
            item(key = kind) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().background(accent.copy(alpha = 0.14f)).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { addingKind = kind }) { Text("＋ Add") }
                    }
                    list.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 6.dp, height = 28.dp).clip(RoundedCornerShape(3.dp))
                                .background(categoryColor(categories.indexOf(c))))
                            Text(c.emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 10.dp))
                            Text(c.name, modifier = Modifier.weight(1f))
                            // Move a category to the other side (e.g. a custom one filed under the wrong type).
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    db.setCategoryKind(c.name, if (kind == KIND_EXPENSE) KIND_INCOME else KIND_EXPENSE)
                                }
                            }) { Text(if (kind == KIND_EXPENSE) "→ Income" else "→ Spending", style = MaterialTheme.typography.labelSmall) }
                            if (c.name != CategorySuggester.OTHER && c.name != CategorySuggester.INCOME) {
                                IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${c.name}") }
                            }
                        }
                    }
                }
            }
        }
    }
    addingKind?.let { kind ->
        NewCategoryDialog(onDismiss = { addingKind = null }) { name, emoji ->
            addingKind = null
            scope.launch(Dispatchers.IO) { db.addCategory(name, emoji, kind) }
        }
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
private fun SetupTab(modifier: Modifier, resumeTick: Int, onNamesChanged: () -> Unit) {
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
        item { SimNamesCard(onNamesChanged) }
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

@Composable
private fun SimNamesCard(onNamesChanged: () -> Unit) {
    val context = LocalContext.current
    val names = remember { SimBooks.SIMS.associateWith { SimBooks.name(context, it) }.toMutableMap() }
    var edits by remember { mutableStateOf(names.toMap()) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SIM books", style = MaterialTheme.typography.titleMedium)
            Text("Each SIM keeps completely separate accounts. SMS go to the book of the SIM they arrive on; " +
                "you can move one to the other book from its ✎ Edit details.", style = MaterialTheme.typography.bodySmall)
            SimBooks.SIMS.forEach { s ->
                OutlinedTextField(
                    value = edits[s] ?: "", onValueChange = { v -> edits = edits + (s to v) },
                    label = { Text("Name for SIM $s") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = {
                edits.forEach { (s, n) -> SimBooks.setName(context, s, n) }
                onNamesChanged()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }) { Text("Save names") }
        }
    }
}
