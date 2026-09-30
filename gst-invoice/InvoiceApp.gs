/**
 * GST Invoice maker — Google Apps Script web app for the "2026-27" invoice sheet.
 *
 * Every new invoice is a copy of the latest invoice tab (same layout, logo, bank details),
 * filled with the client's details and line items. The next invoice number, today's date,
 * the fixed HSN code and SGST/CGST vs IGST are all worked out here. A row is added to the
 * Summary tab for each invoice.
 *
 * Invoice tab layout this relies on (same as 2627_01 ... 2627_30):
 *   A11 client block (name / address / "GST - <GSTIN>")   F11 invoice no   F13 invoice date
 *   rows 18-21 items: A description, C HSN, D qty, E rate, F amount
 *   F22 total   E23/F23 SGST   E24/F24 CGST   E25/F25 IGST   F26 final   A28 =INR(F26)
 */

// ---------- Settings ----------
var SPREADSHEET_ID = '1Vkd0_SyWMR-Spr3hFAiE6miglJ325GtL-y5IOnrS2pw';
var SUMMARY_SHEET = 'Summary 26-27';
var HOME_STATE_CODE = '27';          // Maharashtra
var DEFAULT_HSN = '999629';
var GST_RATE = 0.18;                 // 9% + 9%, or 18% IGST
var FIRST_ITEM_ROW = 18;
var MAX_ITEMS = 4;                   // rows 18-21
var SUMMARY_FIRST_ROW = 4;
var CLAUDE_MODEL = 'claude-opus-5-5';

var GSTIN_RE = /\b(\d{2}[A-Z]{5}\d{4}[A-Z][0-9A-Z]Z[0-9A-Z])\b/;
var INVOICE_SHEET_RE = /^(\d{4})_(\d+)$/;

var STATES = {
  '01': 'Jammu & Kashmir', '02': 'Himachal Pradesh', '03': 'Punjab', '04': 'Chandigarh',
  '05': 'Uttarakhand', '06': 'Haryana', '07': 'Delhi', '08': 'Rajasthan', '09': 'Uttar Pradesh',
  '10': 'Bihar', '11': 'Sikkim', '12': 'Arunachal Pradesh', '13': 'Nagaland', '14': 'Manipur',
  '15': 'Mizoram', '16': 'Tripura', '17': 'Meghalaya', '18': 'Assam', '19': 'West Bengal',
  '20': 'Jharkhand', '21': 'Odisha', '22': 'Chhattisgarh', '23': 'Madhya Pradesh',
  '24': 'Gujarat', '26': 'Dadra & Nagar Haveli and Daman & Diu', '27': 'Maharashtra',
  '29': 'Karnataka', '30': 'Goa', '31': 'Lakshadweep', '32': 'Kerala', '33': 'Tamil Nadu',
  '34': 'Puducherry', '35': 'Andaman & Nicobar', '36': 'Telangana', '37': 'Andhra Pradesh',
  '38': 'Ladakh', '97': 'Other Territory'
};

// ---------- Web app ----------
function doGet() {
  return HtmlService.createTemplateFromFile('Index').evaluate()
    .setTitle('GST Invoice')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1')
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Invoices')
    .addItem('Rebuild Summary from all invoice tabs', 'rebuildSummary')
    .addToUi();
}

/** Data the form needs when it loads. */
function getAppInfo() {
  var today = new Date();
  var ss = book_();
  return {
    nextInvoiceNo: nextInvoiceNo_(ss, today),
    today: Utilities.formatDate(today, tz_(), 'd-MMM-yyyy'),
    hsn: DEFAULT_HSN,
    homeStateCode: HOME_STATE_CODE,
    gstRate: GST_RATE,
    maxItems: MAX_ITEMS,
    states: STATES,
    clients: pastClients_(ss),
    aiReader: !!claudeKey_()
  };
}

/**
 * Creates the invoice tab and its Summary row.
 * data: {name, address, gstin, taxType: 'auto'|'intra'|'inter', hsn, items: [{desc, qty, rate}]}
 */
