"""The synthetic export of the importer's tests: the owner's transactions file and the partner's, in the real export's
format with D-235's 32 columns (D4b), invented names and amounts. The 17 computed columns follow Excel's own rules
(D-237), written here independently of the importer: the codes and types of each side and of the item, each side's
change of its account's displayed balance less a family row's family part (FAMILY_EXP, FAMILY_INC: minus half of the
debit side on UNALLOCATED, half of the credit side on UNALLOCATED), CHECK_CORR, LOAN_SUM and LOAN_CUR. The codes
COMMISION and GEMENTE_FUND keep the workbook's typos (D-246).

Usage, from this folder: python3 -I make-fixtures.py .
"""
import sys, os
from decimal import Decimal as D

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
          'CUR_DEBIT', 'SUM_DEBIT', 'CUR_CREDIT', 'SUM_CREDIT', 'LOAN_CA', 'CODE_ITEM', 'TYPE', 'CODE_DEBIT',
          'CODE_CREDIT', 'TYPE_DEBIT', 'TYPE_CREDIT', 'COUNT_DEBIT', 'COUNT_CREDIT', 'DEBIT_CHANGE', 'CREDIT_CHANGE',
          'CUR_FAMILY', 'FAMILY_EXP', 'FAMILY_INC', 'CHECK_CORR', 'LOAN_SUM', 'LOAN_CUR', 'EXP_ITEM_CODE']
CATTYPE = {n: t for n, t, _ in CATEGORIES}
LOANS = ('LOANS_ASSET', 'CREDITOR_DEBT')


def num(s):
    s = s.replace(' ', '').replace('\u00a0', '').strip()
    return D(0) if s in ('', '-') else D(s)


def text(v):
    return '0' if v == 0 else format(v.normalize(), 'f')


def csv(path, rows):
    def f(v):
        v = str(v)
        return '"' + v.replace('"', '""') + '"' if any(c in v for c in ';"\r\n') else v
    with open(os.path.join(OUT, path), 'w', encoding='utf-8', newline='') as fh:
        fh.write(BOM + ';'.join(HEADER) + '\r\n')
        for r in rows:
            fh.write(';'.join(f(v) for v in r) + '\r\n')


def computed(r):
    """The row with Excel's 17 computed columns after its 15 typed ones (D-237)."""
    _, _, item, s, cur, _, _, deb, cre, fam, cd, sd, cc, sc, _ = r
    dcode, ccode = CODE[deb.lower()], CODE[cre.lower()]
    dtype, ctype = TYPE[dcode], TYPE[ccode]
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


csv('transactions.csv', [computed(r) for r in OWNER])
csv('partner-transactions.csv', [computed(r) for r in PARTNER])
