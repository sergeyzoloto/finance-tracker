"""The synthetic export of D3b's tests, in the real export's format (D-61): invented names and amounts, and daily
files that follow Excel's own rules, independently of the importer: each row moves its two sides, a family row's
family part (FAMILY_EXP, FAMILY_INC) moves FAMILY_DEBT and UNALLOCATED, each month's lump moves them at the month-end,
and the 1st of each month sweeps UNALLOCATED into RESERVE. The USD lump column is 0 (D-85), while the balances keep
copied USD lumps (D-147). Rows after the cutoff are padding and garbage, never read (D-146).

Usage, from this folder: python3 -I make-fixtures.py .
"""
import sys, os, datetime
from decimal import Decimal as D
from collections import defaultdict

OUT = sys.argv[1]
BOM = '﻿'

ACCOUNTS = [
    ('Кошелёк', 'ASSET', 'CASH'),
    ('Карта «Северная»', 'ASSET', 'NORTH_CARD'),
    ('Евро-счёт', 'ASSET', 'EURO_ACCOUNT'),
    ('Долларовый счёт', 'ASSET', 'USD_ACCOUNT'),
    ('Займы друзьям', 'ASSET', 'LOANS_ASSET'),
    ('Фонд общины', 'ASSET', 'GEMENTE_FUND'),
    ('Долг перед банком', 'LIABILITIES', 'CREDITOR_DEBT'),
    ('Общий семейный бюджет', 'LIABILITIES', 'FAMILY_DEBT'),
    ('Отложено', 'LIABILITIES', 'RESERVE'),
    ('Свободный остаток', 'LIABILITIES', 'UNALLOCATED'),
]
CATEGORIES = [
    ('Оклад', 'INCOME', 'PAYCHECK'),
    ('Пособие', 'INCOME', 'BENEFIT'),
    ('Прочие поступления', 'INCOME', 'OTHERINC'),
    ('Прибыль от курсовой разницы', 'INCOME', 'FX_GAIN'),
    ('Продукты', 'EXPENSE', 'FOOD'),
    ('Транспорт', 'EXPENSE', 'TRANSPORT'),
    ('Подарки', 'EXPENSE', 'GIFTS'),
    ('Комиссия', 'EXPENSE', 'COMMISION'),
    ('-', 'CASHFLOW', 'NULL'),
]
CODE = {n.lower(): c for n, _, c in ACCOUNTS}
TYPE = {c: t for _, t, c in ACCOUNTS}
CAT = {n: c for n, _, c in CATEGORIES}
UA = 'Свободный остаток'
FD = 'Общий семейный бюджет'

