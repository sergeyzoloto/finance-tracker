// The partner's transactions file, reduced in the browser before anything is uploaded (D-78, D-140): only his family
// rows (Family = "да", an item other than "-"), and of each only what the import needs: the ID, the date, the item,
// the Family flag, and the side on UNALLOCATED ("Debit" or "Credit") with that side's currency and amount, and where
// his file has D-235's columns the item's code and type (D-260). His other rows, his accounts, counterparties and
// comments never leave this browser. The server refuses the whole request if a row or a column gets past this
// (backend: Workbook.partner, PartnerFilter is the same filter for the command line).

/** The columns of the upload, in order, exactly; the server refuses any other. */
export const PARTNER_COLUMNS = ['ID', 'Date', 'Exp. Item', 'Family', 'Side', 'Currency', 'Sum'] as const
/** And the item's code and type, where his file has D-235's columns (D-260). */
export const PARTNER_ITEM_COLUMNS = ['CODE_ITEM', 'TYPE'] as const

/** The name the upload goes by: his export's file names carry his first name (D-143). */
export const PARTNER_FILE_NAME = 'partner-family-rows.csv'

const FAMILY_YES = 'да'
const NO_ITEM = '-'
const SPACES = /^[\s  ]+|[\s  ]+$/g

/** Excel's CSV with semicolons: fields quoted where needed, CRLF or LF, a byte order mark ignored. Rows as fields. */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  let i = text.charCodeAt(0) === 0xfeff ? 1 : 0
  for (; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        field += '"'
        i++
      } else if (c === '"') {
        quoted = false
      } else {
        field += c
      }
    } else if (c === '"') {
      quoted = true
    } else if (c === ';') {
      row.push(field)
      field = ''
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++
      row.push(field)
      rows.push(row)
      row = []
      field = ''
    } else {
      field += c
    }
  }
  if (field !== '' || row.length > 0) {
    row.push(field)
    rows.push(row)
  }
  return rows.filter((r) => r.some((f) => trim(f) !== ''))
}

const trim = (value: string) => value.replace(SPACES, '')

