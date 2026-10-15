"""The synthetic export of D3c's tests (D-174 to D-191): an owner and a partner who both keep the owner's layout (D-177),
invented names and amounts. Their daily files follow Excel's own rules, independently of the importer: each row moves
its two sides; a family row's family part (FAMILY_EXP, FAMILY_INC, exact halves) moves FAMILY_DEBT and UNALLOCATED; each
month-end's lump of the other member's family rows (but BENEFIT, CASHBACK and OTHERINC incomes, which the other's own
rows on FAMILY_DEBT carry, D-84 and D-179) moves them too; and the 1st of each month sweeps UNALLOCATED into RESERVE.

The partner's Excel starts on 31 January 2024 (his join date is 1 February, D-174), with his family debt then as his
first balance. His settlement rows mirror the owner's, by each of D-178's tiers; his conversion is written in EUR
where the owner's is written in USD; row 105 is his benefit row (D-179). Rows 111 (a settlement without a counterpart)
and 112 (an expense straight from FAMILY_DEBT, D-179) are refused: his daily files leave them out, so that his file
without them reconciles.

Usage, from this folder: python3 -I make-fixtures.py .
"""
import sys, os, datetime
from decimal import Decimal as D
from collections import defaultdict

OUT = sys.argv[1]
BOM = '﻿'

CATEGORIES = [
    ('Оклад', 'INCOME', 'PAYCHECK'),
    ('Пособие', 'INCOME', 'BENEFIT'),
    ('Прочие поступления', 'INCOME', 'OTHERINC'),
    ('Продукты', 'EXPENSE', 'FOOD'),
    ('Транспорт', 'EXPENSE', 'TRANSPORT'),
    ('-', 'CASHFLOW', 'NULL'),
]
CAT = {n: c for n, _, c in CATEGORIES}
CARRIED = ('BENEFIT', 'CASHBACK', 'OTHERINC')
UA = 'Свободный остаток'
FD = 'Общий семейный бюджет'
OWNER_ACCOUNTS = [
    ('Кошелёк', 'ASSET', 'CASH'),
    ('Евро-счёт', 'ASSET', 'EURO_ACCOUNT'),
    ('Долларовый счёт', 'ASSET', 'USD_ACCOUNT'),
    (FD, 'LIABILITIES', 'FAMILY_DEBT'),
    ('Отложено', 'LIABILITIES', 'RESERVE'),
    (UA, 'LIABILITIES', 'UNALLOCATED'),
]
PCARD = 'Карта «Восток»'
PARTNER_ACCOUNTS = [
    ('Наличные', 'ASSET', 'CASH'),
    (PCARD, 'ASSET', 'PARTNER_CARD'),
    (FD, 'LIABILITIES', 'FAMILY_DEBT'),
    ('Отложено', 'LIABILITIES', 'RESERVE'),
    (UA, 'LIABILITIES', 'UNALLOCATED'),
]

