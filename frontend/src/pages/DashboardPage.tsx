import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { ArrowRight, ArrowUpRight, Check, Plus, RefreshCw, WalletCards } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError, createAccount, getAccounts, getTransactions, type Account, type Transaction } from '@/api/client'
import { formatNzd, sumNzd } from '@/lib/money'
import { TransactionsList } from '@/components/TransactionsList'

export function DashboardPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [createOpen, setCreateOpen] = useState(false)
  const [todayLabel, setTodayLabel] = useState('Today')
  const [accountName, setAccountName] = useState('Everyday account')
  const [formError, setFormError] = useState('')
  const accountsQuery = useQuery({ queryKey: ['accounts'], queryFn: getAccounts })
  const accounts = accountsQuery.data ?? []
  const featuredAccountId = accounts[0]?.id
  const activityQuery = useQuery({
    queryKey: ['transactions', 'recent', featuredAccountId],
    queryFn: () => getTransactions(featuredAccountId!),
    enabled: Boolean(featuredAccountId),
  })
  const createMutation = useMutation({
    mutationFn: createAccount,
    onSuccess: async () => {
      setCreateOpen(false)
      setFormError('')
      await queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
    onError: (error) => setFormError(error instanceof ApiError ? error.message : 'Account could not be created.'),
  })

  useEffect(() => {
    const animationFrame = window.requestAnimationFrame(() => {
      setTodayLabel(new Date().toLocaleDateString('en-NZ', { weekday: 'long', day: 'numeric', month: 'long' }))
    })
    return () => window.cancelAnimationFrame(animationFrame)
  }, [])

  function submitAccount(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError('')
    createMutation.mutate(accountName.trim())
  }

  function openTransaction(account: Account, transaction: Transaction) {
    navigate(`/accounts/${account.id}?transaction=${transaction.id}`)
  }

  if (accountsQuery.isLoading) return <div className="page-stack"><Skeleton className="hero-skeleton" /><div className="account-grid"><Skeleton className="account-skeleton" /><Skeleton className="account-skeleton" /></div></div>

  return (
    <div className="page-stack">
      <div className="page-heading heading-with-actions">
        <div><p className="eyebrow">{todayLabel}</p><h1>Your money, in view.</h1><p className="muted">A clear picture of every simulated NZD account.</p></div>
        <div className="heading-actions"><Button variant="outline" onClick={() => queryClient.invalidateQueries({ queryKey: ['accounts'] })}><RefreshCw size={16} /> Refresh</Button><Button onClick={() => { setAccountName('Everyday account'); setCreateOpen(true) }}><Plus size={17} /> Open account</Button></div>
      </div>

      <Alert className="simulation-alert"><span className="simulation-alert-mark"><Check size={16} /></span><div><AlertTitle>Simulation environment</AlertTitle><AlertDescription>All balances and transfers are practice data. Nothing connects to a real bank.</AlertDescription></div></Alert>

      {accountsQuery.isError && <Alert variant="destructive" role="alert"><AlertTitle>Accounts could not load</AlertTitle><AlertDescription>Please refresh this view or sign in again.</AlertDescription></Alert>}

      <section className="overview-grid" aria-label="Account overview">
        <Card className="balance-card">
          <CardContent className="balance-card-content">
            <div className="balance-card-top"><span className="balance-icon"><WalletCards size={20} aria-hidden="true" /></span><Badge variant="secondary">NZD · SIMULATED</Badge></div>
            <p className="balance-label">Total across your accounts</p>
            <p className="balance-total" data-testid="total-balance">{formatNzd(sumNzd(accounts.map((account) => account.balance)))}</p>
            <div className="balance-card-foot"><span>{accounts.length} {accounts.length === 1 ? 'account' : 'accounts'} · updated from ledger projection</span><span className="balance-foot-check"><Check size={14} /> Synced</span></div>
          </CardContent>
        </Card>
        <Card className="insight-card">
          <CardHeader><CardDescription>Ledger insight</CardDescription><CardTitle>Every transfer balances.</CardTitle></CardHeader>
          <CardContent><p>Each movement creates a debit and a credit. Your balance is a view over those posted records—not a number edited by the form.</p><Link to={featuredAccountId ? `/accounts/${featuredAccountId}` : '/'} className="text-link">Explore an account <ArrowRight size={15} /></Link></CardContent>
        </Card>
      </section>

      <section className="section-block">
        <div className="section-heading"><div><p className="eyebrow">Your accounts</p><h2>Accounts</h2></div><Button variant="ghost" onClick={() => setCreateOpen(true)}>Add account <Plus size={16} /></Button></div>
        {accounts.length === 0 ? <Card className="empty-card"><CardContent><div className="empty-illustration"><WalletCards size={26} /></div><h3>Start with an account</h3><p>Open a simulated NZD account. LedgerCore adds the opening funds as a balanced ledger entry.</p><Button onClick={() => setCreateOpen(true)}><Plus size={17} /> Open your first account</Button></CardContent></Card> :
          <div className="account-grid">{accounts.map((account, index) => <AccountCard key={account.id} account={account} index={index} />)}</div>}
      </section>

      <section className="section-block">
        <div className="section-heading"><div><p className="eyebrow">Latest activity</p><h2>Recent transactions</h2></div>{featuredAccountId && <Link to={`/history?accountId=${featuredAccountId}`} className="text-link">View history <ArrowRight size={15} /></Link>}</div>
        <Card className="activity-card"><CardContent className="activity-content">
          {!featuredAccountId ? <div className="empty-inline">Create an account to see your activity.</div> : activityQuery.isLoading ? <div className="activity-skeleton"><Skeleton /><Skeleton /><Skeleton /></div> :
            activityQuery.isError ? <div className="empty-inline">Activity could not be loaded. Refresh to try again.</div> :
              <TransactionsList items={activityQuery.data?.items ?? []} onSelect={(transaction) => openTransaction(accounts[0], transaction)} />}
        </CardContent></Card>
      </section>

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Open a simulated account</DialogTitle><DialogDescription>Opening funds are added by a balanced journal entry against the simulation reserve.</DialogDescription></DialogHeader>
          {formError && <Alert variant="destructive" role="alert"><AlertTitle>Account not created</AlertTitle><AlertDescription>{formError}</AlertDescription></Alert>}
          <form id="create-account-form" className="form-stack" onSubmit={submitAccount}>
            <div className="form-field"><Label htmlFor="account-name">Account name</Label><Input id="account-name" maxLength={80} required value={accountName} onChange={(event) => setAccountName(event.target.value)} /></div>
            <div className="opening-funds-note"><span>Opening balance</span><strong>NZ$1,000.00</strong></div>
          </form>
          <DialogFooter><Button variant="outline" type="button" onClick={() => setCreateOpen(false)}>Cancel</Button><Button type="submit" form="create-account-form" disabled={createMutation.isPending}>{createMutation.isPending ? 'Opening…' : 'Open account'}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function AccountCard({ account, index }: { account: Account; index: number }) {
  return (
    <Link to={`/accounts/${account.id}`} className={`account-card${index === 0 ? ' account-card-featured' : ''}`}>
      <div className="account-card-top"><span className={`account-card-icon account-tone-${index % 3}`}><WalletCards size={19} /></span><Badge variant="outline">{account.status}</Badge></div>
      <span className="account-name">{account.displayName}</span>
      <span className="account-number">NZD account · ending {account.id.slice(-4).toUpperCase()}</span>
      <span className="account-balance">{formatNzd(account.balance)}</span>
      <span className="account-card-bottom"><span>Available balance</span><ArrowUpRight size={17} /></span>
    </Link>
  )
}