function createInvoice(data) {
  var name = String(data.name || '').trim();
  var address = String(data.address || '').trim();
  var gstin = String(data.gstin || '').trim().toUpperCase().replace(/\s+/g, '');
  var hsn = String(data.hsn || DEFAULT_HSN).trim();
  if (!name) throw new Error('Client name is required.');
  if (gstin && !GSTIN_RE.test(gstin)) throw new Error('GST number "' + gstin + '" does not look valid.');

  var items = (data.items || []).map(function (it) {
    return { desc: String(it.desc || '').trim(), qty: Number(it.qty), rate: Number(it.rate) };
  }).filter(function (it) { return it.desc || it.rate; });
  if (!items.length) throw new Error('Add at least one item.');
  if (items.length > MAX_ITEMS) throw new Error('Maximum ' + MAX_ITEMS + ' items per invoice.');
  items.forEach(function (it, i) {
    if (!it.desc) throw new Error('Item ' + (i + 1) + ': description is missing.');
    if (!(it.qty > 0)) throw new Error('Item ' + (i + 1) + ': quantity must be more than 0.');
    if (!(it.rate > 0)) throw new Error('Item ' + (i + 1) + ': rate must be more than 0.');
  });

  var intra = isIntraState_(gstin, data.taxType);

  var lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    var ss = book_();
    var today = new Date();
    var invoiceNo = nextInvoiceNo_(ss, today);
    var sheetName = invoiceNo.replace('/', '_');

    var template = latestInvoiceSheet_(ss);
    if (!template) throw new Error('No existing invoice tab (like 2627_30) to copy the layout from.');
    var sh = template.copyTo(ss).setName(sheetName);
    ss.setActiveSheet(sh);
    ss.moveActiveSheet(ss.getNumSheets());

    var clientBlock = name + '\n' + address + (gstin ? '\n\nGST - ' + gstin : '');
    sh.getRange('A11').setValue(clientBlock);
    sh.getRange('F11').setNumberFormat('@').setValue(invoiceNo);
    sh.getRange('F13').setValue(dateOnly_(today)).setNumberFormat('d"-"mmm"-"yyyy');

    // Items: clear the copied rows, then write ours.
    var r0 = FIRST_ITEM_ROW;
    sh.getRange(r0, 1, MAX_ITEMS, 5).clearContent();
    items.forEach(function (it, i) {
      var r = r0 + i;
      sh.getRange(r, 1).setValue(it.desc);
      sh.getRange(r, 3).setValue(isNaN(Number(hsn)) ? hsn : Number(hsn));
      sh.getRange(r, 4).setValue(it.qty);
      sh.getRange(r, 5).setValue(it.rate);
    });
    for (var i = 0; i < MAX_ITEMS; i++) {
      var row = r0 + i;
      sh.getRange(row, 6).setFormula('=IF(D' + row + '="","",D' + row + '*E' + row + ')');
    }
    var last = r0 + MAX_ITEMS - 1;
    sh.getRange('F22').setFormula('=SUM(F' + r0 + ':F' + last + ')');

    var half = GST_RATE / 2;
    sh.getRange('E23:E25').setValues([[intra ? half : 0], [intra ? half : 0], [intra ? 0 : GST_RATE]])
      .setNumberFormat('0%');
    sh.getRange('F23').setFormula('=ROUND($F$22*E23,2)');
    sh.getRange('F24').setFormula('=ROUND($F$22*E24,2)');
    sh.getRange('F25').setFormula('=ROUND($F$22*E25,2)');
    sh.getRange('F26').setFormula('=SUM(F22:F25)');

    addSummaryRow_(ss, sheetName);
    SpreadsheetApp.flush();
    CacheService.getScriptCache().remove('clients');

    var basic = items.reduce(function (s, it) { return s + it.qty * it.rate; }, 0);
    var tax = round2_(basic * GST_RATE);
    var gid = sh.getSheetId();
    return {
      invoiceNo: invoiceNo,
      sheetName: sheetName,
      intra: intra,
      basic: basic,
      tax: tax,
      total: round2_(basic + tax),
      sheetUrl: ss.getUrl() + '#gid=' + gid,
      pdfUrl: pdfUrl_(ss.getId(), gid)
    };
  } finally {
    lock.releaseLock();
  }
}

