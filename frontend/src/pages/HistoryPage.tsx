import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { ArrowRightLeft, ChevronDown, History } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { getAccounts, getTransactions, type Transaction } from '@/api/client'
import { TransactionsList } from '@/components/TransactionsList'

export function HistoryPage() {
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const accountsQuery = useQuery({ queryKey: ['accounts'], queryFn: getAccounts })
  const accounts = accountsQuery.data ?? []
  const requestedId = searchParams.get('accountId')
  const selectedAccount = accounts.find((account) => account.id === requestedId) ?? accounts[0]
  const accountId = selectedAccount?.id ?? ''
  const historyQuery = useInfiniteQuery({
    queryKey: ['transactions', accountId],
    queryFn: ({ pageParam }) => getTransactions(accountId, pageParam as string | undefined),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor || undefined,
    enabled: Boolean(accountId),
  })
  const items = historyQuery.data?.pages.flatMap((page) => page.items ?? []) ?? []

  function chooseAccount(id: string) {
    setSearchParams(id ? { accountId: id } : {}, { replace: true })
  }

  function openTransaction(transaction: Transaction) {
    navigate(`/accounts/${accountId}?transaction=${transaction.id}`)
  }

  if (accountsQuery.isLoading) return <div className="page-stack"><Skeleton className="hero-skeleton" /><Skeleton className="activity-skeleton" /></div>

  return (
    <div className="page-stack">
      <div className="page-heading heading-with-actions"><div><p className="eyebrow">Your activity</p><h1>Transaction history</h1><p className="muted">Review posted money movements and open a balanced journal entry.</p></div>
        {accounts.length > 0 && <label className="account-picker"><span>Account</span><select aria-label="Choose account for transaction history" value={accountId} onChange={(event) => chooseAccount(event.target.value)}>{accounts.map((account) => <option key={account.id} value={account.id}>{account.displayName}</option>)}</select></label>}
      </div>
      {accounts.length === 0 ? <Card className="empty-card"><CardContent><div className="empty-illustration"><History size={25} /></div><h3>Nothing to review yet</h3><p>Open an account to see activity here.</p></CardContent></Card> : <>
        <div className="history-summary"><span className="history-summary-icon"><ArrowRightLeft size={18} /></span><span><strong>{selectedAccount?.displayName}</strong><small>Newest activity first · paged from PostgreSQL</small></span><span className="history-count">{items.length} shown</span></div>
        <Card className="activity-card"><CardContent className="activity-content">
          {historyQuery.isLoading ? <div className="activity-skeleton"><Skeleton /><Skeleton /><Skeleton /></div> : historyQuery.isError ? <div className="empty-inline">History could not be loaded. Refresh to try again.</div> : <TransactionsList items={items} onSelect={openTransaction} emptyLabel="No transactions for this account yet." />}
          {historyQuery.hasNextPage && <div className="load-more-wrap"><Button variant="outline" onClick={() => historyQuery.fetchNextPage()} disabled={historyQuery.isFetchingNextPage}>{historyQuery.isFetchingNextPage ? 'Loading…' : 'Load older activity'} <ChevronDown size={16} /></Button></div>}
        </CardContent></Card>
      </>}
    </div>
  )
}