# The owner's rows: id, date, item, Sum, Currency, Counteragent, Comment, Debit, Credit, Family, CUR_DEBIT,
# SUM_DEBIT, CUR_CREDIT, SUM_CREDIT, LOAN_CA.
OWNER = [
    (1, '02.01.2024', 'Оклад', '1 000.00 ', 'EUR', 'Работа', '', 'Евро-счёт', UA, '', '', '', '', '', ''),
    (2, '03.01.2024', 'Продукты', '10.01', 'EUR', 'Лавка', 'на неделю', UA, 'Евро-счёт', 'да', '', '', '', '', ''),
    (3, '04.01.2024', 'Продукты', '-4.00', 'EUR', 'Лавка', '', UA, 'Евро-счёт', 'да', '', '', '', '', ''),
    (4, '05.01.2024', 'Прочие поступления', '100', 'EUR', '', '', 'Евро-счёт', UA, 'да', '', '', '', '', ''),
    (5, '06.01.2024', 'Продукты', '3.30', 'EUR', 'Рынок', '', UA, 'Евро-счёт', 'да', 'RUB', '300.12345', '', '', ''),
    (6, '07.01.2024', 'Продукты', '20', 'EUR', 'Магазин', '', UA, 'Долг перед банком', 'да', '', '', '', '', 'Банк'),
    (7, '08.01.2024', '-', '50', 'EUR', '', '', 'Кошелёк', 'Евро-счёт', 'да', '', '', '', '', ''),
    (8, '09.01.2024', 'Продукты', '0', 'EUR', '', '', UA, 'Евро-счёт', 'да', '', '', '', '', ''),
    (9, '10.01.2024', '-', '30', 'EUR', '', '', FD, 'Евро-счёт', '', '', '', '', '', ''),
    (10, '11.01.2024', '-', '15', 'EUR', '', '', 'Кошелёк', FD, '', '', '', '', '', ''),
    (11, '12.01.2024', '-', '40', 'EUR', '', '', 'Займы друзьям', FD, '', '', '', '', '', 'Друг'),
    (12, '14.01.2024', 'Подарки', '500', 'RUB', '', '', UA, FD, '', '', '', '', '', ''),
    (13, '15.01.2024', '-', '200', 'RUB', '', '', FD, UA, '', '', '', '', '', ''),
    (14, '16.01.2024', '-', '11', 'EUR', '', '', FD, FD, '', 'RUB', '1000', '', '', ''),
    (15, '17.01.2024', '-', '9', 'EUR', '', '', FD, FD, '', '', '', 'USD', '10', ''),
    (16, '05.02.2024', '-', '10', 'USD', '', '', FD, 'Долларовый счёт', '', '', '', '', '', ''),
    (17, '18.01.2024', '-', '-20', 'EUR', '', '', 'Кошелёк', 'Евро-счёт', '', '', '', '', '', ''),
    (18, '19.01.2024', 'Прибыль от курсовой разницы', '0', 'RUB', '', '', 'Карта «Северная»', UA, '', '', '', '', '', ''),
    (19, '20.01.2024', 'Продукты', '123.456789', 'RUB', 'Лавка', '', UA, 'Карта «Северная»', '', '', '', '', '', ''),
    (20, '21.01.2024', '-', '50', 'EUR', '', '', 'Займы друзьям', 'Евро-счёт', '', '', '', '', '', 'Друг'),
    (21, '22.01.2024', 'Комиссия', '1.5', 'EUR', 'Банк', '', UA, 'Евро-счёт', '', '', '', '', '', ''),
    (22, '23.01.2024', '-', '100', 'EUR', '', '', 'Фонд общины', 'Евро-счёт', '', '', '', '', '', ''),
    (23, '03.02.2024', 'Пособие', '30', 'EUR', '', '', FD, UA, '', '', '', '', '', ''),
    (24, '24.01.2024', '-', '10', 'EUR', '', '', 'Евро-счёт', 'Евро-счёт', '', 'RUB', '900', '', '', ''),
    (25, '05.03.2024', 'Продукты', '7.00', 'EUR', 'Лавка', '', UA, 'Кошелёк', '', '', '', '', '', ''),
]
# The partner's rows, with his own card.
PCARD = 'Карта «Восток»'
PARTNER = [
    (101, '01.02.2024', 'Продукты', '40.02', 'EUR', 'Лавка «Угол»', 'his note', UA, PCARD, 'да', '', '', '', '', ''),
    (102, '02.02.2024', 'Продукты', '-10.00', 'EUR', 'Лавка «Угол»', '', UA, PCARD, 'да', '', '', '', '', ''),
    (103, '03.02.2024', 'Пособие', '60', 'EUR', 'Фонд', '', PCARD, UA, 'да', '', '', '', '', ''),
    (104, '04.02.2024', 'Транспорт', '500.5', 'RUB', '', '', UA, PCARD, 'да', '', '', '', '', ''),
    (105, '05.02.2024', 'Продукты', ' - ', 'EUR', '', '', UA, PCARD, 'да', '', '', '', '', ''),
    (106, '06.02.2024', 'Продукты', '12', 'EUR', 'Кафе', 'private', UA, PCARD, '', '', '', '', '', ''),
    (107, '07.02.2024', '-', '50', 'EUR', '', '', 'Кошелёк', PCARD, 'да', '', '', '', '', ''),
    (108, '08.02.2024', 'Транспорт', '2.5', 'USD', '', '', UA, PCARD, 'да', 'RUB', '230.75', '', '', 'Друг'),
    (109, '08.03.2024', 'Продукты', '8.00', 'EUR', '', '', UA, PCARD, 'да', '', '', '', '', ''),
]
CODE[PCARD.lower()] = 'PARTNER_CARD'
TYPE['PARTNER_CARD'] = 'ASSET'
HEADER = ['ID', 'Date', 'Exp. Item', 'Sum', 'Currency', 'Counteragent', 'Comment', 'Debit', 'Credit', 'Family',
          'CUR_DEBIT', 'SUM_DEBIT', 'CUR_CREDIT', 'SUM_CREDIT', 'LOAN_CA']