/** Saves the invoice as a PDF in a Drive folder "GST Invoices" and returns its link. */
function saveInvoicePdf(sheetName) {
  var ss = book_();
  var sh = ss.getSheetByName(sheetName);
  if (!sh) throw new Error('Invoice tab ' + sheetName + ' not found.');
  var blob = UrlFetchApp.fetch(pdfUrl_(ss.getId(), sh.getSheetId()), {
    headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() }
  }).getBlob().setName('Invoice ' + sheetName + '.pdf');
  var folders = DriveApp.getFoldersByName('GST Invoices');
  var folder = folders.hasNext() ? folders.next() : DriveApp.createFolder('GST Invoices');
  var file = folder.createFile(blob);
  return file.getUrl();
}

// ---------- Reading client details from an image / PDF ----------

/**
 * file: {data: base64, mimeType, name}. Returns {name, address, gstin, source, rawText?}.
 * Uses Claude when an ANTHROPIC_API_KEY script property is set, otherwise Google Drive OCR.
 */
function readClientFromFile(file) {
  if (!file || !file.data) throw new Error('No file received.');
  var result = claudeKey_() ? readWithClaude_(file) : readWithDriveOcr_(file);
  result.gstin = String(result.gstin || '').toUpperCase().replace(/\s+/g, '');
  return result;
}

function readWithDriveOcr_(file) {
  var blob = Utilities.newBlob(Utilities.base64Decode(file.data), file.mimeType, file.name || 'upload');
  var created;
  try {
    created = Drive.Files.create({ name: 'gst-ocr-temp', mimeType: MimeType.GOOGLE_DOCS }, blob,
      { ocrLanguage: 'en', fields: 'id' });
  } catch (e) {
    throw new Error('Drive OCR failed. Make sure the "Drive API" service is added in Apps Script ' +
      '(Services → Drive API). Details: ' + e.message);
  }
  try {
    var text = DocumentApp.openById(created.id).getBody().getText();
    var parsed = parseGstCertificateText(text);
    parsed.source = 'Google OCR';
    parsed.rawText = text.slice(0, 4000);
    return parsed;
  } finally {
    DriveApp.getFileById(created.id).setTrashed(true);
  }
}

/** Pulls name / address / GSTIN out of GST certificate (REG-06) text. Also works on letterheads for the GSTIN. */
function parseGstCertificateText(text) {
  var t = String(text || '').replace(/\r/g, '');
  var flat = t.replace(/[ \t]+/g, ' ');
  var gstinMatch = flat.toUpperCase().replace(/(\d{2}[A-Z]{5})\s+(\d{4})/g, '$1$2').match(GSTIN_RE);
  var gstin = gstinMatch ? gstinMatch[1] : '';

  var legal = grabAfter_(flat, /Legal Name(?: of Business)?\s*[:\-]?/i,
    [/\n\s*\d+\.\s/, /Trade Name/i, /Constitution/i, /\n/]);
  var trade = grabAfter_(flat, /Trade Name,?\s*(?:if any)?\s*[:\-]?/i,
    [/\n\s*\d+\.\s/, /Constitution/i, /Address/i, /\n/]);
  var address = grabAfter_(flat, /Address of Principal Place of\s*Business\s*[:\-]?/i,
    [/\n\s*\d+\.\s*Date/i, /Date of Liability/i, /Date of Validity/i, /Type of Registration/i,
      /Particulars of Approving/i, /\n\s*5\.\s/]);

  var name = legal || trade;
  if (!name) {
    // Fallback: first line that looks like a company name.
    var lines = t.split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
    for (var i = 0; i < lines.length; i++) {
      if (/(PRIVATE LIMITED|PVT\.? LTD|LIMITED|LLP|ENTERPRISES|TRADERS|INDUSTRIES|& CO|SOLUTIONS|SERVICES)/i.test(lines[i])) {
        name = lines[i];
        break;
      }
    }
  }
  return {
    name: tidy_(name),
    tradeName: tidy_(trade),
    address: tidy_(address),
    gstin: gstin
  };
}

