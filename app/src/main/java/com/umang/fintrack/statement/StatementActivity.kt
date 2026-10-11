package com.umang.fintrack.statement

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.umang.fintrack.data.FinanceDb
import com.umang.fintrack.data.SimBooks
import com.umang.fintrack.data.Transaction
import com.umang.fintrack.notify.Notifier
import com.umang.fintrack.parser.ParsedStatement
import com.umang.fintrack.parser.StatementParser
import com.umang.fintrack.parser.TxnType
import com.umang.fintrack.ui.CategorizeActivity
import com.umang.fintrack.ui.CreditGreen
import com.umang.fintrack.ui.DebitRed
import com.umang.fintrack.ui.FinTheme
import com.umang.fintrack.ui.formatDay
import com.umang.fintrack.ui.formatMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports a bank / credit card statement (PDF or CSV). Opened from Setup → "Upload statement",
 * or by sharing a statement attachment from Gmail / Files to FinTrack.
 */
class StatementActivity : ComponentActivity() {
    companion object {
        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, StatementActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> intent.data
        }
        setContent { FinTheme { Surface(Modifier.fillMaxSize()) { StatementScreen(uri) { finish() } } } }
    }
}

private sealed class UiState {
    data class Working(val message: String) : UiState()
    data class NeedsPassword(val wrong: Boolean) : UiState()
    data class Error(val message: String) : UiState()
    /** Read OK; asking which SIM book the missing entries should go into. */
    data class ChooseBook(val statement: ParsedStatement, val suggested: Int, val knownCard: Boolean) : UiState()
    data class Done(
        val bank: String?, val account: String?, val total: Int, val already: Int, val added: List<Transaction>,
        val book: Int,
    ) : UiState()
}

@Composable
private fun StatementScreen(uri: Uri?, onClose: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<UiState>(UiState.Working("Reading statement…")) }
    var attempt by remember { mutableIntStateOf(0) }
    var typedPassword by remember { mutableStateOf("") }
    val fileName = remember(uri) { uri?.let { StatementImporter.fileName(context, it) } ?: "" }

    LaunchedEffect(attempt) {
        if (uri == null) { state = UiState.Error("No file was received."); return@LaunchedEffect }
        state = UiState.Working("Reading statement…")
        val read = withContext(Dispatchers.IO) {
            StatementImporter.read(context, uri, typedPassword.ifBlank { null }) { msg -> state = UiState.Working(msg) }
        }
        state = when (read) {
            is ReadResult.NeedsPassword -> UiState.NeedsPassword(wrong = typedPassword.isNotBlank())
            is ReadResult.Failed -> UiState.Error(read.message)
            is ReadResult.Text -> withContext(Dispatchers.IO) {
                val st = StatementParser.parse(read.text)
                if (st.txns.isEmpty()) {
                    UiState.Error("No transactions found in this file. If it's a scanned (image) statement, it can't be read.")
                } else {
                    val known = FinanceDb.get(context).bookForAccount(st.account)
                    UiState.ChooseBook(st, known ?: SimBooks.selected(context), knownCard = known != null)
                }
            }
        }
    }

    // Second step: import into the book you picked.
    var importInto by remember { mutableStateOf<Pair<ParsedStatement, Int>?>(null) }
    LaunchedEffect(importInto) {
        val (st, book) = importInto ?: return@LaunchedEffect
        state = UiState.Working("Comparing with your recorded transactions…")
        state = withContext(Dispatchers.IO) {
            val db = FinanceDb.get(context)
            val result = db.importStatement(st, book)
            // A handful get the usual sticky notification; for more, the Home banner and the button below do the job.
            if (result.added.size <= 5) result.added.forEach { Notifier.show(context, it) }
            UiState.Done(st.bank, st.account, result.total, result.alreadyRecorded, result.added.mapNotNull(db::getTransaction), book)
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import statement", style = MaterialTheme.typography.headlineSmall)
        if (fileName.isNotEmpty()) Text(fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)

        when (val s = state) {
            is UiState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(s.message)
            }

            is UiState.NeedsPassword -> {
                Text(
                    if (s.wrong) "That password didn't work." else
                        "This PDF is password protected and none of the usual patterns opened it.",
                    color = if (s.wrong) DebitRed else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Type the password from the bank's email (often your name's first 4 letters + birth date), or add " +
                        "your name and date of birth in Setup → Statements so the app can work it out.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = typedPassword, onValueChange = { typedPassword = it }, label = { Text("PDF password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = typedPassword.isNotBlank(), onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) { Text("Open") }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }

            is UiState.ChooseBook -> {
                var book by remember(s) { mutableIntStateOf(s.suggested) }
                val st = s.statement
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(listOfNotNull(st.bank, st.instrument, st.account).joinToString(" · "), fontWeight = FontWeight.Bold)
                        Text("${st.txns.size} transactions found")
                    }
                }
                Text("Which book should missing entries go into?", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (s.knownCard) "Suggested: the book this card / account's transactions usually go to."
                    else "This card / account hasn't been seen before – please pick the book.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SimBooks.SIMS.forEach { sim ->
                        FilterChip(
                            selected = book == sim, onClick = { book = sim },
                            label = { Text("📱 " + SimBooks.name(context, sim)) },
                        )
                    }
                }
                Text(
                    "Entries already recorded in either book are skipped either way.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { importInto = st to book }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import into ${SimBooks.name(context, book)}")
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }

            is UiState.Error -> {
                Text(s.message, color = DebitRed)
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }

            is UiState.Done -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(listOfNotNull(s.bank, s.account).joinToString(" · ").ifBlank { "Statement" }, fontWeight = FontWeight.Bold)
                        Text("${s.total} transactions in the statement · book: ${SimBooks.name(context, s.book)}")
                        Text("✓ ${s.already} already recorded – skipped", color = CreditGreen)
                        Text(
                            if (s.added.isEmpty()) "Nothing missing – you're up to date 🎉"
                            else "＋ ${s.added.size} missing – added for you to categorise",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (s.added.isNotEmpty()) {
                    Button(onClick = {
                        context.startActivity(CategorizeActivity.intent(context, s.added.first().id))
                        onClose()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Categorise ${s.added.size} new transaction(s)") }
                    LazyColumn(Modifier.weight(1f)) {
                        items(s.added, key = { it.id }) { t ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.merchant ?: "Statement entry", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatDay(t.timestamp), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    (if (t.type == TxnType.DEBIT) "−" else "+") + formatMoney(t.amount),
                                    color = if (t.type == TxnType.DEBIT) DebitRed else CreditGreen, fontWeight = FontWeight.SemiBold,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}
