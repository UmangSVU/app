package com.umang.fintrack.parser

/** Picks a default category for a parsed transaction. Pure Kotlin, no Android dependencies. */
object CategorySuggester {

    const val OTHER = "Other"
    const val INCOME = "Income"
    const val TRANSFER = "Transfer"
    const val TRANSFER_IN = "Transfer In"
    const val INTEREST = "Interest & Dividends"

    private class Rule(val category: String, keywords: List<String>) {
        val regex = Regex(
            keywords.joinToString("|", prefix = """(?i)(?<![a-z])(?:""", postfix = """)(?![a-z])""") {
                Regex.escape(it)
            }
        )
    }

    private val DEBIT_RULES = listOf(
        Rule("Food & Dining", listOf(
            "swiggy", "zomato", "restaurant", "cafe", "coffee", "starbucks", "dominos", "domino's", "pizza",
            "mcdonald", "mcdonalds", "kfc", "burger king", "subway", "eatfit", "eatclub", "haldiram", "barbeque",
            "chaayos", "chai", "chai point", "tea", "dunkin", "baskin", "food", "dhaba", "bakery", "hotel",
        )),
        Rule("Groceries", listOf(
            "bigbasket", "blinkit", "zepto", "instamart", "dmart", "d mart", "avenue supermarts", "grofers",
            "jiomart", "reliance fresh", "more retail", "nature's basket", "spencers", "grocery", "kirana",
            "supermarket", "milkbasket", "country delight",
        )),
        Rule("Fuel", listOf(
            "petrol", "diesel", "fuel", "hpcl", "bpcl", "iocl", "indian oil", "hindustan petroleum",
            "bharat petroleum", "shell", "nayara", "reliance bp",
        )),
        Rule("Transport", listOf(
            "uber", "ola", "olacabs", "rapido", "metro", "mmrda", "bmrcl", "dmrc", "fastag", "toll",
            "parking", "namma yatri", "blu smart", "blusmart", "cab", "taxi",
        )),
        Rule("Travel", listOf(
            "irctc", "makemytrip", "goibibo", "cleartrip", "yatra", "ixigo", "redbus", "indigo", "air india",
            "vistara", "spicejet", "akasa", "airbnb", "oyo", "booking.com", "agoda", "railway", "airlines",
        )),
        Rule("Shopping", listOf(
            "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "snapdeal", "croma",
            "reliance digital", "decathlon", "ikea", "lifestyle", "westside", "pantaloons", "h&m", "zara",
            "uniqlo", "shoppers stop", "lenskart", "firstcry", "purplle",
        )),
        Rule("Bills & Utilities", listOf(
            "electricity", "bescom", "msedcl", "mseb", "tata power", "adani electricity", "torrent power",
            "bses", "water bill", "gas", "mahanagar gas", "indane", "hp gas", "bharat gas", "broadband",
            "airtel", "jio", "vodafone", "vi", "bsnl", "act fibernet", "hathway", "recharge", "postpaid",
            "prepaid", "dth", "tata play", "bill", "billdesk", "bbps", "insurance", "lic",
        )),
        Rule("Entertainment", listOf(
            "netflix", "spotify", "hotstar", "disney", "prime video", "primevideo", "sonyliv", "zee5",
            "youtube", "bookmyshow", "pvr", "inox", "jiocinema", "apple.com", "google play", "playstation",
            "steam", "gaana", "wynk", "audible",
        )),
        Rule("Health", listOf(
            "pharmacy", "pharma", "apollo", "medplus", "1mg", "tata 1mg", "pharmeasy", "netmeds", "hospital",
            "clinic", "diagnostic", "lab", "healthians", "practo", "medical", "chemist", "dental", "cult.fit",
            "cultfit", "gym",
        )),
        Rule("Education", listOf(
            "school", "college", "university", "tuition", "coursera", "udemy", "byjus", "unacademy", "fees",
            "exam", "books", "somaiya",
        )),
        Rule("Rent", listOf("rent", "nobroker", "nestaway", "housing.com", "maintenance", "society")),
        Rule("EMI & Loans", listOf(
            "emi", "loan", "bajaj finance", "bajaj finserv", "home credit", "lazypay", "simpl", "zestmoney",
            "kreditbee", "moneyview", "navi",
        )),
        Rule("Investment", listOf(
            "zerodha", "groww", "upstox", "angel one", "angelone", "kuvera", "mutual fund", "sip",
            "ppf", "nps", "smallcase", "indmoney", "dhan", "icici direct", "hdfc securities", "etmoney",
            "fixed deposit", "recurring deposit",
        )),
    )

    fun suggest(parsed: ParsedSms, body: String, learned: String?): String {
        if (!learned.isNullOrBlank()) return learned

        val haystack = listOfNotNull(parsed.merchant, parsed.upiId, body).joinToString(" ")
        val lower = haystack.lowercase()

        if (parsed.type == TxnType.CREDIT) {
            return when {
                "salary" in lower || "sal cr" in lower || "payroll" in lower -> "Salary"
                Regex("""\brefund|revers|cashback\b""").containsMatchIn(lower) -> "Refund"
                "credit card" in lower && Regex("""payment|received""").containsMatchIn(lower) -> TRANSFER_IN
                "interest" in lower || "dividend" in lower -> INTEREST
                else -> INCOME
            }
        }

        if (parsed.mode == "ATM") return "Cash"
        // Merchant name wins over words found elsewhere in the SMS (e.g. the bank's own footer).
        val merchantText = listOfNotNull(parsed.merchant, parsed.upiId).joinToString(" ")
        DEBIT_RULES.firstOrNull { it.regex.containsMatchIn(merchantText) }?.let { return it.category }
        DEBIT_RULES.firstOrNull { it.regex.containsMatchIn(haystack) }?.let { return it.category }
        if (parsed.mode == "Auto-debit") return "EMI & Loans"
        if ("credit card" in lower && Regex("""payment (?:of|towards)|bill payment""").containsMatchIn(lower)) {
            return TRANSFER
        }
        return OTHER
    }

    /** Stable key for remembering "this merchant is always category X". */
    fun merchantKey(parsed: ParsedSms): String? = merchantKey(parsed.merchant ?: parsed.upiId)

    fun merchantKey(merchant: String?): String? {
        val key = merchant?.lowercase()?.replace(Regex("""[^a-z0-9@]"""), "")
        return key?.takeIf { it.length >= 3 }
    }
}
