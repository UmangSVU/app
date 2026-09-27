package com.umang.fintrack.parser

/**
 * Pure-Kotlin parser for bank / card / UPI / wallet transaction SMS.
 * No Android dependencies so it can be unit tested on the JVM.
 */

enum class TxnType { DEBIT, CREDIT }

data class ParsedSms(
    val amount: Double,
    val type: TxnType,
    /** Bank or card issuer, e.g. "HDFC Bank". */
    val bank: String?,
    /** Masked account / card number, e.g. "XX1234". */
    val account: String?,
    /** "Credit Card", "Debit Card", "Card", "Bank Account", "Wallet" or "UPI". */
    val instrument: String?,
    /** UPI, NEFT, IMPS, RTGS, ATM, Card, NetBanking, Auto-debit, Cheque. */
    val mode: String?,
    /** Payee (for debits) or payer (for credits). */
    val merchant: String?,
    val upiId: String?,
    val reference: String?,
    val balance: Double?,
    val availableLimit: Double?,
)

object SmsParser {

    private const val NUM = """([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private const val CUR = """(?:rs\.?|inr|₹)"""

    private val AMOUNT = Regex("""(?i)$CUR\s*[:.]?\s*$NUM""")

    // SBI style: "A/C X1234 debited by 20.0 on date ..."
    private val AMOUNT_NO_CURRENCY = Regex(
        """(?i)\b(?:debited|credited|spent|paid|withdrawn|deducted|received)\s+(?:by|with|for|of)?\s*$NUM\b"""
    )

    private val IGNORE = Regex(
        """(?i)\b(?:otp|one[- ]time password|verification code|will be (?:debited|credited|charged)|""" +
            """is due|due (?:on|by|date)|minimum (?:amount )?due|total (?:amount )?due|has requested|""" +
            """requested (?:money|rs|inr)|collect request|payment request|pre-?approved|apply now|""" +
            """loan of (?:up ?to|upto)|failed|declined|unsuccessful|could not be|""" +
            """insufficient (?:funds|balance)|to be debited)\b"""
    )

    // Removed before deciding debit/credit so "Credit Card spent Rs 500" is not read as a credit.
    private val TYPE_NOISE = Regex("""(?i)\b(?:credit|debit)\s*card\b|\bcredit\s*limit\b|\bdebit\s*limit\b""")

    private val DEBIT_WORDS = Regex(
        """(?i)\b(?:debited|debit(?:ed)? (?:by|for|of|with)|spent|paid|withdrawn|withdrawal|sent|purchase[d]?|""" +
            """payment of|txn of|transaction of|transferred|deducted|charged|used (?:at|for)|dr)\b"""
    )
    private val CREDIT_WORDS = Regex(
        """(?i)\b(?:credited|received|deposited|refund(?:ed)?|added|reversed|reversal|cashback|cr)\b"""
    )

    private val BALANCE = Regex(
        """(?i)\b(?:avl\.?|avbl\.?|available|avail\.?|a/c|clr|closing|net|total)?\s*bal(?:ance)?\.?\s*""" +
            """(?:is|:|-|of)?\s*(?:$CUR)?\s*[:.]?\s*(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    )
    private val LIMIT = Regex(
        """(?i)\b(?:avl\.?|avbl\.?|available|avail\.?)\s*(?:credit\s*)?(?:limit|lmt)\.?\s*""" +
            """(?:is|:|-)?\s*(?:$CUR)?\s*[:.]?\s*$NUM"""
    )

    private val CARD_NUMBER = Regex(
        """(?i)\bcard\s*(?:no\.?|number|num)?\s*(?:ending|ending with|ending in|end)?\s*[:.]?\s*[x*#.\-]*\s*(\d{4})\b"""
    )
    private val ENDING = Regex("""(?i)\bending\s*(?:with|in)?\s*[:.]?\s*[x*]*(\d{4})\b""")
    private val ACCOUNT_NUMBER = Regex(
        """(?i)\b(?:a/c|a/c\.|ac|acct|account|a\.c\.)\s*(?:no\.?|number|num)?\s*[:.]?\s*[x*#.\-]*\s*(\d{3,6})\b"""
    )
    private val MASKED = Regex("""(?i)(?<![a-z0-9])[x*]{1,}(\d{3,6})\b""")

