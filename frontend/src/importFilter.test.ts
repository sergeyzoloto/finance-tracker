import { describe, expect, it } from 'vitest'
import { filterPartnerRows, importForm, parseCsv, PARTNER_COLUMNS, PARTNER_FILE_NAME, unallocatedName } from './importFilter'

// Synthetic files in the export's format (D-61): invented names and amounts, semicolons, a byte order mark.
const ACCOUNTS = '﻿Статьи баланса;Актив/пассив;Line\r\nКошелёк;ASSET;CASH\r\n"Свободный остаток";LIABILITIES;UNALLOCATED\r\n'
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

  it('finds UNALLOCATED by its code in the owner’s accounts file', () => {
    expect(unallocatedName(ACCOUNTS)).toBe('Свободный остаток')
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
  const owner = {
    accounts: new Blob([ACCOUNTS]), categories: new Blob(['c']), transactions: new Blob(['t']),
    dailyBalance: new Blob(['b']), dailyChange: new Blob(['d']),
  }
  const names = {
    accounts: 'accounts.csv', categories: 'categories.csv', transactions: 'transactions.csv',
    dailyBalance: 'balance.csv', dailyChange: 'change.csv',
  }

  it('holds the owner’s five files and the partner’s filtered rows under a name of their own, nothing else', async () => {
    const upload = filterPartnerRows(PARTNER, 'Свободный остаток')
    const body = importForm(owner, names, upload)
    expect([...body.keys()]).toEqual(['accounts', 'categories', 'transactions', 'dailyBalance', 'dailyChange',
      'partnerTransactions'])
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
    expect([...importForm(owner, names).keys()]).toEqual(['accounts', 'categories', 'transactions', 'dailyBalance',
      'dailyChange'])
  })
})