function grabAfter_(text, startRe, endRes) {
  var m = text.match(startRe);
  if (!m) return '';
  var rest = text.slice(m.index + m[0].length);
  var end = rest.length;
  endRes.forEach(function (re) {
    var e = rest.search(re);
    // Skip an end marker right at the start (e.g. the value sits on the next line).
    if (e === 0) {
      var sub = rest.slice(1).search(re);
      e = sub >= 0 ? sub + 1 : -1;
    }
    if (e > 0 && e < end) end = e;
  });
  return rest.slice(0, Math.min(end, 400));
}

function tidy_(s) {
  return String(s || '').replace(/\s*\n\s*/g, ' ').replace(/\s{2,}/g, ' ')
    .replace(/^[\s:,\-]+|[\s:,\-]+$/g, '').trim();
}

function readWithClaude_(file) {
  var isPdf = /pdf/i.test(file.mimeType);
  var fileBlock = isPdf
    ? { type: 'document', source: { type: 'base64', media_type: 'application/pdf', data: file.data } }
    : { type: 'image', source: { type: 'base64', media_type: file.mimeType, data: file.data } };
  var body = {
    model: CLAUDE_MODEL,
    max_tokens: 4000,
    output_config: { effort: 'low' },
    fallbacks: 'default',
    messages: [{
      role: 'user',
      content: [fileBlock, {
        type: 'text',
        text: 'This is an Indian client\'s GST registration certificate, visiting card, letterhead or ' +
          'similar document. Extract the details to put in the "Bill To" block of a GST invoice.\n' +
          'Reply with only a JSON object, no other text:\n' +
          '{"name": "<legal name of business, as registered>", "tradeName": "<trade name or empty>", ' +
          '"address": "<full address incl. city, state and PIN, one line, comma separated>", ' +
          '"gstin": "<15-character GSTIN or empty>"}\n' +
          'Use an empty string for anything not present. Do not guess a GSTIN.'
      }]
    }]
  };
  var res = UrlFetchApp.fetch('https://api.anthropic.com/v1/messages', {
    method: 'post',
    contentType: 'application/json',
    headers: {
      'x-api-key': claudeKey_(),
      'anthropic-version': '2023-06-01',
      'anthropic-beta': 'server-side-fallback-2026-07-01'
    },
    payload: JSON.stringify(body),
    muteHttpExceptions: true
  });
  var code = res.getResponseCode();
  var json = JSON.parse(res.getContentText());
  if (code !== 200) {
    throw new Error('Claude API error ' + code + ': ' + (json.error && json.error.message || res.getContentText()));
  }
  if (json.stop_reason === 'refusal') throw new Error('The document could not be read. Please fill the details manually.');
  var text = (json.content || []).filter(function (b) { return b.type === 'text'; })
    .map(function (b) { return b.text; }).join('\n');
  var m = text.match(/\{[\s\S]*\}/);
  if (!m) throw new Error('Could not read details from the document.');
  var out = JSON.parse(m[0]);
  return {
    name: tidy_(out.name || out.tradeName),
    tradeName: tidy_(out.tradeName),
    address: tidy_(out.address),
    gstin: out.gstin || '',
    source: 'Claude'
  };
}

// ---------- Summary ----------

function addSummaryRow_(ss, sheetName) {
  var sum = ss.getSheetByName(SUMMARY_SHEET);
  if (!sum) return;
  var last = lastSummaryRow_(sum);
  var row = last + 1;
  var srNo = last >= SUMMARY_FIRST_ROW ? (Number(sum.getRange(last, 1).getValue()) || (last - SUMMARY_FIRST_ROW + 1)) + 1 : 1;
  writeSummaryRow_(sum, row, srNo, sheetName);
  if (last >= SUMMARY_FIRST_ROW) {
    sum.getRange(last, 1, 1, 10).copyTo(sum.getRange(row, 1, 1, 10), SpreadsheetApp.CopyPasteType.PASTE_FORMAT, false);
  }
  updateSummaryTotals_(sum, row);
}