# id, date, item, Sum, Currency, Counteragent, Comment, Debit, Credit, Family, CUR_DEBIT, SUM_DEBIT, CUR_CREDIT,
# SUM_CREDIT, LOAN_CA
OWNER = [
    (1, '02.01.2024', 'Оклад', '1000', 'EUR', 'Работа', '', 'Евро-счёт', UA, '', '', '', '', '', ''),
    (2, '10.01.2024', 'Продукты', '20.01', 'EUR', 'Лавка', '', UA, 'Евро-счёт', 'да', '', '', '', '', ''),
    (3, '05.02.2024', 'Продукты', '30.01', 'EUR', 'Рынок', '', UA, 'Евро-счёт', 'да', '', '', '', '', ''),
    (4, '07.02.2024', 'Прочие поступления', '100', 'EUR', '', '', 'Евро-счёт', UA, 'да', '', '', '', '', ''),
    (5, '10.02.2024', '-', '40', 'EUR', '', '', FD, 'Евро-счёт', '', '', '', '', '', ''),
    (6, '12.02.2024', '-', '25', 'EUR', '', '', FD, 'Евро-счёт', '', '', '', '', '', ''),
    (7, '12.02.2024', '-', '15', 'EUR', '', '', FD, 'Евро-счёт', '', '', '', '', '', ''),
    (8, '20.02.2024', '-', '33', 'EUR', '', '', 'Кошелёк', FD, '', '', '', '', '', ''),
    (9, '28.02.2024', '-', '12', 'EUR', '', '', FD, 'Евро-счёт', '', '', '', '', '', ''),
    (10, '15.02.2024', '-', '10', 'USD', '', '', FD, FD, '', 'EUR', '9', '', '', ''),
    (12, '03.02.2024', 'Пособие', '30', 'EUR', '', '', FD, UA, '', '', '', '', '', ''),
    (13, '25.02.2024', '-', '10', 'USD', '', '', FD, 'Долларовый счёт', '', '', '', '', '', ''),
    (14, '09.02.2024', '-', '15', 'EUR', '', '', 'Кошелёк', FD, '', '', '', '', '', ''),
    (15, '10.03.2024', 'Продукты', '7', 'EUR', 'Лавка', '', UA, 'Кошелёк', '', '', '', '', '', ''),
]
PARTNER = [
    (101, '01.02.2024', 'Продукты', '40.02', 'EUR', 'Лавка «Угол»', 'his note', UA, PCARD, 'да', '', '', '', '', ''),
    (102, '03.02.2024', 'Пособие', '60', 'EUR', 'Фонд', '', PCARD, UA, 'да', '', '', '', '', ''),
    (103, '04.02.2024', 'Транспорт', '500.5', 'RUB', '', '', UA, PCARD, 'да', '', '', 'EUR', '5.50', ''),
    (104, '06.02.2024', 'Продукты', '12', 'EUR', 'Кафе', 'private', UA, PCARD, '', '', '', '', '', ''),
    (105, '07.02.2024', 'Прочие поступления', '50', 'EUR', '', '', FD, UA, '', '', '', '', '', ''),
    (106, '10.02.2024', '-', '40', 'EUR', '', '', PCARD, FD, '', '', '', '', '', ''),
    (107, '12.02.2024', '-', '40', 'EUR', '', '', 'Наличные', FD, '', '', '', '', '', ''),
    (108, '23.02.2024', '-', '33', 'EUR', '', '', FD, PCARD, '', '', '', '', '', ''),
    (109, '02.03.2024', '-', '12', 'EUR', '', '', PCARD, FD, '', '', '', '', '', ''),
    (110, '15.02.2024', '-', '9', 'EUR', '', '', FD, FD, '', 'USD', '10', '', '', ''),
    (111, '25.02.2024', '-', '77', 'EUR', '', '', FD, PCARD, '', '', '', '', '', ''),
    (112, '26.02.2024', 'Продукты', '5', 'EUR', '', '', UA, FD, '', '', '', '', '', ''),
    (113, '08.03.2024', 'Продукты', '8.004', 'EUR', '', '', UA, PCARD, 'да', '', '', '', '', ''),
    (114, '05.02.2024', '-', '20', 'EUR', '', '', 'Наличные', PCARD, 'да', '', '', '', '', ''),
    (115, '25.02.2024', '-', '10', 'USD', '', '', 'Наличные', FD, '', '', '', '', '', ''),
    (116, '09.02.2024', '-', '15', 'EUR', '', '', FD, PCARD, 'да', '', '', '', '', ''),
]
REFUSED = {111, 112}
HEADER = ['ID', 'Date', 'Exp. Item', 'Sum', 'Currency', 'Counteragent', 'Comment', 'Debit', 'Credit', 'Family',
          'CUR_DEBIT', 'SUM_DEBIT', 'CUR_CREDIT', 'SUM_CREDIT', 'LOAN_CA']
FULL = HEADER + ['CUR_FAMILY', 'FAMILY_EXP', 'FAMILY_INC', 'CODE_ITEM', 'TYPE']


def num(s):
    s = s.replace(' ', '').replace(' ', '').strip()
    return D(0) if s in ('', '-') else D(s)


def date(s):
    return datetime.datetime.strptime(s, '%d.%m.%Y').date()


def write(path, header, rows):
    def f(v):
        v = str(v)
        return '"' + v.replace('"', '""') + '"' if any(c in v for c in ';"\r\n') else v
    with open(os.path.join(OUT, path), 'w', encoding='utf-8', newline='') as fh:
        fh.write(BOM + ';'.join(header) + '\r\n')
        for r in rows:
            fh.write(';'.join(f(v) for v in r) + '\r\n')


def ledger(accounts):
    code = {n.lower(): c for n, _, c in accounts}
    kind = {c: t for _, t, c in accounts}
    return code, kind


def sides(r, code):
    _, d, item, s, cur, _, _, deb, cre, fam, cd, sd, cc, sc, _ = r
    S = num(s)
    return (code[deb.lower()], cd or cur, num(sd) if cd else S), (code[cre.lower()], cc or cur, num(sc) if cc else S)


def full(rows, code):
    """Each row with Excel's computed family columns: CUR_FAMILY, FAMILY_EXP, FAMILY_INC (exact halves), CODE_ITEM."""
    out = []
    for r in rows:
        (dc, dcur, dsum), (cc, ccur, csum) = sides(r, code)
        cur_family, fe, fi = r[4], D(0), D(0)
        if r[9] == 'да' and r[2] != '-':
            if dc == 'UNALLOCATED':
                cur_family, fe = dcur, -dsum / 2
            elif cc == 'UNALLOCATED':
                cur_family, fi = ccur, csum / 2
        out.append(list(r) + [cur_family, fe, fi, CAT.get(r[2], 'NULL')])
    return out


def lumps(rows, code):
    """The month-end lumps of these family rows, as the other member's Excel posts them: half of each, BENEFIT,
    CASHBACK and OTHERINC incomes left out."""
    out = defaultdict(D)
    for r in rows:
        if r[9] != 'да' or r[2] == '-' or CAT[r[2]] in CARRIED:
            continue
        (dc, dcur, dsum), (cc, ccur, csum) = sides(r, code)
        d = date(r[1])
        month_end = (d.replace(day=28) + datetime.timedelta(days=4)).replace(day=1) - datetime.timedelta(days=1)
        if dc == 'UNALLOCATED':
            out[(month_end, dcur)] += dsum / 2
        elif cc == 'UNALLOCATED':
            out[(month_end, ccur)] -= csum / 2
    return out