    private val UPI_ID = Regex("""(?i)(?<![\w.])([a-z0-9][a-z0-9._]{1,}@[a-z]{2,})(?!\w|\.\w)""")

    private val REFERENCE = Regex(
        """(?i)\b(?:upi(?=\s*[:/])|upi\s*ref(?:erence)?\.?\s*(?:no\.?|number|id)?|ref(?:erence)?\.?\s*(?:no\.?|number|#|id)?|refno|""" +
            """rrn|utr(?:\s*no\.?)?|txn\s*(?:id|no\.?|#)|transaction\s*(?:id|no\.?|ref(?:erence)?))\s*[:.#/\-]?\s*""" +
            """([A-Za-z0-9]{6,})"""
    )

    private const val STOP = """(?:on|via|using|ref|refno|upi|txn|from|dated|date|for|avl|avbl|bal|if|not|""" +
        """thru|through|by|with|at|in|is|has|was|and|call|sms|info|to|-)"""

    private fun partyRegex(prefix: String) = Regex(
        """(?i)\b(?:$prefix)\s+(?:vpa\s+|a/c\s+of\s+|m/s\.?\s*|merchant\s+)?""" +
            """([a-z0-9@][a-z0-9&'._@*/\- ]{1,50}?)(?=\s+$STOP\b|\s*[.,;:()\n]\s|\s*[,;()\n]|\.\s*$|\.?$)"""
    )

    private val DEBIT_PARTY = listOf(
        partyRegex("""paid to|sent to|trf to|transfer(?:red)? to|payment to|towards"""),
        partyRegex("""at"""),
        partyRegex("""to"""),
        partyRegex("""for"""),
        partyRegex("""on"""),
        partyRegex("""by"""),
    )
    private val CREDIT_PARTY = listOf(
        partyRegex("""received from|credited by|trf from|transfer(?:red)? from|sent by"""),
        // ICICI: "...credited with Rs 500 on 27-Sep-26; RAHUL SHARMA credited."
        Regex("""(?i);\s*([a-z][a-z .&'\-]{1,40}?)\s+credited\b"""),
        partyRegex("""from"""),
        partyRegex("""by"""),
    )

    // "Info: UPI/P2M/123456789/SWIGGY" style descriptors.
    private val INFO_DESCRIPTOR = Regex("""(?i)\b(?:info|desc|remarks?)\s*[:\-]\s*([^\n]+?)(?:\.\s|$)""")
    private val SLASH_DESCRIPTOR = Regex("""(?i)\b(?:upi|neft|imps|pos|ach|nach)/[\w/ .&@\-]+""")

    private val BAD_PARTY = Regex(
        """(?i)^(?:your|you|the|a|an|a/c|ac|acct|account|card|upi|neft|imps|rtgs|beneficiary|self|bank|""" +
            """date|dt|rs|inr|avl|avbl|bal|balance|mobile|net ?banking|atm|pos|txn|transaction|ur|any|us|""" +
            """unauthorized|block|dispute|help|customer|call|sms|cash|info)\b|a/c|\bxx|\*\*|\d{6,}"""
    )

