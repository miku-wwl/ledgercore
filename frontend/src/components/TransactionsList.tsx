import { ArrowDownLeft, ArrowUpRight, ChevronRight } from 'lucide-react'
import { formatTransactionAmount } from '@/lib/money'
import type { Transaction } from '@/api/client'

export function TransactionsList({ items, onSelect, emptyLabel = 'No transactions yet.' }: {
  items: Transaction[]
  onSelect?: (item: Transaction) => void
  emptyLabel?: string
}) {
  if (items.length === 0) return <div className="empty-inline"><span className="empty-icon"><ArrowUpRight size={19} /></span><span>{emptyLabel}</span></div>
  return <div className="transaction-list">
    {items.map((item) => {
      const incoming = item.direction === 'INCOMING'
      const row = <>
        <span className={`transaction-icon${incoming ? ' incoming' : ''}`} aria-hidden="true">
          {incoming ? <ArrowDownLeft size={17} /> : <ArrowUpRight size={17} />}
        </span>
        <span className="transaction-copy">
          <strong>{item.counterparty || 'Internal transfer'}</strong>
          <small>{incoming ? 'Money in' : 'Money out'} · {new Date(item.createdAt).toLocaleDateString('en-NZ', { day: 'numeric', month: 'short', year: 'numeric' })}</small>
        </span>
        <span className={`transaction-amount${incoming ? ' positive' : ''}`}>{formatTransactionAmount(item.amount, item.direction)}</span>
        {onSelect && <ChevronRight className="transaction-chevron" size={17} aria-hidden="true" />}
      </>
      return onSelect
        ? <button className="transaction-row transaction-button" key={item.id} type="button" onClick={() => onSelect(item)} aria-label={`${incoming ? 'Incoming' : 'Outgoing'} ${item.amount} NZD with ${item.counterparty}`}>
            {row}
          </button>
        : <div className="transaction-row" key={item.id}>{row}</div>
    })}
  </div>
}
