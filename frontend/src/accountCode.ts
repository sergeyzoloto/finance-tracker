/**
 * An account's code, made from its name (the code is never typed): upper case, every run of other characters one "_",
 * none at either end; "ACCOUNT" where nothing is left or the first character is not a letter; at most 40 characters,
 * then "_2", "_3", … until it is not one of the codes the user has already (the backend allows 50 characters).
 */
export function accountCode(name: string, existing: string[]): string {
  const cleaned = name.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '')
  const base = (/^[A-Z]/.test(cleaned) ? cleaned : 'ACCOUNT').slice(0, 40)
  const taken = new Set(existing)
  if (!taken.has(base)) return base
  for (let n = 2; ; n++) {
    const code = `${base}_${n}`
    if (!taken.has(code)) return code
  }
}
