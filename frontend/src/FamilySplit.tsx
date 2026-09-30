import type { FamilyLedger } from './api'
import { basisPointsToPercent } from './basisPoints'
import { percentsOf, splitRows, type SplitContext, type SplitForm, type SplitMode, type SplitPreview } from './expenseForm'
import { shareText } from './family'
import { fromMinor } from './minorUnits'
import { formatMoney } from './money'
import { basisPointsOf } from './shareSplit'

/** The rule's name as the choice shows it: "equal shares", or "Anna 60.00 %, Sam 40.00 %". */
function ruleLabel(ledger: FamilyLedger, context: SplitContext) {
  if (ledger.splitRule === 'EQUAL') return 'equal shares'
  return splitRows({ mode: 'RULE', percents: {}, amounts: {}, member: '' }, context)
    .map((m) => `${m.displayName} ${shareText(m)}`).join(', ')
}

const MODES: { mode: SplitMode; label: (rule: string) => string }[] = [
  { mode: 'KEEP', label: () => 'Equal shares, as it is split now' },
  { mode: 'RULE', label: (rule) => `The budget’s rule (${rule})` },
  { mode: 'PERCENT', label: () => 'Percentages' },
  { mode: 'AMOUNT', label: () => 'Amounts' },
  { mode: 'ONE_MEMBER', label: () => 'Entirely on one member' },
]

/**
 * How a family expense or income is split (D-12): the budget's rule, percentages, amounts, or one member, with every
 * member's resulting amount and the total before saving, as the server would split it. A stored record split equally
 * also offers its equal shares as they are, among its own members (KEEP), which the preview follows. The server's
 * objections come by member (`byMember`) and for the split as a whole (`problems`).
 */
export function SplitEditor({ form, preview, context, currency, onChange, byMember, problems, you }: {
  form: SplitForm
  preview: SplitPreview
  context: SplitContext
  currency: string
  onChange: (form: SplitForm) => void
  byMember: Map<number, string[]>
  problems: string[]
  /** The reader's member id, marked in the rows. */
  you: number
}) {
  const { amount } = context
  const money = (minor: bigint | null) => (minor === null ? '—' : formatMoney(fromMinor(minor, currency), currency))

  function choose(mode: SplitMode) {
    // Amounts start from what the form shows now, so that switching keeps the split and a small change is quick.
    const amounts = mode === 'AMOUNT' && Object.keys(form.amounts).length === 0 && preview.rows.every((r) => r.amount !== null)
      ? Object.fromEntries(preview.rows.map((r) => [r.member.id, fromMinor(r.amount!, currency)]))
      : form.amounts
    onChange({ ...form, mode, amounts })
  }

  const percents = percentsOf(form, preview.rows.map((r) => r.member))
  return (
    <fieldset className="section split-editor">
      <legend>Split</legend>
      <div className="options">
        {MODES.filter(({ mode }) => mode !== 'KEEP' || form.keep).map(({ mode, label }) => (
          <label key={mode} className="check">
            <input type="radio" name="split" checked={form.mode === mode} onChange={() => choose(mode)} />
            {label(ruleLabel(context.ledger, context))}
          </label>
        ))}
      </div>
      {form.mode === 'ONE_MEMBER' && (
        <label className="field">
          <span className="label">On whom</span>
          <select value={form.member} onChange={(e) => onChange({ ...form, member: e.target.value })}>
            <option value="">Choose a member</option>
            {preview.rows.map((r) => <option key={r.member.id} value={r.member.id}>{r.member.displayName}</option>)}
          </select>
        </label>
      )}
      <table className="shares split-preview">
        <thead>
          <tr>
            <th scope="col">Member</th>
            {form.mode === 'PERCENT' && <th scope="col" className="amount">Percent</th>}
            {form.mode === 'AMOUNT' && <th scope="col" className="amount">Amount</th>}
            <th scope="col" className="amount">Share</th>
          </tr>
        </thead>
        <tbody>
          {preview.rows.map(({ member, amount: share, problem }) => {
            const messages = [...(problem ? [problem] : []), ...(byMember.get(member.id) ?? [])]
            return (
              <tr key={member.id}>
                <th scope="row">
                  {member.displayName}
                  {member.id === you && <span className="badge">You</span>}
                  {messages.map((m) => <small key={m} className="error" role="alert">{m}</small>)}
                </th>
                {form.mode === 'PERCENT' && (
                  <td className="amount nowrap">
                    <input className="amount percent" inputMode="decimal" value={percents[member.id] ?? ''}
                      aria-label={`Share of ${member.displayName} in percent`} aria-invalid={messages.length > 0}
                      onChange={(e) => onChange({ ...form, percents: { ...percents, [member.id]: e.target.value } })} /> %
                  </td>
                )}
                {form.mode === 'AMOUNT' && (
                  <td className="amount nowrap">
                    <input className="amount money" inputMode="decimal" value={form.amounts[member.id] ?? ''} placeholder="0"
                      aria-label={`Amount of ${member.displayName}`} aria-invalid={messages.length > 0}
                      onChange={(e) => onChange({ ...form, amounts: { ...form.amounts, [member.id]: e.target.value } })} />
                  </td>
                )}
                <td className="amount nowrap" data-testid={`share-${member.id}`}>
                  {money(share)}
                  {share !== null && amount !== undefined && amount > 0n && (
                    <small className="muted"> {basisPointsToPercent(basisPointsOf(share, amount))} %</small>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row">Total</th>
            {form.mode === 'PERCENT' && (
              <td className={`amount nowrap ${preview.percentTotal === 10_000 ? '' : 'error'}`} data-testid="percent-total">
                {preview.percentTotal === null ? '—' : `${basisPointsToPercent(preview.percentTotal)} %`}
              </td>
            )}
            {form.mode === 'AMOUNT' && (
              <td className={`amount nowrap ${preview.amountTotal !== null && preview.amountTotal === amount ? '' : 'error'}`}
                data-testid="amount-total">
                {money(preview.amountTotal)}
              </td>
            )}
            <td className="amount nowrap" data-testid="split-total">{money(amount ?? null)}</td>
          </tr>
        </tfoot>
      </table>
      {[...preview.problems, ...problems].map((m) => <p key={m} className="error small" role="alert">{m}</p>)}
      <p className="muted small">
        Each share is rounded down to the currency’s smallest unit, and what is left goes to the largest share, on a tie
        to {context.noun === 'income' ? 'the member who received it' : 'the payer'}.
        The saved {context.noun ?? 'expense'} shows the shares as they were stored.
      </p>
    </fieldset>
  )
}
