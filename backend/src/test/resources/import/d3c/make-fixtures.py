"""The synthetic export of D3c's tests (D-174 to D-191): an owner and a partner who both keep the owner's layout (D-177),
invented names and amounts, each a transactions file with D-235's 32 columns (D4b). The 17 computed columns follow
Excel's own rules (D-237), written here independently of the importer, as ../make-fixtures.py writes them.

The partner joins on 1 February 2024 (D-174). His settlement rows mirror the owner's, by each of D-178's tiers; his
conversion is written in EUR where the owner's is written in USD; row 105 is his benefit row (D-179). Rows 111 (a
settlement without a counterpart) and 112 (an expense straight from FAMILY_DEBT, D-179) are refused, and the tests leave
them out.

Usage, from this folder: python3 -I make-fixtures.py .
"""
import sys, os
from decimal import Decimal as D

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
OLD = ['ID', 'Date', 'Exp. Item', 'Sum', 'Currency', 'Counteragent', 'Comment', 'Debit', 'Credit', 'Family',
       'CUR_DEBIT', 'SUM_DEBIT', 'CUR_CREDIT', 'SUM_CREDIT', 'LOAN_CA']
HEADER = OLD + ['CODE_ITEM', 'TYPE', 'CODE_DEBIT', 'CODE_CREDIT', 'TYPE_DEBIT', 'TYPE_CREDIT', 'COUNT_DEBIT',
                'COUNT_CREDIT', 'DEBIT_CHANGE', 'CREDIT_CHANGE', 'CUR_FAMILY', 'FAMILY_EXP', 'FAMILY_INC', 'CHECK_CORR',
                'LOAN_SUM', 'LOAN_CUR', 'EXP_ITEM_CODE']
CATTYPE = {n: t for n, t, _ in CATEGORIES}
LOANS = ('LOANS_ASSET', 'CREDITOR_DEBT')


def num(s):
    s = s.replace(' ', '').replace('\u00a0', '').strip()
    return D(0) if s in ('', '-') else D(s)


def text(v):
    return '0' if v == 0 else format(v.normalize(), 'f')


def write(path, header, rows):
    def f(v):
        v = str(v)
        return '"' + v.replace('"', '""') + '"' if any(c in v for c in ';"\r\n') else v
    with open(os.path.join(OUT, path), 'w', encoding='utf-8', newline='') as fh:
        fh.write(BOM + ';'.join(header) + '\r\n')
        for r in rows:
            fh.write(';'.join(f(v) for v in r) + '\r\n')


def computed(r, accounts):
    """The row with Excel's 17 computed columns after its 15 typed ones (D-237)."""
    code = {n.lower(): c for n, _, c in accounts}
    kind = {c: t for _, t, c in accounts}
    _, _, item, s, cur, _, _, deb, cre, fam, cd, sd, cc, sc, _ = r
    dcode, ccode = code[deb.lower()], code[cre.lower()]
    dtype, ctype = kind[dcode], kind[ccode]
    dcur, ccur = cd or cur, cc or cur
    dsum, csum = (num(sd) if cd else num(s)), (num(sc) if cc else num(s))
    family = fam == 'да' and item != '-'  # a family row without an item has no family part
    fe = -dsum / 2 if family and dcode == 'UNALLOCATED' else D(0)
    fi = csum / 2 if family and ccode == 'UNALLOCATED' else D(0)
    dchange = (dsum if dtype == 'ASSET' else -dsum) - fe
    cchange = (-csum if ctype == 'ASSET' else csum) - fi
    assets, liabilities = D(0), fe + fi
    if dtype == 'ASSET':
        assets += dchange
    else:
        liabilities += dchange
    if ctype == 'ASSET':
        assets += cchange
    else:
        liabilities += cchange
    corr = assets - liabilities
    if dtype == 'ASSET' and ctype == 'LIABILITIES':
        corr = -corr
    loan = (-dsum if dcode in LOANS else D(0)) + (csum if ccode in LOANS else D(0))
    loan_cur = dcur if dcode in LOANS else ccur if ccode in LOANS else ''
    code_item = CAT.get(item, 'NULL')
    return list(r) + [code_item, CATTYPE.get(item, 'CASHFLOW'), dcode, ccode, dtype, ctype, f'{dcode}_{dcur}',
                      f'{ccode}_{ccur}', text(dchange), text(cchange), dcur, text(fe), text(fi), text(corr),
                      text(loan), loan_cur, code_item]


write('owner-transactions.csv', HEADER, [computed(r, OWNER_ACCOUNTS) for r in OWNER])
write('partner-transactions.csv', HEADER, [computed(r, PARTNER_ACCOUNTS) for r in PARTNER])
# The partner's transactions file as export-2026-10-13 had it: 15 columns, no computed ones (D-177's other layout),
# which the page's filter reads as well as the full one (D-151).
write('partner-transactions-old.csv', OLD, PARTNER)