def num(s):
    s = s.replace(' ', '').replace(' ', '').strip()
    return D(0) if s in ('', '-') else D(s)


def date(s):
    return datetime.datetime.strptime(s, '%d.%m.%Y').date()


def csv(path, header, rows):
    def f(v):
        v = str(v)
        return '"' + v.replace('"', '""') + '"' if any(c in v for c in ';"\r\n') else v
    with open(os.path.join(OUT, path), 'w', encoding='utf-8', newline='') as fh:
        fh.write(BOM + ';'.join(header) + '\r\n')
        for r in rows:
            fh.write(';'.join(f(v) for v in r) + '\r\n')


def sides(r):
    _, d, item, s, cur, _, _, deb, cre, fam, cd, sd, cc, sc, _ = r
    S = num(s)
    return (CODE[deb.lower()], cd or cur, num(sd) if cd else S), (CODE[cre.lower()], cc or cur, num(sc) if cc else S)


# Excel's computed family columns of the owner's rows.
owner_rows = []
for r in OWNER:
    (dc, dcur, dsum), (cc, ccur, csum) = sides(r)
    fam = r[9] == 'да' and r[2] != '-'
    cur_family, fe, fi = '', D(0), D(0)
    if fam:
        if dc == 'UNALLOCATED':
            cur_family, fe = dcur, -dsum / 2
        else:
            cur_family, fi = ccur, csum / 2
    owner_rows.append(list(r) + [cur_family or r[4], fe, fi, CAT.get(r[2], 'NULL')])

# Lumps: before the partner's ledger (January), with odd decimals; from February, half his family rows but BENEFIT,
# CASHBACK and OTHERINC; USD a copy of EUR (the workbook's error), which D-147 makes harmless.
LUMPS = {(date('31.01.2024'), 'RUB'): D('333.335'), (date('31.01.2024'), 'EUR'): D('12.345')}
feb = date('29.02.2024')
for r in PARTNER:
    if r[9] != 'да' or r[2] == '-':
        continue
    (dc, dcur, dsum), (cc, ccur, csum) = sides(r)
    if CAT[r[2]] in ('BENEFIT', 'CASHBACK', 'OTHERINC') or date(r[1]) >= date('01.03.2024'):
        continue
    if dc == 'UNALLOCATED':
        LUMPS[(feb, dcur)] = LUMPS.get((feb, dcur), D(0)) + dsum / 2
    else:
        LUMPS[(feb, ccur)] = LUMPS.get((feb, ccur), D(0)) - csum / 2
for d in (date('31.01.2024'), feb):
    LUMPS[(d, 'USD')] = LUMPS.get((d, 'EUR'), D(0))

COLUMNS = ['CASH_EUR', 'NORTH_CARD_RUB', 'EURO_ACCOUNT_EUR', 'EURO_ACCOUNT_RUB', 'USD_ACCOUNT_USD', 'LOANS_ASSET_EUR',
           'GEMENTE_FUND_EUR', 'CREDITOR_DEBT_EUR', 'FAMILY_DEBT_RUB', 'FAMILY_DEBT_EUR', 'FAMILY_DEBT_USD',
           'UNALLOCATED_RUB', 'UNALLOCATED_EUR', 'UNALLOCATED_USD', 'RESERVE_RUB', 'RESERVE_EUR', 'RESERVE_USD']
