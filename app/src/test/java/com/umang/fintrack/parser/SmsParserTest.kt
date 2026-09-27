package com.umang.fintrack.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SmsParserTest {

    private fun parse(sender: String, body: String): ParsedSms {
        val parsed = SmsParser.parse(sender, body)
        assertNotNull("should parse: $body", parsed)
        return parsed!!
    }

    @Test
    fun hdfcUpiDebit() {
        val p = parse(
            "VM-HDFCBK",
            "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 27/09/26\nRef 426912345678\n" +
                "Not You?\nCall 18002586161/SMS BLOCK UPI to 7308080808"
        )
        assertEquals(250.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("HDFC Bank", p.bank)
        assertEquals("XX1234", p.account)
        assertEquals("SWIGGY", p.merchant)
        assertEquals("426912345678", p.reference)
        assertEquals("Food & Dining", CategorySuggester.suggest(p, "", null))
    }

    @Test
    fun hdfcCreditCardSpend() {
        val p = parse(
            "AD-HDFCBK",
            "Rs.1,499.00 spent on HDFC Bank Credit Card xx4321 at AMAZON PAY INDIA on 2026-09-27:10:21:33. " +
                "Avl Lmt: Rs.85,000.00. Not you? Call 18002586161"
        )
        assertEquals(1499.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("Credit Card", p.instrument)
        assertEquals("XX4321", p.account)
        assertEquals("AMAZON PAY INDIA", p.merchant)
        assertEquals(85000.0, p.availableLimit!!, 0.001)
        assertEquals("Shopping", CategorySuggester.suggest(p, "", null))
    }

    @Test
    fun sbiUpiDebitWithoutCurrency() {
        val p = parse(
            "JD-SBIUPI",
            "Dear UPI user A/C X5678 debited by 120.0 on date 27Sep26 trf to UBER INDIA Refno 426998877665. " +
                "If not u? call 1800111109. -SBI"
        )
        assertEquals(120.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("State Bank of India", p.bank)
        assertEquals("XX5678", p.account)
        assertEquals("UBER INDIA", p.merchant)
        assertEquals("UPI", p.mode)
        assertEquals("Transport", CategorySuggester.suggest(p, "", null))
    }

    @Test
    fun icicCredit() {
        val p = parse(
            "VK-ICICIB",
            "ICICI Bank Acct XX901 credited with Rs 25,000.00 on 27-Sep-26; RAHUL SHARMA credited. " +
                "UPI:426911112222. Call 18002662 for dispute. SMS BLOCK 901 to 9215676766."
        )
        assertEquals(25000.0, p.amount, 0.001)
        assertEquals(TxnType.CREDIT, p.type)
        assertEquals("ICICI Bank", p.bank)
        assertEquals("XX901", p.account)
    }

    @Test
    fun hdfcCreditWithVpaAndBalance() {
        val p = parse(
            "VM-HDFCBK",
            "Update! INR 5,000.00 deposited in HDFC Bank A/c XX1234 on 27-SEP-26 for UPI-rahul@okaxis-RAHUL. " +
                "Avl bal INR 12,345.67. Cheque deposits in A/C are subject to clearing"
        )
        assertEquals(5000.0, p.amount, 0.001)
        assertEquals(TxnType.CREDIT, p.type)
        assertEquals("rahul@okaxis", p.upiId)
        assertEquals(12345.67, p.balance!!, 0.001)
        assertEquals("Income", CategorySuggester.suggest(p, "", null))
    }

    @Test
    fun axisDebitCardPos() {
        val p = parse(
            "AX-AXISBK",
            "INR 640.00 debited from A/c no. XX3344 on 27-09-2026 at ZEPTO MARKETPLACE. Avl Bal INR 9,210.50"
        )
        assertEquals(640.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("Axis Bank", p.bank)
        assertEquals("ZEPTO MARKETPLACE", p.merchant)
        assertEquals(9210.5, p.balance!!, 0.001)
        assertEquals("Groceries", CategorySuggester.suggest(p, "", null))
    }

    @Test
    fun atmWithdrawal() {
        val p = parse(
            "BZ-KOTAKB",
            "Rs 2000.00 withdrawn at ATM KOTAK ANDHERI from Kotak Bank Debit Card XX7788 on 27/09/2026. " +
                "Avl bal Rs 15000.00"
        )
        assertEquals(2000.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("Debit Card", p.instrument)
        assertEquals("XX7788", p.account)
        assertEquals("ATM", p.mode)
        assertEquals("Cash", CategorySuggester.suggest(p, "", null))
    }

    @Test
    fun salaryCredit() {
        val p = parse(
            "VM-HDFCBK",
            "Update! INR 85,000.00 deposited in HDFC Bank A/c XX1234 on 01-SEP-26 for NEFT Cr-SALARY SEP 2026-" +
                "ACME PVT LTD. Avl bal INR 97,000.00."
        )
        assertEquals(TxnType.CREDIT, p.type)
        assertEquals("NEFT", p.mode)
        assertEquals("Salary", CategorySuggester.suggest(p, "salary", null))
    }

    @Test
    fun learnedCategoryWins() {
        val p = parse("VM-HDFCBK", "Sent Rs.40.00 From HDFC Bank A/C *1234 To RAMESH TEA STALL On 27/09/26 Ref 1234567")
        assertEquals("RAMESH TEA STALL", p.merchant)
        assertEquals("Food & Dining", CategorySuggester.suggest(p, "", "Food & Dining"))
        assertEquals("rameshteastall", CategorySuggester.merchantKey(p))
    }

    @Test
    fun ignoresOtpAndReminders() {
        assertNull(SmsParser.parse("VM-HDFCBK", "123456 is your OTP for txn of Rs 500.00 at AMAZON. Do not share."))
        assertNull(SmsParser.parse("VM-HDFCBK", "Your credit card bill of Rs 5,000 is due on 05-Oct-26."))
        assertNull(SmsParser.parse("VM-HDFCBK", "Rs 999 will be debited from your a/c on 01-Oct for Netflix mandate."))
        assertNull(SmsParser.parse("VM-HDFCBK", "Transaction of Rs 500 at SWIGGY failed due to insufficient balance"))
        assertNull(SmsParser.parse("JM-SWIGGY", "Get 50% off on your next order! Use code YUMMY."))
    }

    @Test
    fun iciciCardWithNetworkPrefix() {
        val p = parse(
            "AX-ICICIT",
            "INR 1,234.00 spent using ICICI Bank Card XX1111 on 27-Sep-26 on IND*Flipkart. Avl Limit: INR 45,000.00."
        )
        assertEquals(1234.0, p.amount, 0.001)
        assertEquals("Flipkart", p.merchant)
        assertEquals(45000.0, p.availableLimit!!, 0.001)
    }

    @Test
    fun bobCreditByVpa() {
        val p = parse(
            "JK-BOBTXN",
            "Rs.3000.00 Credited to A/c ...8899 thru UPI/426955554444 by ramesh@ybl. Total Bal:Rs.10500.00CR."
        )
        assertEquals(3000.0, p.amount, 0.001)
        assertEquals(TxnType.CREDIT, p.type)
        assertEquals("Bank of Baroda", p.bank)
        assertEquals("XX8899", p.account)
        assertEquals("ramesh@ybl", p.merchant)
        assertEquals("426955554444", p.reference)
    }

    @Test
    fun achAutoDebit() {
        val p = parse(
            "VM-AXISBK",
            "Your A/c no. XX3344 has been debited with INR 1,050.00 on 27-09-26 by ACH-DR BAJAJ FINANCE LTD."
        )
        assertEquals("Auto-debit", p.mode)
        assertEquals("BAJAJ FINANCE LTD", p.merchant)
        assertEquals("EMI & Loans", CategorySuggester.suggest(p, "", null))
    }

    @Test
    fun creditCardBillPaymentIsCredit() {
        val body = "Payment of Rs 12,000.00 received towards your HDFC Bank Credit Card ending 4321 on 27-09-2026."
        val p = parse("VM-HDFCBK", body)
        assertEquals(TxnType.CREDIT, p.type)
        assertEquals("XX4321", p.account)
        assertEquals("Transfer", CategorySuggester.suggest(p, body, null))
    }

    @Test
    fun ignoresCreditCardBillReminders() {
        listOf(
            "Statement for HDFC Bank Credit Card XX4321: Total Amt Due Rs.12,450.00, Min Amt Due Rs.630.00, " +
                "Due Dt 05-Oct-26. Pay via NetBanking/UPI.",
            "Reminder: Your ICICI Bank Credit Card XX1111 bill of INR 8,200.00 is pending. Pay now to avoid late fee.",
            "Your SBI Card ending 5566 e-statement has been sent. Total Amount Due: Rs 3,499; Minimum Due: Rs 200.",
            "Axis Bank Credit Card XX9090 bill generated. Amount payable Rs 15,000.00. Last date 10-10-2026.",
            "Gentle reminder: Rs 2,300 outstanding on your Kotak Credit Card XX7788 is overdue. Kindly pay.",
        ).forEach { assertNull(it, SmsParser.parse("VM-HDFCBK", it)) }
    }

    @Test
    fun cardSpendWithDueWordsStillParses() {
        // A real spend SMS must survive even if it happens to mention the bill.
        val p = parse("AD-HDFCBK", "Rs.500.00 spent on HDFC Bank Credit Card XX4321 at ZOMATO on 27-09-26. Avl Lmt Rs.10,000")
        assertEquals(500.0, p.amount, 0.001)
    }
}