/** A field for the upload: quoted where it holds a semicolon, a quote or a line break. */
function field(value: string): string {
  return /[;"\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value
}

/**
 * The name of UNALLOCATED in the user's own transactions file (D-235, D-260): Debit or Credit of a row whose CODE_DEBIT
 * or CODE_CREDIT is UNALLOCATED. The partner's file names it the same way, in any case. Null if no row has it.
 */
export function unallocatedName(transactionsCsv: string): string | null {
  const [header, ...rows] = parseCsv(transactionsCsv)
  if (!header) return null
  const names = header.map(trim)
  const sides = [['CODE_DEBIT', 'Debit'], ['CODE_CREDIT', 'Credit']].map(([code, name]) =>
    [names.indexOf(code), names.indexOf(name)])
  if (sides.some(([code, name]) => code < 0 || name < 0)) return null
  for (const row of rows) {
    for (const [code, name] of sides) {
      if (trim(row[code] ?? '') === 'UNALLOCATED') return trim(row[name] ?? '')
    }
  }
  return null
}

/** The header of a transactions file (D-235): exactly these 32 columns, in this order. */
export const TRANSACTIONS_COLUMNS = [
  'ID', 'Date', 'Exp. Item', 'Sum', 'Currency', 'Counteragent', 'Comment', 'Debit', 'Credit', 'Family', 'CUR_DEBIT',
  'SUM_DEBIT', 'CUR_CREDIT', 'SUM_CREDIT', 'LOAN_CA', 'CODE_ITEM', 'TYPE', 'CODE_DEBIT', 'CODE_CREDIT', 'TYPE_DEBIT',
  'TYPE_CREDIT', 'COUNT_DEBIT', 'COUNT_CREDIT', 'DEBIT_CHANGE', 'CREDIT_CHANGE', 'CUR_FAMILY', 'FAMILY_EXP',
  'FAMILY_INC', 'CHECK_CORR', 'LOAN_SUM', 'LOAN_CUR', 'EXP_ITEM_CODE',
] as const

/**
 * What the page shows of the user's file before the check (D-221, D-257): its rows as the import counts them, its
 * highest Excel ID and its last row date (yyyy-MM-dd); whether its header is D-235's.
 */
export interface FileFacts { rows: number; highestId: string | null; lastDate: string | null; header: boolean }

export function fileFacts(transactionsCsv: string): FileFacts {
  const [header, ...rows] = parseCsv(transactionsCsv)
  const names = (header ?? []).map(trim)
  const isHeader = names.length === TRANSACTIONS_COLUMNS.length && names.every((n, i) => n === TRANSACTIONS_COLUMNS[i])
  let highest: bigint | null = null
  let last: string | null = null
  for (const row of rows) {
    const id = trim(row[0] ?? '')
    if (/^[1-9][0-9]{0,17}$/.test(id) && (highest === null || BigInt(id) > highest)) highest = BigInt(id)
    const match = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(trim(row[1] ?? ''))
    if (match) {
      const date = `${match[3]}-${match[2]}-${match[1]}`
      if (last === null || date > last) last = date
    }
  }
  return { rows: rows.length, highestId: highest === null ? null : String(highest), lastDate: last, header: isHeader }
}

export interface PartnerUpload {
  /** The upload, CSV with semicolons and {@link PARTNER_COLUMNS}. */
  csv: string
  /** His rows read, and those uploaded. */
  read: number
  kept: number
  /** Kept rows with neither side on UNALLOCATED: the server reports them as errors, by row. */
  withoutUnallocated: number
}

/**
 * His family rows with an item, reduced to {@link PARTNER_COLUMNS}.
 *
 * @throws Error if the file lacks a column the filter reads
 */
export function filterPartnerRows(partnerCsv: string, unallocated: string): PartnerUpload {
  const [header, ...rows] = parseCsv(partnerCsv)
  const names = (header ?? []).map(trim)
  const column = (name: string) => {
    const index = names.indexOf(name)
    if (index < 0) throw new Error(`The partner's transactions file has no column "${name}".`)
    return index
  }
  const c = {
    id: column('ID'), date: column('Date'), item: column('Exp. Item'), sum: column('Sum'), currency: column('Currency'),
    debit: column('Debit'), credit: column('Credit'), family: column('Family'), curDebit: column('CUR_DEBIT'),
    sumDebit: column('SUM_DEBIT'), curCredit: column('CUR_CREDIT'), sumCredit: column('SUM_CREDIT'),
  }
  const ua = unallocated.toLowerCase()
  const items = PARTNER_ITEM_COLUMNS.every((n) => names.includes(n))
  const codeItem = names.indexOf('CODE_ITEM')
  const type = names.indexOf('TYPE')
  const lines = [[...PARTNER_COLUMNS, ...(items ? PARTNER_ITEM_COLUMNS : [])].join(';')]
  let kept = 0
  let withoutUnallocated = 0
  for (const row of rows) {
    const get = (i: number) => trim(row[i] ?? '')
    if (get(c.family) !== FAMILY_YES || get(c.item) === NO_ITEM) continue
    let side = ''
    let currency = get(c.currency)
    let sum = get(c.sum)
    if (get(c.debit).toLowerCase() === ua) {
      side = 'Debit'
      if (get(c.curDebit) !== '') {
        currency = get(c.curDebit)
        sum = get(c.sumDebit)
      }
    } else if (get(c.credit).toLowerCase() === ua) {
      side = 'Credit'
      if (get(c.curCredit) !== '') {
        currency = get(c.curCredit)
        sum = get(c.sumCredit)
      }
    } else {
      withoutUnallocated++
    }
    const values = [get(c.id), get(c.date), get(c.item), FAMILY_YES, side, currency, sum]
    if (items) values.push(get(codeItem), get(type))
    lines.push(values.map(field).join(';'))
    kept++
  }
  return { csv: `${lines.join('\r\n')}\r\n`, read: rows.length, kept, withoutUnallocated }
}

/**
 * The request's body: the user's own transactions file as chosen (D4b), and the partner's rows as filtered, under a
 * name of their own. Never the partner's raw file: the page takes no file of his but the transactions, which it filters.
 */
export function importForm(transactions: Blob, name: string, partner?: PartnerUpload): FormData {
  const body = new FormData()
  body.append('transactions', transactions, name)
  if (partner) {
    body.append('partnerTransactions', new Blob([partner.csv], { type: 'text/csv' }), PARTNER_FILE_NAME)
  }
  return body
}