function writeSummaryRow_(sum, row, srNo, sheetName) {
  var q = "'" + sheetName + "'!";
  sum.getRange(row, 1, 1, 10).setValues([[
    srNo,
    '=' + q + 'F11',
    '=' + q + 'F13',
    '=IFERROR(REGEXEXTRACT(' + q + 'A11,"[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][0-9A-Z]Z[0-9A-Z]"),"")',
    '=' + q + 'C18',
    '=' + q + 'F26',
    '=' + q + 'F22',
    '=' + q + 'F23',
    '=' + q + 'F24',
    '=' + q + 'F25'
  ]]);
  sum.getRange(row, 2).setNumberFormat('@');
  sum.getRange(row, 3).setNumberFormat('d"-"mmm"-"yyyy');
  sum.getRange(row, 6, 1, 2).setNumberFormat('#,##0');
  sum.getRange(row, 8, 1, 3).setNumberFormat('#,##0.00');
}

function updateSummaryTotals_(sum, lastRow) {
  ['H', 'I', 'J'].forEach(function (col) {
    sum.getRange(col + '2').setFormula('=SUM(' + col + SUMMARY_FIRST_ROW + ':' + col + lastRow + ')');
  });
}

function lastSummaryRow_(sum) {
  var n = Math.max(sum.getLastRow() - SUMMARY_FIRST_ROW + 1, 0);
  if (!n) return SUMMARY_FIRST_ROW - 1;
  var vals = sum.getRange(SUMMARY_FIRST_ROW, 2, n, 1).getValues();
  for (var i = vals.length - 1; i >= 0; i--) {
    if (String(vals[i][0]).trim() !== '') return SUMMARY_FIRST_ROW + i;
  }
  return SUMMARY_FIRST_ROW - 1;
}

/** Menu action: rewrites the Summary rows so there is one row per invoice tab, in tab order. */
function rebuildSummary() {
  var ui = SpreadsheetApp.getUi();
  var ok = ui.alert('Rebuild Summary',
    'This rewrites rows ' + SUMMARY_FIRST_ROW + ' onwards of "' + SUMMARY_SHEET +
    '" with one row per invoice tab. Continue?', ui.ButtonSet.OK_CANCEL);
  if (ok !== ui.Button.OK) return;
  var ss = book_();
  var sum = ss.getSheetByName(SUMMARY_SHEET);
  var tabs = ss.getSheets().map(function (s) { return s.getName(); })
    .filter(function (n) { return INVOICE_SHEET_RE.test(n); });
  var oldLast = Math.max(sum.getLastRow(), SUMMARY_FIRST_ROW);
  var formatRow = sum.getRange(SUMMARY_FIRST_ROW, 1, 1, 10);
  sum.getRange(SUMMARY_FIRST_ROW, 1, oldLast - SUMMARY_FIRST_ROW + 1, 10).clearContent();
  tabs.forEach(function (name, i) {
    var row = SUMMARY_FIRST_ROW + i;
    if (i > 0) formatRow.copyTo(sum.getRange(row, 1, 1, 10), SpreadsheetApp.CopyPasteType.PASTE_FORMAT, false);
    writeSummaryRow_(sum, row, i + 1, name);
  });
  updateSummaryTotals_(sum, SUMMARY_FIRST_ROW + Math.max(tabs.length, 1) - 1);
  ui.alert('Summary now lists ' + tabs.length + ' invoices.');
}

// ---------- Helpers ----------

function book_() {
  return SpreadsheetApp.openById(SPREADSHEET_ID);
}

function tz_() {
  return Session.getScriptTimeZone() || 'Asia/Kolkata';
}

/** Today's date at midnight in the script time zone (so the sheet shows the right day). */
function dateOnly_(d) {
  var s = Utilities.formatDate(d, tz_(), 'yyyy-MM-dd').split('-');
  return new Date(Number(s[0]), Number(s[1]) - 1, Number(s[2]));
}

/** "2627" for any date from 1-Apr-2026 to 31-Mar-2027. */
function fyPrefix(d, timeZone) {
  var y = Number(Utilities.formatDate(d, timeZone || tz_(), 'yyyy'));
  var m = Number(Utilities.formatDate(d, timeZone || tz_(), 'M'));
  var start = m >= 4 ? y : y - 1;
  return String(start % 100).padStart(2, '0') + String((start + 1) % 100).padStart(2, '0');
}