def daily(rows, code, kind, opening, pl, start, end, cutoff, columns):
    changes = defaultdict(lambda: defaultdict(D))

    def move(day, c, cur, amount):
        changes[day][f'{c}_{cur}'] += amount

    for r in rows:
        day = date(r[1])
        (dc, dcur, dsum), (cc, ccur, csum) = sides(tuple(r[:15]), code)
        move(day, dc, dcur, dsum if kind[dc] == 'ASSET' else -dsum)
        move(day, cc, ccur, -csum if kind[cc] == 'ASSET' else csum)
        part = r[16] + r[17]
        if part:
            move(day, 'FAMILY_DEBT', r[15], part)
            move(day, 'UNALLOCATED', r[15], -part)
    current = defaultdict(D, opening)
    balance, change = [], []
    day = start
    while day <= end:
        if day > start:
            if day.day == 1:
                for cur in ('RUB', 'EUR', 'USD'):
                    x = current[f'UNALLOCATED_{cur}']
                    move(day, 'UNALLOCATED', cur, -x)
                    move(day, 'RESERVE', cur, x)
            for (d, cur), amount in pl.items():
                if d == day:
                    move(day, 'FAMILY_DEBT', cur, amount)
                    move(day, 'UNALLOCATED', cur, -amount)
            for c, v in changes[day].items():
                current[c] += v
        cells = [current[c] for c in columns]
        if day > cutoff:
            cells = [D(0)] * len(columns)
        balance.append((day, cells))
        change.append((day, [changes[day][c] for c in columns] + [pl.get((day, c), D(0)) for c in ('RUB', 'EUR', 'USD')]))
        day += datetime.timedelta(days=1)
    return balance, change


def cell(v):
    return format(v.normalize(), 'f') if v != 0 else '0'


def files(prefix, accounts, rows, refused, other_rows, other_code, opening, start, columns, lump_name, cutoff):
    code, kind = ledger(accounts)
    rows_full = full(rows, code)
    kept = [r for r in rows_full if r[0] not in refused]
    balance, change = daily(kept, code, kind, opening, lumps(other_rows, other_code), start, date('31.12.2024'),
                            cutoff, columns)
    write(f'{prefix}-accounts.csv', ['Статьи баланса', 'Актив/пассив', 'Line'], accounts)
    write(f'{prefix}-transactions.csv', FULL,
          [r[:15] + [r[15], format(r[16], 'f'), format(r[17], 'f'), r[18], 'x'] for r in rows_full])
    write(f'{prefix}-daily-balance.csv', ['Date'] + columns,
          [[d.strftime('%d.%m.%Y')] + [cell(v) for v in cells] for d, cells in balance])
    write(f'{prefix}-daily-change.csv', ['Date'] + columns + [f'PL_family_{lump_name}_{c}' for c in ('RUB', 'EUR', 'USD')],
          [[d.strftime('%d.%m.%Y')] + [cell(v) for v in cells] for d, cells in change])
    return code


owner_code, _ = ledger(OWNER_ACCOUNTS)
partner_code, _ = ledger(PARTNER_ACCOUNTS)
COMMON = ['FAMILY_DEBT_RUB', 'FAMILY_DEBT_EUR', 'FAMILY_DEBT_USD', 'UNALLOCATED_RUB', 'UNALLOCATED_EUR',
          'UNALLOCATED_USD', 'RESERVE_RUB', 'RESERVE_EUR', 'RESERVE_USD']
files('owner', OWNER_ACCOUNTS, OWNER, set(), PARTNER, partner_code,
      {'CASH_EUR': D(100), 'EURO_ACCOUNT_EUR': D(1000), 'USD_ACCOUNT_USD': D(50), 'RESERVE_EUR': D(1100),
       'RESERVE_USD': D(50)},
      date('31.12.2023'), ['CASH_EUR', 'EURO_ACCOUNT_EUR', 'USD_ACCOUNT_USD'] + COMMON, 'Sam', date('10.03.2024'))
# His first balances: his family debt is his half of the owner's January family row (20.01 / 2), exact.
files('partner', PARTNER_ACCOUNTS, PARTNER, REFUSED, OWNER, owner_code,
      {'CASH_EUR': D(100), 'PARTNER_CARD_EUR': D(500), 'FAMILY_DEBT_EUR': D('10.005'), 'RESERVE_EUR': D('589.995')},
      date('31.01.2024'), ['CASH_EUR', 'CASH_USD', 'PARTNER_CARD_EUR'] + COMMON, 'Alex', date('08.03.2024'))
write('categories.csv', ['Статьи движения средств', 'Доходы/расходы', 'Line'], CATEGORIES)
# The partner's transactions file as export-2026-10-13 had it: 15 columns, no family columns (D-177's other layout).
write('partner-transactions-old.csv', HEADER, PARTNER)
