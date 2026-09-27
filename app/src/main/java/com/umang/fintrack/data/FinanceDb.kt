package com.umang.fintrack.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.umang.fintrack.parser.CategorySuggester
import com.umang.fintrack.parser.SmsParser
import com.umang.fintrack.parser.TxnType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest

data class Transaction(
    val id: Long,
    val timestamp: Long,
    val amount: Double,
    val type: TxnType,
    val bank: String?,
    val account: String?,
    val instrument: String?,
    val mode: String?,
    val merchant: String?,
    val upiId: String?,
    val reference: String?,
    val balance: Double?,
    val availableLimit: Double?,
    val category: String?,
    val suggestedCategory: String,
    val note: String?,
    val pending: Boolean,
    val sender: String?,
    val body: String?,
    /** Which SIM's book this belongs to: 1 or 2. */
    val sim: Int,
    /** For a group bill: the part that is really yours. Null = the whole amount. */
    val myShare: Double?,
    /** Marked "not a transaction": kept (so re-imports skip it) but never counted. */
    val ignored: Boolean,
) {
    /** The amount that counts towards your totals. */
    val countedAmount: Double get() = myShare ?: amount
    /** Paid on behalf of others in a group bill. */
    val othersShare: Double get() = myShare?.let { (amount - it).coerceAtLeast(0.0) } ?: 0.0

    /** "HDFC Bank · Credit Card XX4321" */
    val sourceLabel: String
        get() = listOfNotNull(bank, listOfNotNull(instrument, account).joinToString(" ").ifBlank { null })
            .joinToString(" · ").ifBlank { "Manual entry" }
}

data class Category(val name: String, val emoji: String, val kind: String)

/** Categories that move money between your own accounts and are left out of spend/income totals. */
const val TRANSFER = "Transfer"

class FinanceDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "fintrack.db", null, 2) {

    companion object {
        @Volatile private var instance: FinanceDb? = null

        fun get(context: Context): FinanceDb =
            instance ?: synchronized(this) { instance ?: FinanceDb(context).also { instance = it } }

        private val DEFAULT_CATEGORIES = listOf(
            Category("Food & Dining", "🍔", "EXPENSE"),
            Category("Groceries", "🛒", "EXPENSE"),
            Category("Shopping", "🛍️", "EXPENSE"),
            Category("Transport", "🚕", "EXPENSE"),
            Category("Fuel", "⛽", "EXPENSE"),
            Category("Bills & Utilities", "💡", "EXPENSE"),
            Category("Rent", "🏠", "EXPENSE"),
            Category("EMI & Loans", "🏦", "EXPENSE"),
            Category("Entertainment", "🎬", "EXPENSE"),
            Category("Health", "💊", "EXPENSE"),
            Category("Education", "📚", "EXPENSE"),
            Category("Travel", "✈️", "EXPENSE"),
            Category("Cash", "💵", "EXPENSE"),
            Category("Investment", "📈", "BOTH"),
            Category(TRANSFER, "🔁", "BOTH"),
            Category("Salary", "💼", "INCOME"),
            Category("Refund", "↩️", "INCOME"),
            Category(CategorySuggester.INCOME, "💰", "INCOME"),
            Category(CategorySuggester.OTHER, "📦", "BOTH"),
        )
    }

