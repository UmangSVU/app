package com.umang.fintrack.parser

import java.util.Calendar
import java.util.Locale

/** One row of a bank or credit card statement. [date] is local midnight of the transaction day. */
data class StatementTxn(
    val date: Long,
    val amount: Double,
    val type: TxnType,
    val description: String,
    val raw: String,
)

data class ParsedStatement(
    val bank: String?,
    /** Masked card / account number, e.g. "XX0705". */
    val account: String?,
    /** "Credit Card" or "Bank Account". */
    val instrument: String,
    val txns: List<StatementTxn>,
)

/**
 * Reads transactions out of the text of a bank / credit card statement (from a PDF or CSV).
 * Pure Kotlin so it can be unit tested. It is deliberately layout-agnostic: a transaction is a
 * line that starts with a date and contains a money amount (two decimals).
 */
object StatementParser {

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // Dates at the start of a row: 02/10/2026, 02-10-26, 02.10.2026, 2026-10-02, 02 Oct 2026, 02-Oct-26, 02 Oct, 02/10
    private val DATE = Regex(
        """^\s*(?:\d{1,4}\s+)?""" +
            """(?:(\d{4})-(\d{1,2})-(\d{1,2})|""" +
            """(\d{1,2})[/\-.](\d{1,2})(?:[/\-.](\d{2,4}))?|""" +
            """(\d{1,2})[\s\-/]*([A-Za-z]{3})[a-z]*[\s\-/,']*(\d{2,4})?)(?![\d])"""
    )
    private val AMOUNT = Regex(
        """(?<![\d.,])(-?(?:\d{1,3}(?:,\d{2,3})+|\d+)\.\d{2})(?![\d])(?:\s*(Cr|CR|cr|Dr|DR|dr|C|D)\b)?"""
    )
    private val SKIP = Regex(
        """(?i)\b(?:opening balance|closing balance|balance b/?f|b/f|c/f|brought forward|carried forward|total|""" +
            """minimum (?:amount )?due|payment due|statement (?:date|period)|credit limit|available (?:credit )?limit|""" +
            """previous balance|reward points?|finance charges? summary|page \d)\b"""
    )
    private val CREDIT_HINT = Regex(
        """(?i)\b(?:payment received|payment - thank you|thank you|refund|reversal|reversed|cashback|cash back|""" +
            """credit(?:ed)?|deposit|salary|neft cr|imps cr|upi cr|by transfer|interest paid|int\.? ?pd)\b|/cr/|\bcr\b"""
    )

    fun parse(text: String): ParsedStatement {
        val lines = text.lines().map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }
        val defaultYear = mostCommonYear(text) ?: Calendar.getInstance().get(Calendar.YEAR)
        val isCard = Regex("""(?i)credit card""").containsMatchIn(text)
        val hasBalance = Regex("""(?i)\bbalance\b""").containsMatchIn(text) && !isCard

        val txns = mutableListOf<StatementTxn>()
        var prevBalance: Double? = null
        var i = 0
        while (i < lines.size) {
            var row = lines[i]
            val dateMatch = DATE.find(row)
            if (dateMatch == null) { i++; continue }
            // Wrapped rows: the amounts may be on the next line(s).
            var j = i + 1
            while (AMOUNT.findAll(row.substring(dateMatch.range.last + 1)).none() && j < lines.size && j <= i + 2 &&
                DATE.find(lines[j]) == null
            ) {
                row += " " + lines[j]; j++
            }
            i = maxOf(j, i + 1)

            val date = toMillis(dateMatch, defaultYear) ?: continue
            var rest = row.substring(dateMatch.range.last + 1)
            // A second (value) date right after the first one.
            DATE.find(rest)?.let { if (it.range.first <= 2) rest = rest.substring(it.range.last + 1) }
            val amounts = AMOUNT.findAll(rest).toList()
            if (amounts.isEmpty()) continue
            val description = cleanDescription(rest.substring(0, amounts.first().range.first))
            if (SKIP.containsMatchIn(description) || SKIP.containsMatchIn(row.take(40))) {
                // Still learn the opening balance so the first real row can be classified.
                if (hasBalance) prevBalance = money(amounts.last().groupValues[1])
                continue
            }

            val marked = amounts.firstOrNull { it.groupValues[2].isNotEmpty() }
            val (amountMatch, balance) = if (hasBalance && amounts.size >= 2) {
                amounts[amounts.size - 2] to money(amounts.last().groupValues[1])
            } else {
                (marked ?: amounts.first()) to null
            }
            val amount = money(amountMatch.groupValues[1])?.let { kotlin.math.abs(it) } ?: continue
            if (amount == 0.0) continue

            val marker = amountMatch.groupValues[2].lowercase()
            val type = when {
                marker.startsWith("c") -> TxnType.CREDIT
                marker.startsWith("d") -> TxnType.DEBIT
                balance != null && prevBalance != null && kotlin.math.abs(prevBalance - amount - balance) < 0.011 -> TxnType.DEBIT
                balance != null && prevBalance != null && kotlin.math.abs(prevBalance + amount - balance) < 0.011 -> TxnType.CREDIT
                CREDIT_HINT.containsMatchIn(description) -> TxnType.CREDIT
                else -> TxnType.DEBIT
            }
            if (balance != null) prevBalance = balance
            txns += StatementTxn(date, amount, type, description.ifBlank { "Statement entry" }, row)
        }

