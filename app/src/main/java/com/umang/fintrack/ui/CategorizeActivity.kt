package com.umang.fintrack.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.umang.fintrack.data.Category
import com.umang.fintrack.data.FinanceDb
import com.umang.fintrack.data.Transaction
import com.umang.fintrack.notify.Notifier
import com.umang.fintrack.parser.CategorySuggester
import com.umang.fintrack.parser.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The category pop-up. It cannot be dismissed with Back: the only ways out are Submit
 * (which saves a category) or "Not a transaction" (which deletes a wrongly detected SMS).
 */
class CategorizeActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "txn_id"
        const val NEW_MANUAL = -1L

        fun intent(context: Context, id: Long): Intent =
            Intent(context, CategorizeActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private var currentId by mutableLongStateOf(NEW_MANUAL)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        currentId = intent.getLongExtra(EXTRA_ID, NEW_MANUAL)
        setContent {
            FinTheme {
                CategorizeScreen(
                    id = currentId,
                    onDone = { next -> if (next == null) finish() else currentId = next },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep working on the current one; a newer SMS is picked up automatically after Submit.
        if (currentId == NEW_MANUAL) currentId = intent.getLongExtra(EXTRA_ID, NEW_MANUAL)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorizeScreen(id: Long, onDone: (next: Long?) -> Unit, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { FinanceDb.get(context) }
    val scope = rememberCoroutineScope()
    val manual = id == CategorizeActivity.NEW_MANUAL

    var txn by remember(id) { mutableStateOf<Transaction?>(null) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var pendingCount by remember { mutableStateOf(0) }
    var loaded by remember(id) { mutableStateOf(false) }

    var selected by remember(id) { mutableStateOf<String?>(null) }
    var amountText by remember(id) { mutableStateOf("") }
    var merchant by remember(id) { mutableStateOf("") }
    var type by remember(id) { mutableStateOf(TxnType.DEBIT) }
    var note by remember(id) { mutableStateOf("") }
    var rememberChoice by remember(id) { mutableStateOf(true) }
    var editing by remember(id) { mutableStateOf(manual) }
    var showSms by remember(id) { mutableStateOf(false) }
    var addingCategory by remember { mutableStateOf(false) }
    var confirmIgnore by remember { mutableStateOf(false) }
    var categoriesVersion by remember { mutableStateOf(0) }

    LaunchedEffect(id) {
        val t = withContext(Dispatchers.IO) { if (manual) null else db.getTransaction(id) }
        if (!manual && t == null) { onDone(withContext(Dispatchers.IO) { db.pendingIds().firstOrNull() }); return@LaunchedEffect }
        txn = t
        selected = t?.category ?: t?.suggestedCategory ?: CategorySuggester.OTHER
        amountText = t?.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: ""
        merchant = t?.merchant ?: ""
        type = t?.type ?: TxnType.DEBIT
        note = t?.note ?: ""
        loaded = true
    }
    LaunchedEffect(id, categoriesVersion) {
        categories = withContext(Dispatchers.IO) { db.categories() }
        pendingCount = withContext(Dispatchers.IO) { db.pendingIds().size }
    }

    // Pending entries can't be closed without a category; already-categorised ones opened for editing can.
    val locked = txn?.pending == true
    BackHandler(enabled = locked) {
        Toast.makeText(context, "Please select a category and tap Submit", Toast.LENGTH_SHORT).show()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.fillMaxWidth().heightIn(max = 720.dp)) {
            if (!loaded) return@Card
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (manual) "Add transaction" else "Categorize transaction",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (!manual && pendingCount > 1) {
                        Text("$pendingCount pending", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }

                val color = if (type == TxnType.DEBIT) DebitRed else CreditGreen
                Text(
                    (if (type == TxnType.DEBIT) "− " else "+ ") + formatMoney(amountText.toDoubleOrNull() ?: 0.0),
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color,
                )
                txn?.let { t ->
                    Text(
                        (if (t.type == TxnType.DEBIT) "Debited" else "Credited") + " · " + formatDateTime(t.timestamp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Details(t)
                }

                if (!manual) {
                    TextButton(onClick = { editing = !editing }) { Text(if (editing) "Hide edit" else "✎ Edit details") }
                }
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == TxnType.DEBIT, onClick = { type = TxnType.DEBIT }, label = { Text("Debit / Spent") })
                        FilterChip(selected = type == TxnType.CREDIT, onClick = { type = TxnType.CREDIT }, label = { Text("Credit / Received") })
                    }
                    OutlinedTextField(
                        value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Amount (₹)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = merchant, onValueChange = { merchant = it },
                        label = { Text(if (type == TxnType.DEBIT) "Paid to" else "Received from") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider()
                Text("Category", style = MaterialTheme.typography.titleSmall)
                val wanted = if (type == TxnType.DEBIT) "EXPENSE" else "INCOME"
                val ordered = categories.sortedBy { if (it.kind == wanted || it.kind == "BOTH") 0 else 1 }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ordered.forEach { c ->
                        FilterChip(
                            selected = selected == c.name,
                            onClick = { selected = c.name },
                            label = { Text("${c.emoji} ${c.name}") },
                        )
                    }
                    AssistChip(onClick = { addingCategory = true }, label = { Text("＋ New") })
                }
                txn?.suggestedCategory?.let { Text("Suggested: $it", style = MaterialTheme.typography.labelSmall) }

                OutlinedTextField(
                    value = note, onValueChange = { note = it }, label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (merchant.isNotBlank() || txn?.upiId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                        Text(
                            "Always suggest this category for ${merchant.ifBlank { txn?.upiId }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                txn?.body?.let { body ->
                    TextButton(onClick = { showSms = !showSms }) { Text(if (showSms) "Hide SMS" else "Show original SMS") }
                    if (showSms) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Text("${txn?.sender ?: ""}\n$body", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                        }
                    }
                }

                Button(
                    enabled = selected != null && (amountText.toDoubleOrNull() ?: 0.0) > 0.0,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val category = selected ?: return@Button
                        val amount = amountText.toDoubleOrNull() ?: return@Button
                        scope.launch {
                            val next = withContext(Dispatchers.IO) {
                                if (manual) {
                                    db.insertManual(amount, type, merchant.ifBlank { null }, category, note.ifBlank { null })
                                    null
                                } else {
                                    db.categorize(id, category, note, amount, merchant, type, rememberChoice)
                                    Notifier.cancel(context, id)
                                    if (locked) db.pendingIds().firstOrNull() else null
                                }
                            }
                            onDone(next)
                        }
                    },
                ) { Text("Submit") }

                Row {
                    if (!locked) TextButton(onClick = onClose) { Text("Cancel") }
                    if (!manual) {
                        TextButton(onClick = { confirmIgnore = true }) {
                            Text(if (locked) "Not a transaction" else "Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (addingCategory) {
        NewCategoryDialog(
            onDismiss = { addingCategory = false },
            onCreate = { name, emoji ->
                addingCategory = false
                scope.launch {
                    withContext(Dispatchers.IO) { db.addCategory(name, emoji, if (type == TxnType.DEBIT) "EXPENSE" else "INCOME") }
                    selected = name.trim()
                    categoriesVersion++
                }
            },
        )
    }
    if (confirmIgnore) {
        AlertDialog(
            onDismissRequest = { confirmIgnore = false },
            title = { Text(if (locked) "Not a transaction?" else "Delete transaction?") },
            text = { Text("This entry will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmIgnore = false
                    scope.launch {
                        val next = withContext(Dispatchers.IO) {
                            db.delete(id); Notifier.cancel(context, id)
                            if (locked) db.pendingIds().firstOrNull() else null
                        }
                        onDone(next)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmIgnore = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun Details(t: Transaction) {
    val rows = listOfNotNull(
        t.merchant?.let { (if (t.type == TxnType.DEBIT) "To" else "From") to it },
        t.bank?.let { "Bank" to it },
        (listOfNotNull(t.instrument, t.account).joinToString(" ").ifBlank { null })?.let { "Account / Card" to it },
        t.mode?.let { "Mode" to it },
        t.upiId?.takeIf { it != t.merchant }?.let { "UPI ID" to it },
        t.reference?.let { "Reference" to it },
        t.balance?.let { "Balance after" to formatMoney(it) },
        t.availableLimit?.let { "Available limit" to formatMoney(it) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { (label, value) ->
            Row {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun NewCategoryDialog(onDismiss: () -> Unit, onCreate: (name: String, emoji: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🏷️") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(4) }, label = { Text("Emoji") }, singleLine = true)
                Spacer(Modifier)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name, emoji) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
