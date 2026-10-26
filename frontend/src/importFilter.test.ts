import { describe, expect, it } from 'vitest'
import {
  fileFacts, filterPartnerRows, importForm, parseCsv, PARTNER_COLUMNS, PARTNER_FILE_NAME, PARTNER_ITEM_COLUMNS,
  TRANSACTIONS_COLUMNS, unallocatedName,
} from './importFilter'

// Synthetic files in the export's format (D-61, D-235): invented names and amounts, semicolons, a byte order mark.
const OWN = `﻿${TRANSACTIONS_COLUMNS.join(';')}\r\n`
  + '1;02.01.2024;-;50;EUR;;;Кошелёк;Евро-счёт;;;;;;;NULL;CASHFLOW;CASH;EURO_ACCOUNT;ASSET;ASSET;CASH_EUR;EURO_ACCOUNT_EUR;'
  + '50;-50;EUR;0;0;0;0;;NULL\r\n'
  + '2;03.01.2024;Продукты;5;EUR;;;"Свободный остаток";Кошелёк;;;;;;;FOOD;EXPENSE;UNALLOCATED;CASH;LIABILITIES;ASSET;'
  + 'UNALLOCATED_EUR;CASH_EUR;-5;-5;EUR;0;0;0;0;;FOOD\r\n'
const HEADER = 'ID;Date;Exp. Item;Sum;Currency;Counteragent;Comment;Debit;Credit;Family;CUR_DEBIT;SUM_DEBIT;CUR_CREDIT;SUM_CREDIT;LOAN_CA'
const PARTNER = [
  `﻿${HEADER}`,
  // A family expense from his own card, with a shop and a comment that must stay here.
  '101;01.02.2024;Продукты;40.02;EUR;Лавка «Угол»;"secret; comment";свободный остаток;Карта «Восток»;да;;;;;',
  // Not a family row: his personal life.
  '102;02.02.2024;Продукты;12.00;EUR;Кафе;private;Свободный остаток;Карта «Восток»;;;;;;',
  // A family row without an item: a transfer, which stays here too.
  '103;03.02.2024;-;50;EUR;;;Кошелёк;Карта «Восток»;да;;;;;',
  // A family income, received into his account.
  '104;04.02.2024;Пособие;60;EUR;Фонд;note;Карта «Восток»;Свободный остаток;да;;;;;',
  // A refund paid in dollars for a rouble expense: the UNALLOCATED side's currency and amount go.
  '105;05.02.2024;Транспорт;-2.50;USD;;;Свободный остаток;Долларовый счёт;да;RUB;-230.75;;;Друг',
  // A family row on accounts that aren't UNALLOCATED at all.
  '106;06.02.2024;Продукты;5;EUR;;;Кошелёк;Карта «Восток»;да;;;;;',
  '',
].join('\r\n')

