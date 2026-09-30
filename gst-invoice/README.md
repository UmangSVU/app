# GST Invoice web app (Google Sheets)

A private web page (works on phone and laptop) that adds a new invoice tab to the
**2026-27** invoice Google Sheet. You enter the client and the items, and the page fills in
the rest:

| Field | How it is filled |
|---|---|
| Invoice no | Next number after the highest one this financial year: `2627/30` → `2627/31`. Resets to `/01` on 1 April (`2728/01`). |
| Tab name | `2627_31`, added at the end |
| Invoice date | Today (India time) |
| HSN code | `999629` (can be changed on the form) |
| Tax | GST number starting `27` (Maharashtra) → SGST 9% + CGST 9%. Any other state → IGST 18%. No GST number → SGST + CGST. You can override it on the form. |
| Layout, logo, bank details, amount in words | Copied from the latest invoice tab |
| Summary tab | A new row is added for each invoice |

Client details can be typed in, picked from earlier invoices, or read from a **photo**
or **GST certificate PDF**.

## One-time setup (about 5 minutes)

1. Open the sheet → **Extensions → Apps Script**.
2. Add a new script file (**+ → Script**) named `InvoiceApp` and paste in
   `InvoiceApp.gs`. **Don't replace your existing `Code.gs`**: it holds the `INR()`
   function that writes the amount in words.
3. Add an HTML file (**+ → HTML**) named `Index` and paste in `Index.html`.
4. **Project Settings (⚙)**: set the time zone to *(GMT+05:30) India Standard Time*.
5. **Services (+)** → **Drive API** → Add. This is used to read GST certificates and photos.
6. **Deploy → New deployment → Web app**
   - Execute as: **Me**
   - Who has access: **Only myself**. You open the link while signed in to your Google
     account, on any device.
   - Click **Deploy**, allow the permissions, and copy the **Web app URL**. Bookmark it or add
     it to your phone's home screen.

After you change the code later, use **Deploy → Manage deployments → Edit → Version: New
version** so the same URL gets the update.

## Better reading of photos (optional)

Without extra setup, uploads are read with Google's free OCR. This works well for the
GST certificate (Form REG-06) PDF from the GST portal. For phone photos, visiting cards
and letterheads, you can use Claude instead:

1. Get an API key from https://console.anthropic.com
2. Apps Script → **Project Settings → Script properties → Add**:
   `ANTHROPIC_API_KEY` = your key.

The form shows which reader it used. Always check the fields before you click **Create**.

## Summary tab

Right now the Summary tab lists invoices 1–13 only. After setup, reload the sheet and run
**Invoices → Rebuild Summary from all invoice tabs**. This writes one row per invoice tab.
The rows read their values from the tabs, and the GST number comes from each tab's client
block. The web app then adds a row for each new invoice.

## Notes

- Up to 4 items per invoice (rows 18–21 of the layout).
- Tab `2526_08` looks like a naming slip (its invoice is `2627/08`). It doesn't affect
  numbering. You can rename it to `2627_08`.
- Invoice `2627/29` (Gujarat, GST `24…`) was billed with SGST + CGST. Under the rule above,
  it should have been IGST.
