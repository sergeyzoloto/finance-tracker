// The partner's transactions file, reduced in the browser before anything is uploaded (D-78, D-140): only his family
// rows (Family = "да", an item other than "-"), and of each only what the import needs: the ID, the date, the item,
// the Family flag, and the side on UNALLOCATED ("Debit" or "Credit") with that side's currency and amount. His other
// rows, his accounts, counterparties and comments, and his daily files never leave this browser. The server refuses
// the whole request if a row or a column gets past this (backend: Workbook.partner, PartnerFilter is the same filter
// for the command line).

/** The columns of the upload, in order, exactly; the server refuses any other. */
export const PARTNER_COLUMNS = ['ID', 'Date', 'Exp. Item', 'Family', 'Side', 'Currency', 'Sum'] as const

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
 * The name of UNALLOCATED in the owner's accounts file ("Статьи баланса;Актив/пассив;Line"): the account whose Line is
 * UNALLOCATED. The partner's file names it the same way, in any case. Null if the file has none.
 */
export function unallocatedName(accountsCsv: string): string | null {
  const [header, ...rows] = parseCsv(accountsCsv)
  if (!header) return null
  const line = header.map(trim).indexOf('Line')
  if (line < 0) return null
  const row = rows.find((r) => trim(r[line] ?? '') === 'UNALLOCATED')
  return row ? trim(row[0]) : null
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
  const lines = [PARTNER_COLUMNS.join(';')]
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
    lines.push([get(c.id), get(c.date), get(c.item), FAMILY_YES, side, currency, sum].map(field).join(';'))
    kept++
  }
  return { csv: `${lines.join('\r\n')}\r\n`, read: rows.length, kept, withoutUnallocated }
}

/** The owner's files, by their part in POST /api/import. */
export interface OwnerFiles {
  accounts: Blob
  categories: Blob
  transactions: Blob
  dailyBalance: Blob
  dailyChange: Blob
}

/**
 * The request's body: the owner's files as chosen, and the partner's rows as filtered, under a name of their own. Never
 * the partner's raw file or his daily files: the page takes no file of his but the transactions, which it filters.
 */
export function importForm(owner: OwnerFiles, names: Record<keyof OwnerFiles, string>, partner?: PartnerUpload): FormData {
  const body = new FormData()
  for (const part of ['accounts', 'categories', 'transactions', 'dailyBalance', 'dailyChange'] as const) {
    body.append(part, owner[part], names[part])
  }
  if (partner) {
    body.append('partnerTransactions', new Blob([partner.csv], { type: 'text/csv' }), PARTNER_FILE_NAME)
  }
  return body
}