    private val _changes = MutableStateFlow(0L)
    /** Bumped after every write so screens know to reload. */
    val changes: StateFlow<Long> = _changes
    private fun changed() { _changes.value = _changes.value + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sms_hash TEXT UNIQUE,
                sender TEXT, body TEXT, timestamp INTEGER NOT NULL,
                amount REAL NOT NULL, type TEXT NOT NULL,
                bank TEXT, account TEXT, instrument TEXT, mode TEXT,
                merchant TEXT, upi_id TEXT, reference TEXT,
                balance REAL, available_limit REAL,
                category TEXT, suggested_category TEXT NOT NULL, note TEXT,
                pending INTEGER NOT NULL DEFAULT 1)"""
        )
        addV2Columns(db)
        db.execSQL("CREATE INDEX idx_txn_time ON transactions(timestamp)")
        db.execSQL("CREATE TABLE categories (name TEXT PRIMARY KEY, emoji TEXT NOT NULL, kind TEXT NOT NULL, sort INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE merchant_rules (merchant_key TEXT PRIMARY KEY, category TEXT NOT NULL)")
        DEFAULT_CATEGORIES.forEachIndexed { i, c ->
            db.insert("categories", null, ContentValues().apply {
                put("name", c.name); put("emoji", c.emoji); put("kind", c.kind); put("sort", i)
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addV2Columns(db)
            // Learned rules become per-SIM; old ones apply to SIM 1.
            db.execSQL("UPDATE merchant_rules SET merchant_key = '1|' || merchant_key")
        }
    }

    private fun addV2Columns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN sim INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE transactions ADD COLUMN my_share REAL")
        db.execSQL("ALTER TABLE transactions ADD COLUMN ignored INTEGER NOT NULL DEFAULT 0")
    }

    private fun ruleKey(sim: Int, merchantKey: String) = "$sim|$merchantKey"

    // ---------- SMS ingestion ----------

    /**
     * Parses an SMS and stores it. Returns the new transaction id, or null when the SMS is not a
     * transaction or was already stored.
     */
    fun insertFromSms(sender: String?, body: String, timestamp: Long, sim: Int, pending: Boolean = true): Long? {
        val parsed = SmsParser.parse(sender, body) ?: return null
        // The inbox copy of an SMS can carry a slightly different time than the live broadcast.
        val duplicate = readableDatabase.rawQuery(
            "SELECT 1 FROM transactions WHERE body = ? AND ABS(timestamp - ?) < 600000 LIMIT 1",
            arrayOf(body, timestamp.toString())
        ).use { it.moveToFirst() }
        if (duplicate) return null
        val learned = CategorySuggester.merchantKey(parsed)?.let { learnedCategory(ruleKey(sim, it)) }
        val suggested = CategorySuggester.suggest(parsed, body, learned)
        val values = ContentValues().apply {
            put("sms_hash", hash("$sender|$body|$timestamp"))
            put("sender", sender); put("body", body); put("timestamp", timestamp); put("sim", sim)
            put("amount", parsed.amount); put("type", parsed.type.name)
            put("bank", parsed.bank); put("account", parsed.account); put("instrument", parsed.instrument)
            put("mode", parsed.mode); put("merchant", parsed.merchant); put("upi_id", parsed.upiId)
            put("reference", parsed.reference); put("balance", parsed.balance)
            put("available_limit", parsed.availableLimit)
            put("suggested_category", suggested)
            put("category", if (pending) null else suggested)
            put("pending", if (pending) 1 else 0)
        }
        val id = writableDatabase.insertWithOnConflict("transactions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) return null
        changed()
        return id
    }

    fun insertManual(
        amount: Double, type: TxnType, merchant: String?, category: String, note: String?, sim: Int, myShare: Double?,
    ): Long {
        val id = writableDatabase.insert("transactions", null, ContentValues().apply {
            put("timestamp", System.currentTimeMillis()); put("amount", amount); put("type", type.name)
            put("merchant", merchant); put("category", category); put("suggested_category", category)
            put("note", note); put("pending", 0); put("mode", "Cash"); put("sim", sim); put("my_share", myShare)
        })
        changed()
        return id
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------- Categorisation ----------

    fun categorize(
        id: Long,
        category: String,
        note: String? = null,
        amount: Double? = null,
        merchant: String? = null,
        type: TxnType? = null,
        remember: Boolean = true,
        sim: Int? = null,
        myShare: Double? = null,
    ) {
        val db = writableDatabase
        db.update("transactions", ContentValues().apply {
            put("category", category); put("pending", 0); put("ignored", 0)
            // Only a real partial share is stored; a full or empty share means "all mine".
            val a = amount ?: getTransaction(id)?.amount
            put("my_share", myShare?.takeIf { a != null && it >= 0 && it < a })
            if (sim != null) put("sim", sim)
            if (note != null) put("note", note.ifBlank { null })
            if (amount != null) put("amount", amount)
            if (merchant != null) put("merchant", merchant.ifBlank { null })
            if (type != null) put("type", type.name)
        }, "id = ?", arrayOf(id.toString()))
        if (remember) {
            val txn = getTransaction(id)
            CategorySuggester.merchantKey(txn?.merchant ?: txn?.upiId)?.let { key ->
                db.insertWithOnConflict("merchant_rules", null, ContentValues().apply {
                    put("merchant_key", ruleKey(txn?.sim ?: 1, key)); put("category", category)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        changed()
    }

    /** "Not a transaction": stop asking and leave it out of every total. */
    fun ignore(id: Long) {
        writableDatabase.update("transactions", ContentValues().apply {
            put("ignored", 1); put("pending", 0)
        }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun delete(id: Long) {
        writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
        changed()
    }

    private fun learnedCategory(key: String): String? =
        readableDatabase.rawQuery("SELECT category FROM merchant_rules WHERE merchant_key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ---------- Queries ----------

    fun getTransaction(id: Long): Transaction? =
        readableDatabase.rawQuery("SELECT * FROM transactions WHERE id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.toTransaction() else null
        }

    fun pendingIds(): List<Long> =
        readableDatabase.rawQuery("SELECT id FROM transactions WHERE pending = 1 ORDER BY timestamp", null).use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }

    /** Everything in one SIM's book for a period, including ignored entries (callers filter). */
    fun transactionsBetween(sim: Int, from: Long, to: Long): List<Transaction> =
        readableDatabase.rawQuery(
            "SELECT * FROM transactions WHERE sim = ? AND timestamp >= ? AND timestamp < ? ORDER BY timestamp DESC",
            arrayOf(sim.toString(), from.toString(), to.toString())
        ).use { buildList { while (it.moveToNext()) add(it.toTransaction()) } }

    fun categories(): List<Category> =
        readableDatabase.rawQuery("SELECT name, emoji, kind FROM categories ORDER BY sort", null).use {
            buildList { while (it.moveToNext()) add(Category(it.getString(0), it.getString(1), it.getString(2))) }
        }

    fun addCategory(name: String, emoji: String, kind: String = "BOTH") {
        val sort = readableDatabase.rawQuery("SELECT COALESCE(MAX(sort), 0) + 1 FROM categories", null)
            .use { it.moveToFirst(); it.getInt(0) }
        writableDatabase.insertWithOnConflict("categories", null, ContentValues().apply {
            put("name", name.trim()); put("emoji", emoji.ifBlank { "🏷️" }); put("kind", kind); put("sort", sort)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        changed()
    }

    fun deleteCategory(name: String) {
        writableDatabase.delete("categories", "name = ?", arrayOf(name))
        writableDatabase.delete("merchant_rules", "category = ?", arrayOf(name))
        changed()
    }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.dbl(col: String): Double? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }

    private fun Cursor.toTransaction() = Transaction(
        id = getLong(getColumnIndexOrThrow("id")),
        timestamp = getLong(getColumnIndexOrThrow("timestamp")),
        amount = getDouble(getColumnIndexOrThrow("amount")),
        type = TxnType.valueOf(getString(getColumnIndexOrThrow("type"))),
        bank = str("bank"), account = str("account"), instrument = str("instrument"), mode = str("mode"),
        merchant = str("merchant"), upiId = str("upi_id"), reference = str("reference"),
        balance = dbl("balance"), availableLimit = dbl("available_limit"),
        category = str("category"), suggestedCategory = str("suggested_category") ?: CategorySuggester.OTHER,
        note = str("note"), pending = getInt(getColumnIndexOrThrow("pending")) == 1,
        sender = str("sender"), body = str("body"),
        sim = getInt(getColumnIndexOrThrow("sim")), myShare = dbl("my_share"),
        ignored = getInt(getColumnIndexOrThrow("ignored")) == 1,
    )
}