OPENING = {'CASH_EUR': D(100), 'EURO_ACCOUNT_EUR': D(1000), 'NORTH_CARD_RUB': D(5000), 'USD_ACCOUNT_USD': D(50),
           'RESERVE_EUR': D(1100), 'RESERVE_RUB': D(5000), 'RESERVE_USD': D(50)}
start, end, cutoff = date('31.12.2023'), date('31.12.2024'), date('08.03.2024')
changes = defaultdict(lambda: defaultdict(D))


def move(day, code, cur, amount):
    changes[day][f'{code}_{cur}'] += amount


for r in owner_rows:
    day = date(r[1])
    (dc, dcur, dsum), (cc, ccur, csum) = sides(tuple(r[:15]))
    move(day, dc, dcur, dsum if TYPE[dc] == 'ASSET' else -dsum)
    move(day, cc, ccur, -csum if TYPE[cc] == 'ASSET' else csum)
    part = r[16] + r[17]
    if part:
        move(day, 'FAMILY_DEBT', r[15], part)
        move(day, 'UNALLOCATED', r[15], -part)
current = defaultdict(D, OPENING)
day = start
rows_balance, rows_change = [], []
while day <= end:
    if day > start:
        if day.day == 1:
            for cur in ('RUB', 'EUR', 'USD'):  # the 1st of the month's sweep
                x = current[f'UNALLOCATED_{cur}']
                move(day, 'UNALLOCATED', cur, -x)
                move(day, 'RESERVE', cur, x)
        for (d, cur), pl in LUMPS.items():
            if d == day:
                move(day, 'FAMILY_DEBT', cur, pl)
                move(day, 'UNALLOCATED', cur, -pl)
        for c, v in changes[day].items():
            current[c] += v
    cells = [current[c] for c in COLUMNS]
    if day > cutoff:  # padding, as the workbook's: never read (D-146)
        cells = [D(0)] * len(COLUMNS)
    rows_balance.append((day, cells))
    # The USD lump column is 0, as in the export of 2026-10-12 (D-85), while the balances keep the copied lumps (D-147).
    rows_change.append((day, [changes[day][c] for c in COLUMNS] + [LUMPS.get((day, c), D(0)) for c in ('RUB', 'EUR')] + [D(0)]))
    day += datetime.timedelta(days=1)


def cell(v, n):
    if n % 7 == 3 and v == 0:  # Excel's floating-point residues, read as the decimals they are
        return '-1.42109E-14'
    s = format(v.normalize(), 'f') if v != 0 else '0'
    return s + (' ' if n % 5 == 0 else '')


bal = [[d.strftime('%d.%m.%Y')] + [cell(v, i + j) for j, v in enumerate(cells)] for i, (d, cells) in enumerate(rows_balance)]
bal[-1][1] = '#VALUE!'  # after the cutoff: never read (D-146)
csv('daily-balance.csv', ['Date'] + COLUMNS, bal)
chg_cols = COLUMNS + ['PL_family_Sam_RUB', 'PL_family_Sam_EUR', 'PL_family_Sam_USD']
csv('daily-change.csv', ['Date'] + chg_cols,
    [[d.strftime('%d.%m.%Y')] + [cell(v, i + j) for j, v in enumerate(cells)] for i, (d, cells) in enumerate(rows_change)])
csv('accounts.csv', ['Статьи баланса', 'Актив/пассив', 'Line'], ACCOUNTS)
csv('categories.csv', ['Статьи движения средств', 'Доходы/расходы', 'Line'], CATEGORIES)
csv('transactions.csv', HEADER + ['CUR_FAMILY', 'FAMILY_EXP', 'FAMILY_INC', 'CODE_ITEM', 'TYPE'],
    [r[:15] + [r[15], format(r[16], 'f'), format(r[17], 'f'), r[18], 'x'] for r in owner_rows])
csv('partner-transactions.csv', HEADER, PARTNER)
