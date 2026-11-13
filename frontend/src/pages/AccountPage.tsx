import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { BadgeCheck, BookOpenText, ChevronDown, CircleCheck, Scale, ShieldCheck } from 'lucide-react'
import { useParams, useSearchParams } from 'react-router-dom'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { getAccount, getReconciliation, getTransactionDetail, getTransactions } from '@/api/client'
import { formatNzd } from '@/lib/money'
import { TransactionsList } from '@/components/TransactionsList'

export function AccountPage() {
  const { accountId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const transactionId = searchParams.get('transaction')
  const accountQuery = useQuery({ queryKey: ['account', accountId], queryFn: () => getAccount(accountId), enabled: Boolean(accountId) })
  const historyQuery = useInfiniteQuery({
    queryKey: ['transactions', accountId],
    queryFn: ({ pageParam }) => getTransactions(accountId, pageParam as string | undefined),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor || undefined,
    enabled: Boolean(accountId),
  })
  const reconciliationQuery = useQuery({
    queryKey: ['reconciliation', accountId],
    queryFn: () => getReconciliation(accountId),
    enabled: Boolean(accountId),
  })
  const detailQuery = useQuery({
    queryKey: ['transaction-detail', accountId, transactionId],
    queryFn: () => getTransactionDetail(accountId, transactionId!),
    enabled: Boolean(accountId && transactionId),
  })
  const items = historyQuery.data?.pages.flatMap((page) => page.items ?? []) ?? []
  const account = accountQuery.data

  function closeDetail(open: boolean) {
    if (open) return
    setSearchParams((current) => {
      current.delete('transaction')
      return current
    }, { replace: true })
  }

  if (accountQuery.isLoading) return <div className="page-stack"><Skeleton className="hero-skeleton" /><Skeleton className="activity-skeleton" /></div>
  if (accountQuery.isError || !account) return <div className="page-stack"><div className="page-heading"><p className="eyebrow">Account unavailable</p><h1>We could not find this account.</h1><p className="muted">It may have been removed, or it may belong to another customer.</p></div></div>

  return (
    <div className="page-stack">
      <div className="page-heading account-heading">
        <div><p className="eyebrow">Account details · NZD</p><h1>{account.displayName}</h1><p className="muted">Your account view combines a current balance with its immutable transaction history.</p></div>
        <Badge variant="outline" className="account-status"><span className="status-dot" /> {account.status}</Badge>
      </div>
      <section className="account-summary-grid">
        <Card className="account-balance-panel"><CardContent>
          <p className="balance-label">Current balance</p>
          <p className="account-detail-total">{formatNzd(account.balance)}</p>
          <div className="account-id-line"><span>Account reference</span><code>{account.id.slice(0, 8).toUpperCase()} ···· {account.id.slice(-4).toUpperCase()}</code></div>
        </CardContent></Card>
        <Card className="reconcile-card"><CardHeader><div className="reconcile-title-icon"><Scale size={18} /></div><CardDescription>Balance integrity</CardDescription><CardTitle>Ledger reconciliation</CardTitle></CardHeader>
          <CardContent>
            {reconciliationQuery.isLoading ? <Skeleton className="reconcile-skeleton" /> : reconciliationQuery.isError ? <p className="muted">Reconciliation is unavailable right now.</p> : <>
              <div className={`reconcile-result${reconciliationQuery.data?.matches ? ' reconcile-good' : ' reconcile-bad'}`}>
                {reconciliationQuery.data?.matches ? <CircleCheck size={17} /> : <BadgeCheck size={17} />}
                <span>{reconciliationQuery.data?.matches ? 'Ledger and projection match' : 'Balance mismatch detected'}</span>
              </div>
              <div className="reconcile-values"><span>Ledger source<strong>{formatNzd(reconciliationQuery.data?.authoritativeLedgerBalance)}</strong></span><span>Fast view<strong>{formatNzd(reconciliationQuery.data?.projectedBalance)}</strong></span></div>
            </>}
          </CardContent>
        </Card>
      </section>

      <section className="section-block">
        <div className="section-heading"><div><p className="eyebrow">Posted activity</p><h2>Transaction history</h2></div><span className="muted">Newest first</span></div>
        <Card className="activity-card"><CardContent className="activity-content">
          {historyQuery.isLoading ? <div className="activity-skeleton"><Skeleton /><Skeleton /><Skeleton /></div> : historyQuery.isError ? <div className="empty-inline">History could not be loaded. Refresh to try again.</div> : <TransactionsList items={items} onSelect={(item) => setSearchParams({ transaction: item.id })} />}
          {historyQuery.hasNextPage && <div className="load-more-wrap"><Button variant="outline" onClick={() => historyQuery.fetchNextPage()} disabled={historyQuery.isFetchingNextPage}>{historyQuery.isFetchingNextPage ? 'Loading…' : 'Load older activity'} <ChevronDown size={16} /></Button></div>}
        </CardContent></Card>
      </section>

      <Card className="ledger-education-card"><CardHeader><div className="education-icon"><BookOpenText size={20} /></div><div><p className="eyebrow">A closer look</p><CardTitle>What a posted transfer means</CardTitle></div></CardHeader><CardContent>
        <p>A successful transfer produces one journal entry with a debit and a credit. The UI labels money in and out for everyday understanding; the ledger labels the accounting side of each posting.</p>
        <div className="education-flow"><span><ShieldCheck size={16} /> Authoritative postings</span><span className="flow-line" aria-hidden="true" /><span><Scale size={16} /> Rebuilt balance view</span></div>
      </CardContent></Card>

      <Dialog open={Boolean(transactionId)} onOpenChange={closeDetail}>
        <DialogContent className="transaction-dialog">
          <DialogHeader><DialogTitle>Transfer record</DialogTitle><DialogDescription>Posted transaction · {account.displayName}</DialogDescription></DialogHeader>
          {detailQuery.isLoading && <div className="activity-skeleton"><Skeleton /><Skeleton /></div>}
          {detailQuery.isError && <p className="muted">This transaction could not be opened.</p>}
          {detailQuery.data && <>
            <div className="detail-amount"><span>{detailQuery.data.direction === 'INCOMING' ? 'Received' : 'Sent'}</span><strong>{formatNzd(detailQuery.data.amount)}</strong><small>{new Date(detailQuery.data.createdAt).toLocaleString('en-NZ')}</small></div>
            <Separator />
            <div className="posting-list"><p className="eyebrow">Balanced journal entry</p>{(detailQuery.data.postings ?? []).map((posting, index) => <div className="posting-row" key={`${posting.account}-${posting.side}-${index}`}><span>{posting.account}</span><Badge variant="outline">{posting.side}</Badge><strong>{formatNzd(posting.amount)}</strong></div>)}</div>
            <p className="accounting-explanation">{detailQuery.data.accountingExplanation}</p>
          </>}
        </DialogContent>
      </Dialog>
    </div>
  )
}