        return ParsedStatement(
            bank = SmsParser.bankIn(text),
            account = accountNumber(text, isCard)?.let { "XX$it" },
            instrument = if (isCard) "Credit Card" else "Bank Account",
            txns = txns,
        )
    }

    private fun money(s: String): Double? = s.replace(",", "").toDoubleOrNull()

    private fun cleanDescription(s: String): String = s
        .replace(Regex("""(?<![\w@\-])\d{8,}(?![\w@])"""), " ")   // long reference numbers, not UPI IDs
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ',', ':')
        .take(60)
        .trim()

    private fun accountNumber(text: String, isCard: Boolean): String? {
        val card = Regex("""(?i)(?:card\s*(?:no|number|num)?\.?\s*:?\s*)?(?:[x*\d]{4}[\s\-]?){3}(\d{4})\b""")
        val account = Regex("""(?i)\b(?:a/?c|account)\s*(?:no|number|num)?\.?\s*:?\s*[x*\d]*?(\d{4})\b""")
        return if (isCard) card.find(text)?.groupValues?.get(1) ?: account.find(text)?.groupValues?.get(1)
        else account.find(text)?.groupValues?.get(1) ?: card.find(text)?.groupValues?.get(1)
    }

    private fun mostCommonYear(text: String): Int? =
        Regex("""(?<!\d)(20\d{2})(?!\d)""").findAll(text).map { it.value.toInt() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun toMillis(m: MatchResult, defaultYear: Int): Long? {
        val g = m.groupValues
        val (day, month, yearText) = when {
            g[1].isNotEmpty() -> Triple(g[3].toInt(), g[2].toInt(), g[1])
            g[4].isNotEmpty() -> Triple(g[4].toInt(), g[5].toInt(), g[6])
            else -> {
                val mon = MONTHS.indexOf(g[8].lowercase(Locale.US)).takeIf { it >= 0 } ?: return null
                Triple(g[7].toInt(), mon + 1, g[9])
            }
        }
        if (day !in 1..31 || month !in 1..12) return null
        val year = when (yearText.length) {
            0 -> defaultYear
            2 -> 2000 + yearText.toInt()
            4 -> yearText.toInt()
            else -> return null
        }
        if (year !in 2000..2100) return null
        return Calendar.getInstance().apply {
            clear(); set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis
    }
}

/** Candidate passwords for statement PDFs, built from the usual Indian bank patterns. */
object StatementPasswords {
    /**
     * @param name name as printed on the card / account
     * @param dob date of birth as day, month, year (any may be null)
     * @param cardDigits last 4 digits of your cards / accounts
     */
    fun candidates(
        name: String?,
        dob: Triple<Int, Int, Int>?,
        mobile: String?,
        cardDigits: List<String>,
        extras: List<String>,
    ): List<String> {
        val out = LinkedHashSet<String>()
        extras.map { it.trim() }.filter { it.isNotEmpty() }.forEach { out += it }

        val first = name?.trim()?.split(Regex("""\s+"""))?.firstOrNull()?.filter { it.isLetter() }.orEmpty()
        val full = name?.filter { it.isLetter() }.orEmpty()
        val nameParts = buildList {
            if (first.length >= 4) add(first.take(4))
            if (full.length >= 4 && full.take(4) != first.take(4)) add(full.take(4))
            if (first.isNotEmpty()) add(first)
        }.flatMap { listOf(it.uppercase(), it.lowercase(), it.lowercase().replaceFirstChar(Char::uppercase)) }.distinct()

        val dates = dob?.let { (d, m, y) ->
            val dd = "%02d".format(d); val mm = "%02d".format(m); val yyyy = "%04d".format(y); val yy = yyyy.takeLast(2)
            listOf(dd + mm, dd + mm + yyyy, dd + mm + yy, mm + dd, yyyy, yyyy + mm + dd, dd + mm + yy.takeLast(2))
        }.orEmpty().distinct()

        // Name + date (HDFC, ICICI, Axis, Kotak, IDFC, OneCard…: first 4 letters + DDMM is the most common)
        for (n in nameParts) for (d in dates) out += n + d
        // Date only (many bank account statements)
        dates.forEach { out += it }
        // Date + card digits and card digits + date (SBI Card and others)
        for (c in cardDigits) for (d in dates) { out += d + c; out += c + d }
        // Name + card digits
        for (n in nameParts) for (c in cardDigits) out += n + c
        // Mobile based (SBI account: last 5 digits of mobile + DDMMYY)
        mobile?.filter(Char::isDigit)?.takeIf { it.length >= 5 }?.let { mob ->
            for (d in dates) { out += mob.takeLast(5) + d; out += mob.takeLast(4) + d }
            out += mob.takeLast(10)
        }
        return out.toList()
    }
}
