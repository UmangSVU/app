package com.umang.fintrack.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StatementParserTest {

    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }.timeInMillis

    @Test
    fun creditCardStatement() {
        val text = """
            HDFC Bank Credit Card Statement
            Card No: 4893 XXXX XXXX 0705
            Statement Date: 05/10/2026  Payment Due Date: 25/10/2026
            Total Amount Due 12,345.00  Minimum Amount Due 620.00
            Domestic Transactions
            Date Transaction Description Amount (in Rs.)
            02/10/2026 GPAY-12196625297@OKBIZAXI 84.00
            15/09/2026 SWIGGY BANGALORE 450.50
            18/09/2026 PAYMENT RECEIVED - THANK YOU 10,000.00 Cr
            20/09/2026 AMAZON PAY INDIA PRIVA MUMBAI 1,499.00
        """.trimIndent()
        val s = StatementParser.parse(text)
        assertEquals("HDFC Bank", s.bank)
        assertEquals("XX0705", s.account)
        assertEquals("Credit Card", s.instrument)
        assertEquals(4, s.txns.size)
        assertEquals(StatementTxn(day(2026, 10, 2), 84.0, TxnType.DEBIT, "GPAY-12196625297@OKBIZAXI", s.txns[0].raw), s.txns[0])
        assertEquals(450.5, s.txns[1].amount, 0.001)
        assertEquals(TxnType.CREDIT, s.txns[2].type)
        assertEquals(10000.0, s.txns[2].amount, 0.001)
        assertEquals("AMAZON PAY INDIA PRIVA MUMBAI", s.txns[3].description)
    }

    @Test
    fun bankStatementUsesRunningBalance() {
        val text = """
            STATE BANK OF INDIA
            Account Number : XXXXXXX5678
            Txn Date Value Date Description Ref No Debit Credit Balance
            01 Sep 2026 Opening Balance 20,000.00
            03 Sep 2026 03 Sep 2026 UPI/DR/412345678901/RAMESH TEA 40.00 19,960.00
            05 Sep 2026 05 Sep 2026 NEFT-SALARY ACME PVT LTD 85,000.00 1,04,960.00
            07 Sep 2026 07 Sep 2026 ATM WDL ANDHERI 2,000.00 1,02,960.00
        """.trimIndent()
        val s = StatementParser.parse(text)
        assertEquals("State Bank of India", s.bank)
        assertEquals("XX5678", s.account)
        assertEquals("Bank Account", s.instrument)
        assertEquals(3, s.txns.size)
        assertEquals(TxnType.DEBIT, s.txns[0].type)
        assertEquals(40.0, s.txns[0].amount, 0.001)
        assertEquals(day(2026, 9, 3), s.txns[0].date)
        assertEquals(TxnType.CREDIT, s.txns[1].type)
        assertEquals(85000.0, s.txns[1].amount, 0.001)
        assertEquals(TxnType.DEBIT, s.txns[2].type)
    }

    @Test
    fun csvStyleRowsAndWrappedLines() {
        val text = """
            ICICI Bank Account XX901 Statement
            Date  Particulars  Withdrawals  Deposits  Balance
            Opening Balance 5,000.00
            02-10-26  UPI/ZOMATO/payment  320.00  4,680.00
            03-10-26  IMPS from RAHUL
              1,000.00  5,680.00
        """.trimIndent()
        val s = StatementParser.parse(text)
        assertEquals(2, s.txns.size)
        assertEquals(TxnType.DEBIT, s.txns[0].type)
        assertEquals(TxnType.CREDIT, s.txns[1].type)
        assertEquals(1000.0, s.txns[1].amount, 0.001)
        assertEquals(day(2026, 10, 3), s.txns[1].date)
    }

    @Test
    fun passwordCandidatesCoverCommonPatterns() {
        val c = StatementPasswords.candidates(
            name = "Umang Shah", dob = Triple(7, 3, 1995), mobile = "9876543210",
            cardDigits = listOf("0705"), extras = listOf("MyPass1"),
        )
        assertEquals("MyPass1", c.first())
        for (expected in listOf("UMAN0703", "uman0703", "07031995", "070395", "070319950705", "UMAN0705", "43210070395")) {
            assertTrue("missing $expected", expected in c)
        }
        assertEquals(c.size, c.toSet().size)
    }
}
