package com.umang.fintrack.statement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.umang.fintrack.data.FinanceDb
import com.umang.fintrack.parser.StatementPasswords

/** What you've told the app so it can open password-protected statements. Stored only on the phone. */
object StatementPrefs {
    private const val PREFS = "statement"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(c: Context) = p(c).getString("name", "") ?: ""
    fun dob(c: Context) = p(c).getString("dob", "") ?: ""
    fun mobile(c: Context) = p(c).getString("mobile", "") ?: ""
    fun extras(c: Context) = p(c).getString("extras", "") ?: ""

    fun save(c: Context, name: String, dob: String, mobile: String, extras: String) =
        p(c).edit().putString("name", name.trim()).putString("dob", dob.trim())
            .putString("mobile", mobile.trim()).putString("extras", extras.trim()).apply()

    /** Passwords that opened a statement before; tried first next time. */
    fun worked(c: Context): List<String> = p(c).getString("worked", "")!!.split('\n').filter { it.isNotEmpty() }
    fun remember(c: Context, password: String) {
        val list = (listOf(password) + worked(c)).distinct().take(10)
        p(c).edit().putString("worked", list.joinToString("\n")).apply()
    }

    /** "07/03/1995", "7-3-1995" or "07031995" → (7, 3, 1995). */
    fun parseDob(s: String): Triple<Int, Int, Int>? {
        val digits = s.split(Regex("""[^0-9]+""")).filter { it.isNotEmpty() }
        val parts = if (digits.size == 1 && digits[0].length == 8) {
            listOf(digits[0].take(2), digits[0].substring(2, 4), digits[0].takeLast(4))
        } else digits
        if (parts.size != 3) return null
        val (d, m, y) = parts.map { it.toIntOrNull() ?: return null }
        val year = if (y < 100) 1900 + y else y
        return if (d in 1..31 && m in 1..12) Triple(d, m, year) else null
    }
}

sealed class ReadResult {
    data class Text(val text: String) : ReadResult()
    /** None of the known passwords opened it. */
    data object NeedsPassword : ReadResult()
    data class Failed(val message: String) : ReadResult()
}

object StatementImporter {

    fun fileName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "statement"

    /**
     * Reads the statement's text. For a protected PDF it tries [typedPassword] first, then
     * passwords that worked before, then the usual bank patterns built from your name / DOB.
     */
    fun read(context: Context, uri: Uri, typedPassword: String? = null, onProgress: (String) -> Unit = {}): ReadResult {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ReadResult.Failed("Couldn't open the file")
        val isPdf = bytes.size > 4 && String(bytes, 0, 5, Charsets.ISO_8859_1).startsWith("%PDF")
        if (!isPdf) return ReadResult.Text(csvToText(String(bytes, Charsets.UTF_8)))

        PDFBoxResourceLoader.init(context.applicationContext)
        val candidates = buildList {
            typedPassword?.takeIf { it.isNotEmpty() }?.let(::add)
            add("")
            addAll(StatementPrefs.worked(context))
            addAll(
                StatementPasswords.candidates(
                    name = StatementPrefs.name(context),
                    dob = StatementPrefs.parseDob(StatementPrefs.dob(context)),
                    mobile = StatementPrefs.mobile(context),
                    cardDigits = FinanceDb.get(context).knownAccounts().mapNotNull { a -> a.account?.filter(Char::isDigit)?.takeLast(4) }
                        .filter { it.length == 4 }.distinct(),
                    extras = StatementPrefs.extras(context).split(',', '\n'),
                )
            )
        }.distinct()

        for ((i, pw) in candidates.withIndex()) {
            if (i > 0 && i % 10 == 0) onProgress("Trying passwords… ${i}/${candidates.size}")
            try {
                PDDocument.load(bytes, pw).use { doc ->
                    if (pw.isNotEmpty()) StatementPrefs.remember(context, pw)
                    val text = PDFTextStripper().apply { setSortByPosition(true) }.getText(doc)
                    return ReadResult.Text(text)
                }
            } catch (_: InvalidPasswordException) {
                continue
            } catch (e: Exception) {
                return ReadResult.Failed("Couldn't read this PDF (${e.javaClass.simpleName})")
            }
        }
        return ReadResult.NeedsPassword
    }

    /** CSV / TXT exports: one row per line, cells separated by double spaces (quotes respected). */
    private fun csvToText(raw: String): String = raw.lines().joinToString("\n") { line ->
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        for (ch in line) {
            when {
                ch == '"' -> quoted = !quoted
                (ch == ',' || ch == '\t' || ch == ';') && !quoted -> { cells += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        cells += cur.toString()
        cells.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("  ")
    }
}
