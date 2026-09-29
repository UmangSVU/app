# FinTrack — SMS money tracker for Android

A personal Android app that watches your bank / card / UPI SMS and makes you file every
debit and credit under a category.

## What it does

- **Reads each incoming SMS** and detects debits and credits (UPI, cards, NEFT/IMPS, ATM,
  auto-debit, wallets). OTPs, bill-due reminders, "will be debited" notices, failed
  transactions and promos are ignored.
- **Pulls details from the message**: amount, debit or credit, bank, masked account or card
  number (e.g. `Credit Card XX4321`), payee or payer, UPI ID, reference number, balance
  after the transaction, and available credit limit.
- **Opens a category pop-up** with a **suggested category** already selected (Swiggy →
  Food & Dining, Uber → Transport, ATM → Cash, salary → Salary, …). You can change it,
  edit the amount, payee or type, add a note, and tap **Submit**.
- **Won't go away until you pick a category**: Back doesn't close the pop-up, and the
  notification is ongoing. If you swipe it away, it comes back. The notification also has a
  one-tap "✓ \<suggested category\>" button.
- **Doesn't miss SMS**: no internet needed. Besides reacting to each incoming SMS, the app
  checks the SMS inbox for anything it missed when you open it, every 15 minutes, after a
  reboot, and when the phone comes back online. **⟳ Refresh** (top right) checks the last 7
  days on demand.
- **Learns**: once you file a merchant under a category, it suggests that category for that
  merchant from then on.
- **Two SIMs, two separate books**: each SMS is filed under the SIM it arrived on. You can
  name the books (e.g. "Personal" / "Business") in Setup and switch between them at the top of
  the screen. An entry can be moved to the other book from ✎ Edit details.
- **Card bill and statement reminders are skipped** (total/min amount due, due date, pay now,
  statements). If something still slips through, tap **✕ Not a transaction – don't count** in
  the pop-up or the notification. It stays out of every total, and you can bring it back from
  History → Not counted.
- **Group bills**: turn on *Group bill / split* in the pop-up and enter your share (or tap ½, ⅓,
  ¼, ⅕). Only your share counts as spending; the rest is shown as "paid for others".
- **Home**: spending and income for each month, each with its own colour-coded
  category breakdown, plus a Banks & cards section. Tap any row to see its transactions,
  and tap a transaction to open it. Transfers between your own accounts are shown on their
  own and left out of the totals.
- **Categories are split into Spending and Income.** The pop-up only offers the ones that
  match a debit or a credit.
- **Daily backup to Google Drive**: link a Drive file once in Setup, and at about 9 PM each
  day the app replaces that one file (the Drive app uploads it). The same single file is kept
  in Downloads/FinTrack. On a new phone, **Setup → Restore from backup file** merges it in.
- **History, custom categories, cash entries** (the ＋ button), and an import of the **last
  90 days of SMS**.

Everything stays on the phone in a local SQLite database. There's no internet permission; backups are uploaded by the Google Drive app.

## Install on your phone

1. On your phone, open
   **https://github.com/UmangSVU/app/releases/latest/download/FinTrack.apk**
   (or go to the repo's **Releases** section and tap `FinTrack.apk`).
2. Open the downloaded file and allow "Install unknown apps" for your browser when asked.
3. Open FinTrack → **Setup** tab and allow:
   1. **SMS**. On Android 13+ a sideloaded app may be blocked with *"Restricted setting"*.
      Go to *Settings → Apps → FinTrack → ⋮ → Allow restricted settings*, then tap Allow
      again.
   2. **Notifications**
   3. **Display over other apps**, so the pop-up opens instantly while you use the phone.
   4. **Full-screen alerts** (Android 14+), so it shows on the lock screen.
4. Optional: **Import last 90 days** to fill in your history.

On Xiaomi / Oppo / Vivo / Realme phones, also turn on **Autostart** for FinTrack and set its
battery usage to **No restrictions**. Otherwise the system may block SMS delivery to the app.

## Build locally

Needs Android Studio (or the Android SDK plus JDK 17):

```
./gradlew testDebugUnitTest   # SMS parser tests
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

## Code map

| Path | What it is |
| --- | --- |
| `parser/SmsParser.kt` | Pure-Kotlin SMS → transaction parser (amount, type, bank, account/card, merchant, UPI, ref, balance) |
| `parser/CategorySuggester.kt` | Default-category rules and merchant keys for learning |
| `data/FinanceDb.kt` | SQLite storage: transactions, categories, learned merchant rules |
| `sms/SmsReceiver.kt` | Incoming-SMS receiver and inbox importer |
| `notify/Notifier.kt` | Sticky notification that re-posts itself, quick-accept action, re-post on boot |
| `ui/CategorizeActivity.kt` | The category pop-up |
| `ui/MainActivity.kt` | Home, History, Categories and Setup tabs |