describe('the partner’s filter (D-78, D-140)', () => {
  it('reads Excel’s CSV with quotes, semicolons and a byte order mark', () => {
    expect(parseCsv('﻿a;"b;c";"d ""e"""\r\n\r\nf;g;h\n')).toEqual([['a', 'b;c', 'd "e"'], ['f', 'g', 'h']])
  })

  it('finds UNALLOCATED by its code in the owner’s own transactions file (D-260)', () => {
    expect(unallocatedName(OWN)).toBe('Свободный остаток')
    expect(unallocatedName(OWN.split('\r\n').slice(0, 2).join('\r\n'))).toBeNull()
    expect(unallocatedName('Статьи баланса;Актив/пассив;Line\r\nКошелёк;ASSET;CASH\r\n')).toBeNull()
  })

  it('keeps only family rows with an item, and of them only the side on UNALLOCATED', () => {
    const upload = filterPartnerRows(PARTNER, 'Свободный остаток')
    expect(upload.read).toBe(6)
    expect(upload.kept).toBe(4)
    expect(upload.withoutUnallocated).toBe(1)
    const [header, ...rows] = parseCsv(upload.csv)
    expect(header).toEqual([...PARTNER_COLUMNS])
    expect(rows).toEqual([
      ['101', '01.02.2024', 'Продукты', 'да', 'Debit', 'EUR', '40.02'],
      ['104', '04.02.2024', 'Пособие', 'да', 'Credit', 'EUR', '60'],
      ['105', '05.02.2024', 'Транспорт', 'да', 'Debit', 'RUB', '-230.75'],
      ['106', '06.02.2024', 'Продукты', 'да', '', 'EUR', '5'],
    ])
  })

  it('uploads the same rows from the partner’s file in the owner’s layout, its columns taken by name (D-177)', () => {
    // The owner's layout: the same columns among others, one of them before ID.
    const [header, ...rows] = PARTNER.split('\r\n')
    const owners = [
      `\ufeffFAMILY_EXP;${header.replace('\ufeff', '')};CUR_FAMILY;FAMILY_INC`,
      ...rows.map((row) => (row === '' ? '' : `x;${row};EUR;0`)),
    ].join('\r\n')
    expect(filterPartnerRows(owners, 'Свободный остаток')).toEqual(filterPartnerRows(PARTNER, 'Свободный остаток'))
  })

  it('adds each row’s item code and type where his file has D-235’s columns (D-260)', () => {
    const [header, ...rows] = PARTNER.split('\r\n')
    const full = [
      `${header};CODE_ITEM;TYPE`,
      ...rows.map((row) => (row === '' ? '' : `${row};FOOD;EXPENSE`)),
    ].join('\r\n')
    const [head, first] = parseCsv(filterPartnerRows(full, 'Свободный остаток').csv)
    expect(head).toEqual([...PARTNER_COLUMNS, ...PARTNER_ITEM_COLUMNS])
    expect(first).toEqual(['101', '01.02.2024', 'Продукты', 'да', 'Debit', 'EUR', '40.02', 'FOOD', 'EXPENSE'])
  })

  it('never lets a comment, a counterparty, an account or a personal row through', () => {
    const { csv } = filterPartnerRows(PARTNER, 'Свободный остаток')
    for (const secret of ['secret', 'Лавка', 'Кафе', 'private', 'Карта', 'Восток', 'Друг', 'Фонд', 'note', 'Долларовый',
      'Кошелёк', '102', '103']) {
      expect(csv, secret).not.toContain(secret)
    }
  })

  it('refuses a file without the columns it reads', () => {
    expect(() => filterPartnerRows('ID;Date\r\n1;01.02.2024\r\n', 'Свободный остаток')).toThrow(/no column "Exp. Item"/)
  })
})

describe('the request’s payload', () => {
  const own = new Blob([OWN])

  it('holds the user’s one file and the partner’s filtered rows under a name of their own, nothing else', async () => {
    const upload = filterPartnerRows(PARTNER, 'Свободный остаток')
    const body = importForm(own, 'TransactionsDima.csv', upload)
    expect([...body.keys()]).toEqual(['transactions', 'partnerTransactions'])
    expect((body.get('transactions') as File).name).toBe('TransactionsDima.csv')
    const partner = body.get('partnerTransactions') as File
    expect(partner.name).toBe(PARTNER_FILE_NAME)
    const sent = await partner.text()
    expect(sent).toBe(upload.csv)
    expect(parseCsv(sent).slice(1).every((row) => row[3] === 'да' && row[2] !== '-')).toBe(true)
    expect(sent).not.toContain('Comment')
    expect(sent).not.toContain('Counteragent')
    expect(sent).not.toContain('LOAN_CA')
  })

  it('holds no partner part without a family budget', () => {
    expect([...importForm(own, 'transactions.csv').keys()]).toEqual(['transactions'])
  })
})

describe('the file before the check (D-221, D-257)', () => {
  it('counts the rows as the import does, and names the highest ID and the last row date', () => {
    expect(fileFacts(OWN)).toEqual({ rows: 2, highestId: '2', lastDate: '2024-01-03', header: true })
    // The app's IDs aren't Excel IDs; dates compare as dates, not as text.
    const more = `${OWN}app-f9;15.02.2024;-;1\r\n10;31.12.2023;-;1\r\n`
    expect(fileFacts(more)).toEqual({ rows: 4, highestId: '10', lastDate: '2024-02-15', header: true })
    expect(fileFacts('ID;Date\r\n1;01.01.2024\r\n').header).toBe(false)
  })
})