    private val BANKS: List<Pair<Regex, String>> = listOf(
        "HDFC" to "HDFC Bank",
        "ICICI" to "ICICI Bank",
        "SBICRD|SBI ?CARD" to "SBI Card",
        "SBI|STATE BANK" to "State Bank of India",
        "AXIS" to "Axis Bank",
        "KOTAK|KOTAKB" to "Kotak Mahindra Bank",
        "YESB|YES ?BANK|YESBNK" to "Yes Bank",
        "IDFC" to "IDFC FIRST Bank",
        "INDUS" to "IndusInd Bank",
        "PNB|PUNJAB NATIONAL" to "Punjab National Bank",
        "BOB|BARODA" to "Bank of Baroda",
        "CANBNK|CANARA" to "Canara Bank",
        "UNION ?B|UBOI|UNIONB" to "Union Bank of India",
        "BOI|BANK OF INDIA" to "Bank of India",
        "IDBI" to "IDBI Bank",
        "AUBANK|AU SMALL|AU BANK" to "AU Small Finance Bank",
        "FEDBNK|FEDERAL" to "Federal Bank",
        "RBL" to "RBL Bank",
        "SCBANK|STANDARD CHARTERED|STANCHART" to "Standard Chartered",
        "CITI" to "Citibank",
        "HSBC" to "HSBC",
        "AMEX|AMERICAN EXPRESS" to "American Express",
        "DBS" to "DBS Bank",
        "IOB|INDIAN OVERSEAS" to "Indian Overseas Bank",
        "INDBNK|INDIAN BANK" to "Indian Bank",
        "UCO" to "UCO Bank",
        "CENTBK|CENTRAL BANK" to "Central Bank of India",
        "PAYTM|PYTM" to "Paytm",
        "AIRTEL ?PAYMENTS|AIRBNK" to "Airtel Payments Bank",
        "JUPITER" to "Jupiter",
        "SLICE" to "slice",
        "ONECARD|ONECRD" to "OneCard",
        "EQUITAS" to "Equitas Bank",
        "UJJIVAN" to "Ujjivan Bank",
        "BANDHAN" to "Bandhan Bank",
        "SARASWAT" to "Saraswat Bank",
        "COSMOS" to "Cosmos Bank",
    ).map { (pattern, name) -> Regex("""(?i)(?:^|[^A-Z])(?:$pattern)""") to name }

    private val WALLETS = Regex("""(?i)\b(?:wallet|amazon pay balance|paytm balance|mobikwik|freecharge|phonepe wallet|paytm wallet)\b""")

    fun parse(sender: String?, body: String): ParsedSms? {
        val text = body.replace(' ', ' ').replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty() || IGNORE.containsMatchIn(text)) return null

        val type = detectType(text) ?: return null
        val amount = detectAmount(text) ?: return null
        if (amount <= 0.0) return null

        val lower = text.lowercase()
        val cardNumber = CARD_NUMBER.find(text)?.groupValues?.get(1)
        val accountNumber = ACCOUNT_NUMBER.find(text)?.groupValues?.get(1)
        val genericMasked = ENDING.find(text)?.groupValues?.get(1) ?: MASKED.find(text)?.groupValues?.get(1)
        val upiId = UPI_ID.findAll(text).map { it.groupValues[1] }
            .firstOrNull { !it.contains("..") && !it.endsWith(".com") }

        val instrument = when {
            "credit card" in lower || "creditcard" in lower -> "Credit Card"
            "debit card" in lower || "debitcard" in lower -> "Debit Card"
            WALLETS.containsMatchIn(text) -> "Wallet"
            cardNumber != null || Regex("""(?i)\bcard\b""").containsMatchIn(text) -> "Card"
            accountNumber != null || genericMasked != null -> "Bank Account"
            upiId != null || "upi" in lower -> "UPI"
            else -> null
        }
        val digits = if (instrument?.contains("Card") == true) cardNumber ?: genericMasked ?: accountNumber
        else accountNumber ?: genericMasked ?: cardNumber

