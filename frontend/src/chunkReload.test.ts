import { afterEach, describe, expect, it, vi } from 'vitest'
import { RELOAD_GUARD_MS, reloadForNewBuild } from './chunkReload'

describe('reloadForNewBuild', () => {
  afterEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('reloads once, then not again within the guard, then again after it', () => {
    const reload = vi.fn()
    const start = 1_800_000_000_000

    expect(reloadForNewBuild(reload, start)).toBe(true)
    expect(reloadForNewBuild(reload, start + 5_000)).toBe(false)
    expect(reloadForNewBuild(reload, start + RELOAD_GUARD_MS - 1)).toBe(false)
    expect(reload).toHaveBeenCalledTimes(1)

    expect(reloadForNewBuild(reload, start + RELOAD_GUARD_MS)).toBe(true)
    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('never reloads without session storage, which guards against a loop', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('denied') })
    const reload = vi.fn()

    expect(reloadForNewBuild(reload)).toBe(false)
    expect(reload).not.toHaveBeenCalled()
  })

  it('reloads when the stored time is garbage or in the future', () => {
    const reload = vi.fn()
    sessionStorage.setItem('reloadedForNewBuildAt', 'soon')
    expect(reloadForNewBuild(reload, 1_000_000)).toBe(true)
    sessionStorage.setItem('reloadedForNewBuildAt', String(9_000_000))
    expect(reloadForNewBuild(reload, 1_000_000)).toBe(true)
    expect(reload).toHaveBeenCalledTimes(2)
  })
})
