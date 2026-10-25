import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ExportSummary } from './api'
import Export, { exportQuery } from './Export'
import { WithMe } from './testMe'

// The export page (D4a, D-241, D-243): the whole period or from–to, the summary of the file, one Download.

function summary(change: Partial<ExportSummary> = {}): ExportSummary {
  return {
    from: null, to: null, first: '2017-11-01', last: '2026-09-29', rows: 6989,
    skipped: [{ kind: 'OPENING_BALANCE', count: 1 }], fileName: 'Transactions_2017-11-01_2026-09-29.csv',
    check: { ok: true, accounts: [] }, ...change,
  }
}

function stubApi(answer: (url: string) => ExportSummary): string[] {
  const asked: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    asked.push(url)
    return new Response(JSON.stringify(answer(url)), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return asked
}

function show() {
  render(<WithMe><MemoryRouter><Export /></MemoryRouter></WithMe>)
}

const download = () => screen.getByText('Download')

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('the export page', () => {
  it('asks for the whole period first and links its file', async () => {
    const asked = stubApi(() => summary())
    show()
    const line = await screen.findByTestId('export-summary')
    expect(line.textContent).toContain('6,989 rows')
    expect(line.textContent).toContain('Left out, as no single row stands for them: opening balances (1).')
    expect(asked).toEqual(['/api/export/summary'])
    expect(download().getAttribute('href')).toBe('/api/export/transactions')
    expect(download().getAttribute('download')).toBe('Transactions_2017-11-01_2026-09-29.csv')
  })

  it('asks for a period from–to, both days included, this month by default', async () => {
    const asked = stubApi((url) => url.includes('from=') ? summary({ rows: 57, first: '2026-09-01', last: '2026-09-29',
      skipped: [], fileName: 'Transactions_2026-09-01_2026-09-30.csv' }) : summary())
    show()
    await screen.findByTestId('export-summary')
    fireEvent.click(screen.getByLabelText('From–to'))
    await waitFor(() => expect(screen.getByTestId('export-summary').textContent).toContain('57 rows'))
    expect(screen.getByTestId('export-summary').textContent).not.toContain('Left out')
    expect(asked.at(-1)).toBe('/api/export/summary?from=2026-09-01&to=2026-09-30')
    expect(download().getAttribute('href')).toBe('/api/export/transactions?from=2026-09-01&to=2026-09-30')
  })

  it('downloads nothing while the period ends before it starts', async () => {
    stubApi(() => summary())
    show()
    await screen.findByTestId('export-summary')
    fireEvent.click(screen.getByLabelText('From–to'))
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
    expect(screen.getByText('Choose both dates, the first not after the last.')).toBeTruthy()
    expect((download() as HTMLButtonElement).disabled).toBe(true)
    expect(screen.queryByTestId('export-summary')).toBeNull()
  })

  it('says when a period has no rows and when its check does not add up', async () => {
    stubApi(() => summary({ rows: 0, first: null, last: null, skipped: [], check: { ok: false, accounts: [
      { account: 'CASH', name: 'Cash', currency: 'EUR', opening: '0', closing: '1', exported: '0', others: '0',
        skipped: '0', ok: false }] } }))
    show()
    expect((await screen.findByTestId('export-summary')).textContent).toBe('No rows in this period.')
    expect(screen.getByRole('alert').textContent).toContain('Cash EUR')
  })
})

describe('exportQuery', () => {
  it('is empty for the whole period, the range for from–to, and null for an incomplete one', () => {
    expect(exportQuery('whole', '', '')).toBe('')
    expect(exportQuery('range', '2026-09-01', '2026-09-30')).toBe('?from=2026-09-01&to=2026-09-30')
    expect(exportQuery('range', '2026-09-30', '2026-09-30')).toBe('?from=2026-09-30&to=2026-09-30')
    expect(exportQuery('range', '2026-10-01', '2026-09-30')).toBeNull()
    expect(exportQuery('range', '', '2026-09-30')).toBeNull()
  })
})