        return ParsedSms(
            amount = amount,
            type = type,
            bank = detectBank(sender, text),
            account = digits?.let { "XX$it" },
            instrument = instrument,
            mode = detectMode(lower, instrument),
            merchant = detectParty(text, type) ?: upiId,
            upiId = upiId,
            reference = REFERENCE.findAll(text).map { it.groupValues[1] }.firstOrNull { it.any(Char::isDigit) },
            balance = BALANCE.find(text)?.groupValues?.get(1)?.let(::toDouble),
            availableLimit = LIMIT.find(text)?.groupValues?.get(1)?.let(::toDouble),
        )
    }

    private fun toDouble(s: String): Double? = s.replace(",", "").trimEnd('.').toDoubleOrNull()

    private val PAYMENT_RECEIVED = Regex("""(?i)\bpayment\b.{0,40}\breceived\b|\breceived\b.{0,20}\bpayment\b""")

    private fun detectType(text: String): TxnType? {
        // "Payment of Rs 12,000 received towards your Credit Card" is money coming in to the card.
        if (PAYMENT_RECEIVED.containsMatchIn(text)) return TxnType.CREDIT
        val cleaned = TYPE_NOISE.replace(text, " card ")
        val debit = DEBIT_WORDS.find(cleaned)?.range?.first
        val credit = CREDIT_WORDS.find(cleaned)?.range?.first
        return when {
            debit == null && credit == null -> null
            credit == null -> TxnType.DEBIT
            debit == null -> TxnType.CREDIT
            debit <= credit -> TxnType.DEBIT
            else -> TxnType.CREDIT
        }
    }

    private fun detectAmount(text: String): Double? {
        for (m in AMOUNT.findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 22), m.range.first).lowercase()
            if (Regex("""bal(?:ance)?\b|bal\.|limit|lmt""").containsMatchIn(before)) continue
            toDouble(m.groupValues[1])?.let { return it }
        }
        return AMOUNT_NO_CURRENCY.find(text)?.groupValues?.get(1)?.let(::toDouble)
    }

    private fun detectBank(sender: String?, text: String): String? {
        val s = sender?.uppercase()?.let { " $it" }
        if (s != null) BANKS.firstOrNull { it.first.containsMatchIn(s) }?.let { return it.second }
        val upper = " " + text.uppercase()
        return BANKS.firstOrNull { it.first.containsMatchIn(upper) }?.second
    }

    private fun detectMode(lower: String, instrument: String?): String? = when {
        Regex("""\b(?:upi|vpa)\b""").containsMatchIn(lower) -> "UPI"
        "neft" in lower -> "NEFT"
        "imps" in lower -> "IMPS"
        "rtgs" in lower -> "RTGS"
        Regex("""\batm\b|cash withdrawal|withdrawn""").containsMatchIn(lower) -> "ATM"
        Regex("""\b(?:nach|ecs|ach|auto[- ]?debit|autopay|standing instruction|si|e-?mandate|mandate)\b""")
            .containsMatchIn(lower) -> "Auto-debit"
        Regex("""\b(?:chq|cheque)\b""").containsMatchIn(lower) -> "Cheque"
        Regex("""net ?banking|\bnetbk\b|\bib\b""").containsMatchIn(lower) -> "NetBanking"
        instrument?.contains("Card") == true || Regex("""\bpos\b""").containsMatchIn(lower) -> "Card"
        else -> null
    }

    private fun detectParty(text: String, type: TxnType): String? {
        INFO_DESCRIPTOR.find(text)?.groupValues?.get(1)?.let { info ->
            descriptorName(info)?.let { return it }
        }
        SLASH_DESCRIPTOR.find(text)?.value?.let { d -> descriptorName(d)?.let { return it } }

        val patterns = if (type == TxnType.DEBIT) DEBIT_PARTY else CREDIT_PARTY
        for (p in patterns) {
            for (m in p.findAll(text)) {
                clean(m.groupValues[1])?.let { return it }
            }
        }
        return null
    }

    /** Picks the most name-like segment of "UPI/P2M/1234567/SWIGGY LIMITED". */
    private fun descriptorName(d: String): String? {
        val parts = d.split('/', '-').map { it.trim() }.filter { it.isNotEmpty() }
        return parts.filter { p ->
            p.count(Char::isLetter) >= 3 &&
                !Regex("""(?i)^(?:upi|neft|imps|rtgs|pos|ach|nach|p2m|p2a|p2p|dr|cr|payment|txn|ref)$""").matches(p)
        }.firstNotNullOfOrNull { clean(it) }
    }

    private fun clean(raw: String): String? {
        var s = raw.trim().trim('.', ',', '-', ':', '*', '/', ' ')
        // Drop payment-rail prefixes such as "ACH-DR ", "POS " or card-network tags like "IND*".
        s = s.replace(Regex("""(?i)^(?:(?:ach|nach|ecs|pos|upi|neft|imps)(?:[\s\-/]*(?:dr|cr))?[\s\-/]+)+"""), "")
        s = s.replace(Regex("""^[A-Z]{2,4}\*"""), "")
        s = s.replace(Regex("""\s+"""), " ")
        if (s.length < 2 || s.count(Char::isLetter) < 2) return null
        if (BAD_PARTY.containsMatchIn(s)) return null
        if (Regex("""^\d""").containsMatchIn(s) && !s.contains('@')) return null
        return s.take(40).trim()
    }
}
