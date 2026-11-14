import { useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertCircle, ArrowDown, ArrowRight, Check, CircleHelp, KeyRound, ShieldCheck } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { getTransferAccounts, submitTransfer, ApiError, type TransferIntent, type TransferReceipt } from '@/api/client'
import { formatNzd, parseNzdToMinorUnits } from '@/lib/money'

const PENDING_KEY = 'ledgercore.pending-transfer.v1'

type StoredIntent = { intent: TransferIntent; idempotencyKey: string }
type Outcome = 'idle' | 'confirming' | 'submitting' | 'unknown' | 'success' | 'rejected'

function readPendingIntent(): StoredIntent | null {
  try {
    const value = sessionStorage.getItem(PENDING_KEY)
    if (!value) return null
    const parsed = JSON.parse(value) as StoredIntent
    if (parsed.intent?.sourceAccountId && parsed.intent?.targetAccountId && parsed.intent?.amount && parsed.idempotencyKey) return parsed
  } catch {
    sessionStorage.removeItem(PENDING_KEY)
  }
  return null
}

export function TransferPage() {
  const queryClient = useQueryClient()
  const accountsQuery = useQuery({ queryKey: ['transfer-accounts'], queryFn: getTransferAccounts })
  const accounts = accountsQuery.data ?? []
  const sourceAccounts = accounts.filter((account) => account.canDebit)
  const [pending, setPending] = useState<StoredIntent | null>(readPendingIntent)
  const [sourceId, setSourceId] = useState(() => pending?.intent.sourceAccountId ?? '')
  const [targetId, setTargetId] = useState(() => pending?.intent.targetAccountId ?? '')
  const [amount, setAmount] = useState(() => pending?.intent.amount ?? '')
  const [fieldError, setFieldError] = useState('')
  const [requestError, setRequestError] = useState('')
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [outcome, setOutcome] = useState<Outcome>(() => pending ? 'unknown' : 'idle')
  const [receipt, setReceipt] = useState<TransferReceipt | null>(null)

  const source = sourceAccounts.find((account) => account.id === sourceId)
  const target = accounts.find((account) => account.id === targetId)

  function prepareTransfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFieldError('')
    setRequestError('')
    if (pending) {
      setFieldError('Resolve the saved transfer before starting another one.')
      return
    }
    if (!sourceId || !targetId) {
      setFieldError('Choose both a source and a destination account.')
      return
    }
    if (sourceId === targetId) {
      setFieldError('Choose two different accounts.')
      return
    }
    if (!parseNzdToMinorUnits(amount)) {
      setFieldError('Enter a positive NZD amount with exactly two decimal places, such as 12.50.')
      return
    }
    setOutcome('confirming')
    setConfirmOpen(true)
  }

  async function sendIntent(saved: StoredIntent) {
    setOutcome('submitting')
    setRequestError('')
    try {
      const result = await submitTransfer(saved.intent, saved.idempotencyKey)
      sessionStorage.removeItem(PENDING_KEY)
      setPending(null)
      setReceipt(result)
      setOutcome('success')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['accounts'] }),
        queryClient.invalidateQueries({ queryKey: ['transfer-accounts'] }),
        queryClient.invalidateQueries({ queryKey: ['transactions'] }),
        queryClient.invalidateQueries({ queryKey: ['reconciliation'] }),
      ])
    } catch (error) {
      if (error instanceof ApiError && error.status < 500) {
        sessionStorage.removeItem(PENDING_KEY)
        setPending(null)
        setOutcome('rejected')
        setRequestError(error.status === 422
          ? 'The source account does not have enough available funds. No transfer was posted.'
          : error.message)
      } else {
        setPending(saved)
        setOutcome('unknown')
        setRequestError('The connection ended before we could confirm the result. The original request is saved in this tab; check it using the same key.')
      }
    }
  }

  async function confirmAndSend() {
    if (!source || !target) return
    const saved: StoredIntent = {
      intent: { sourceAccountId: source.id, targetAccountId: target.id, amount },
      idempotencyKey: window.crypto.randomUUID(),
    }
    sessionStorage.setItem(PENDING_KEY, JSON.stringify(saved))
    setPending(saved)
    setConfirmOpen(false)
    await sendIntent(saved)
  }

  function startAnotherTransfer() {
    setReceipt(null)
    setOutcome('idle')
    setAmount('')
  }

  const canStart = sourceAccounts.some((account) => account.status === 'OPEN') && accounts.filter((account) => account.status === 'OPEN').length >= 2

  return (
    <div className="page-stack transfer-page">
      <div className="page-heading"><p className="eyebrow">Move simulated funds</p><h1>Make a transfer</h1><p className="muted">Choose two accounts, review the intent, then confirm once.</p></div>

      {outcome === 'unknown' && pending && <Alert className="unknown-alert" role="alert"><CircleHelp className="unknown-mark" size={20} /><div><AlertTitle>Transfer result unknown</AlertTitle><AlertDescription>{requestError || 'This tab has a saved transfer that has not been confirmed yet. Retry with the same intent and key to recover the original receipt.'}</AlertDescription><div className="unknown-actions"><Button onClick={() => sendIntent(pending)}>Check the saved transfer</Button><span><KeyRound size={14} /> Original key is retained in this tab</span></div></div></Alert>}

      {outcome === 'submitting' && <Alert role="status"><AlertTitle>Confirming the transfer</AlertTitle><AlertDescription>Keep this page open while LedgerCore records the transfer and its durable receipt.</AlertDescription></Alert>}

      {outcome === 'rejected' && requestError && <Alert variant="destructive" role="alert"><AlertCircle size={18} /><div><AlertTitle>Transfer not posted</AlertTitle><AlertDescription>{requestError}</AlertDescription></div></Alert>}

      {outcome === 'success' && receipt && <Card className="success-card"><CardContent><span className="success-icon"><Check size={21} /></span><div><Badge variant="secondary">POSTED</Badge><h2>Transfer complete</h2><p>{formatNzd(receipt.amount)} moved. The original receipt is saved with the transaction.</p><div className="receipt-reference"><span>Receipt</span><code>{receipt.transferId}</code></div></div><Button variant="outline" onClick={startAnotherTransfer}>Make another transfer</Button></CardContent></Card>}

      {accountsQuery.isError && <Alert variant="destructive" role="alert"><AlertTitle>Accounts could not load</AlertTitle><AlertDescription>Refresh this page or sign in again to continue.</AlertDescription></Alert>}

      {!canStart && !accountsQuery.isLoading && <Card className="empty-card"><CardContent><div className="empty-illustration"><ArrowRight size={25} /></div><h3>An authorized source and an open destination are needed</h3><p>Open another simulated account from your overview or ask an organization administrator to grant your member role. Every new account starts with balanced opening funds.</p></CardContent></Card>}

      {canStart && outcome !== 'success' && <div className="transfer-layout">
        <Card className={`transfer-form-card${pending ? ' transfer-form-disabled' : ''}`}>
          <CardHeader><div className="form-step"><span>01</span><span>Transfer details</span></div><CardTitle>Where should the money go?</CardTitle><CardDescription>The source account must have enough available funds.</CardDescription></CardHeader>
          <CardContent>
            <form className="transfer-form" onSubmit={prepareTransfer}>
              <div className="form-field"><Label htmlFor="source-account">From account</Label><select id="source-account" aria-label="From account" value={sourceId} disabled={Boolean(pending)} onChange={(event) => { setSourceId(event.target.value); if (event.target.value === targetId) setTargetId('') }}><option value="">Choose an account</option>{sourceAccounts.filter((account) => account.status === 'OPEN').map((account) => <option key={account.id} value={account.id}>{account.displayName} · {formatNzd(account.balance)}</option>)}</select></div>
              <div className="transfer-direction-mark" aria-hidden="true"><ArrowDown size={16} /></div>
              <div className="form-field"><Label htmlFor="target-account">To account</Label><select id="target-account" aria-label="To account" value={targetId} disabled={Boolean(pending)} onChange={(event) => setTargetId(event.target.value)}><option value="">Choose an account</option>{accounts.filter((account) => account.status === 'OPEN').map((account) => <option key={account.id} value={account.id}>{account.displayName}</option>)}</select></div>
              <div className="form-field amount-field"><div className="label-row"><Label htmlFor="transfer-amount">Amount</Label><span className="field-hint">NZD only</span></div><div className="amount-input-wrap"><span>NZ$</span><Input id="transfer-amount" inputMode="decimal" autoComplete="off" placeholder="0.00" value={amount} disabled={Boolean(pending)} onChange={(event) => setAmount(event.target.value.replace(/[^0-9.]/g, '').slice(0, 18))} aria-describedby="amount-hint" /></div><span className="field-hint" id="amount-hint">Use two decimal places, for example 12.50.</span></div>
              {fieldError && <p className="field-error" role="alert">{fieldError}</p>}
              <Button className="review-transfer-button" type="submit" disabled={Boolean(pending)}>Review transfer <ArrowRight size={17} /></Button>
            </form>
          </CardContent>
        </Card>

        <aside className="transfer-aside">
          <Card className="transfer-summary-card"><CardHeader><CardDescription>Before you confirm</CardDescription><CardTitle>A reliable transfer has a receipt.</CardTitle></CardHeader><CardContent><div className="summary-step"><span>1</span><p><strong>Your intent is saved</strong><small>We keep the same amount, accounts and key while checking the result.</small></p></div><div className="summary-step"><span>2</span><p><strong>The ledger commits once</strong><small>Both postings and the success receipt share one database transaction.</small></p></div><div className="summary-step"><span>3</span><p><strong>Retry with the original key</strong><small>If a response is lost, retrying recovers the saved receipt.</small></p></div><div className="summary-security"><ShieldCheck size={17} /><span>CSRF protected · server checks account ownership</span></div></CardContent></Card>
          {source && <Card className="available-card"><CardContent><span>Available in {source.displayName}</span><strong>{formatNzd(source.balance)}</strong></CardContent></Card>}
        </aside>
      </div>}

      <Dialog open={confirmOpen} onOpenChange={(open) => { setConfirmOpen(open); if (!open) setOutcome('idle') }}>
        <DialogContent>
          <DialogHeader><DialogTitle>Review this transfer</DialogTitle><DialogDescription>Check both accounts and the amount. The server will reserve an idempotency key when you confirm.</DialogDescription></DialogHeader>
          <div className="confirmation-summary"><div><span>From</span><strong>{source?.displayName}</strong></div><div className="confirmation-down"><ArrowDown size={17} /></div><div><span>To</span><strong>{target?.displayName}</strong></div><div className="confirmation-amount"><span>Amount</span><strong>{formatNzd(amount)}</strong></div></div>
          <DialogFooter><Button variant="outline" type="button" onClick={() => { setConfirmOpen(false); setOutcome('idle') }}>Go back</Button><Button type="button" onClick={confirmAndSend}>Confirm transfer <ArrowRight size={17} /></Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}