/** Next number after the highest one in this financial year, looking at tab names and Summary. */
function nextInvoiceNo_(ss, today) {
  var prefix = fyPrefix(today);
  var seen = ss.getSheets().map(function (s) { return s.getName(); });
  var sum = ss.getSheetByName(SUMMARY_SHEET);
  if (sum && sum.getLastRow() >= SUMMARY_FIRST_ROW) {
    sum.getRange(SUMMARY_FIRST_ROW, 2, sum.getLastRow() - SUMMARY_FIRST_ROW + 1, 1).getDisplayValues()
      .forEach(function (r) { seen.push(r[0]); });
  }
  return nextInvoiceNoFrom(seen, prefix);
}

/** Pure helper (testable): given tab names / invoice numbers like "2627_30" or "2627/30". */
function nextInvoiceNoFrom(values, prefix) {
  var max = 0;
  var names = {};
  values.forEach(function (v) {
    var m = String(v).trim().match(/^(\d{4})[\/_](\d+)$/);
    if (m && m[1] === prefix) max = Math.max(max, Number(m[2]));
    names[String(v).trim()] = true;
  });
  var n = max + 1;
  while (names[prefix + '_' + String(n).padStart(2, '0')]) n++;
  return prefix + '/' + String(n).padStart(2, '0');
}

function latestInvoiceSheet_(ss) {
  var best = null, bestKey = -1;
  ss.getSheets().forEach(function (s) {
    var m = s.getName().match(INVOICE_SHEET_RE);
    if (!m) return;
    var key = Number(m[1]) * 10000 + Number(m[2]);
    if (key > bestKey) { bestKey = key; best = s; }
  });
  return best;
}

/** Intra-state (SGST+CGST) when the GSTIN state code is Maharashtra, unless overridden. */
function isIntraState_(gstin, taxType) {
  if (taxType === 'intra') return true;
  if (taxType === 'inter') return false;
  if (!gstin) return true;
  return gstin.slice(0, 2) === HOME_STATE_CODE;
}

/** Clients from earlier invoices, newest first, for the "Previous client" picker. */
function pastClients_(ss) {
  var cache = CacheService.getScriptCache();
  var hit = cache.get('clients');
  if (hit) return JSON.parse(hit);
  var byKey = {};
  var list = [];
  ss.getSheets().slice().reverse().forEach(function (s) {
    if (!INVOICE_SHEET_RE.test(s.getName())) return;
    var c = parseClientBlock(String(s.getRange('A11').getValue() || ''));
    if (!c.name) return;
    var key = (c.gstin || c.name).toUpperCase();
    if (byKey[key]) return;
    byKey[key] = true;
    list.push(c);
  });
  cache.put('clients', JSON.stringify(list), 600);
  return list;
}

/** Splits an A11 block ("Name\naddress...\nGST - XXXXX") into parts. Pure / testable. */
function parseClientBlock(text) {
  var lines = String(text).replace(/\r/g, '').split('\n');
  var name = (lines.shift() || '').trim();
  var gstin = '';
  var addr = [];
  lines.forEach(function (l) {
    var m = l.toUpperCase().match(GSTIN_RE);
    if (m) {
      gstin = m[1];
      l = l.replace(/GST(IN)?\s*(NO\.?)?\s*[-:]?\s*/i, '').replace(new RegExp(m[1], 'i'), '');
    }
    if (l.trim()) addr.push(l.trim());
  });
  return { name: name, address: addr.join('\n'), gstin: gstin };
}

function pdfUrl_(ssId, gid) {
  return 'https://docs.google.com/spreadsheets/d/' + ssId + '/export?format=pdf&gid=' + gid +
    '&size=A4&portrait=true&fitw=true&gridlines=false&printtitle=false&sheetnames=false' +
    '&pagenum=UNDEFINED&fzr=false&top_margin=0.5&bottom_margin=0.5&left_margin=0.5&right_margin=0.5' +
    '&attachment=true';
}

function claudeKey_() {
  return PropertiesService.getScriptProperties().getProperty('ANTHROPIC_API_KEY');
}

function round2_(n) {
  return Math.round(n * 100) / 100;
}
