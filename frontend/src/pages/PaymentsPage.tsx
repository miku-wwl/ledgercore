import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, Building2, CircleAlert, CircleCheck, CircleDashed, Plus, ShieldCheck } from 'lucide-react'
import {
  approveCorporatePayment,
  createCorporatePayment,
  getCorporatePayments,
  getOrganizationAccounts,
  getOrganizations,
  submitCorporatePayment,
  type CorporatePayment,
  type Organization,
} from '@/api/client'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatNzd } from '@/lib/money'

const AMOUNT_PATTERN = /^(?:0|[1-9][0-9]{0,11})\.[0-9]{2}$/
const PAGE_SIZE = 10
const EMPTY_ORGANIZATIONS: Organization[] = []

function message(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

function statusLabel(payment: CorporatePayment): { label: string; detail: string; tone: string } {
  if (payment.externalStatus === 'UNKNOWN') {
    return { label: 'Outcome unknown', detail: 'Check the saved operation before taking another action.', tone: 'border-amber-300 bg-amber-50 text-amber-900' }
  }
  if (payment.externalStatus === 'PENDING' || payment.externalStatus === 'ACCEPTED') {
    return { label: payment.externalStatus === 'ACCEPTED' ? 'Accepted by clearing' : 'Pending at clearing', detail: 'Acceptance does not confirm settlement.', tone: 'border-blue-200 bg-blue-50 text-blue-900' }
  }
  if (payment.status === 'COMPLETED') {
    return { label: 'Settled', detail: 'Settled by clearing and posted to the ledger.', tone: 'border-emerald-200 bg-emerald-50 text-emerald-900' }
  }
  if (payment.externalStatus === 'SETTLED') {
    return { label: 'Settled externally, posting pending', detail: 'Clearing settled; the bank settlement journal is still owed. Do not resend or refund.', tone: 'border-amber-300 bg-amber-50 text-amber-900' }
  }
  if (payment.status === 'REJECTED') {
    return { label: 'Rejected', detail: payment.bookingJournalEntryId ? 'Rejected by clearing; the booking was refunded.' : 'Rejected before booking.', tone: 'border-rose-200 bg-rose-50 text-rose-900' }
  }
  if (payment.externalStatus === 'REJECTED') {
    return { label: 'Rejected externally, refund pending', detail: 'Clearing rejected the payment; the refund journal is still owed.', tone: 'border-amber-300 bg-amber-50 text-amber-900' }
  }
  if (payment.status === 'PENDING_CLEARING') {
    return { label: 'Pending clearing', detail: 'The booking remains payable until a terminal outcome arrives.', tone: 'border-amber-200 bg-amber-50 text-amber-900' }
  }
  return { label: payment.status ?? 'Unknown state', detail: 'Review the approval history before proceeding.', tone: 'border-slate-200 bg-slate-50 text-slate-800' }
}

export function PaymentsPage({ customerId }: { customerId: string }) {
  const queryClient = useQueryClient()
  const organizationsQuery = useQuery({ queryKey: ['organizations'], queryFn: getOrganizations })
  const organizations = organizationsQuery.data ?? EMPTY_ORGANIZATIONS
  const eligibleOrganizations = organizations.filter((organization) =>
    organization.roles?.some((role) => ['PAYMENT_MAKER', 'PAYMENT_APPROVER', 'OPERATIONS'].includes(role)),
  )
  const [selectedId, setSelectedId] = useState('')
  const [page, setPage] = useState(0)
  const [sourceAccountId, setSourceAccountId] = useState('')
  const [beneficiaryName, setBeneficiaryName] = useState('')
  const [beneficiaryReference, setBeneficiaryReference] = useState('')
  const [routingCode, setRoutingCode] = useState('SIM-SETTLE')
  const [amount, setAmount] = useState('')
  const organization = eligibleOrganizations.find((item) => item.id === selectedId) ?? eligibleOrganizations[0]
  const organizationId = organization?.id
  const roles = organization?.roles ?? []
  const canMake = roles.includes('PAYMENT_MAKER')
  const canApprove = roles.includes('PAYMENT_APPROVER')
  const canOperate = roles.includes('OPERATIONS')

  const accountsQuery = useQuery({
    queryKey: ['organizations', organizationId, 'accounts'],
    queryFn: () => getOrganizationAccounts(organizationId as string),
    enabled: Boolean(organizationId),
  })
  const paymentsQuery = useQuery({
    queryKey: ['corporate-payments', organizationId, page],
    queryFn: () => getCorporatePayments(organizationId as string, page, PAGE_SIZE),
    enabled: Boolean(organizationId),
    refetchInterval: 5_000,
    refetchOnWindowFocus: true,
  })

  async function refreshPayments(payment?: CorporatePayment) {
    if (payment) queryClient.setQueryData(['corporate-payment', payment.id], payment)
    await queryClient.invalidateQueries({ queryKey: ['corporate-payments', organizationId] })
    await queryClient.invalidateQueries({ queryKey: ['organizations', organizationId, 'accounts'] })
  }

  const createMutation = useMutation({
    mutationFn: () => createCorporatePayment(organizationId as string, {
      sourceAccountId, beneficiaryName: beneficiaryName.trim(),
      beneficiaryAccountReference: beneficiaryReference.trim(),
      beneficiaryRoutingCode: routingCode.trim(), amount,
    }),
    onSuccess: async () => {
      setBeneficiaryName('')
      setBeneficiaryReference('')
      setAmount('')
      setPage(0)
      await refreshPayments()
    },
  })
  const submitMutation = useMutation({
    mutationFn: submitCorporatePayment,
    onSuccess: refreshPayments,
  })
  const approveMutation = useMutation({
    mutationFn: approveCorporatePayment,
    onSuccess: refreshPayments,
  })

  function createPayment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (organizationId && sourceAccountId && canMake && AMOUNT_PATTERN.test(amount)) createMutation.mutate()
  }

  const actionError = createMutation.error ?? submitMutation.error ?? approveMutation.error
  const rows = paymentsQuery.data?.items ?? []
  const total = paymentsQuery.data?.total ?? 0
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="page-stack">
      <div className="page-heading">
        <p className="eyebrow">Corporate banking</p>
        <h1>Payments</h1>
        <p className="muted">Prepare outgoing payments, obtain an independent approval, then track clearing through its confirmed outcome.</p>
      </div>

      {organizationsQuery.isError && <Alert variant="destructive"><AlertTitle>Organizations could not load</AlertTitle><AlertDescription>{message(organizationsQuery.error, 'Refresh and try again.')}</AlertDescription></Alert>}
      {actionError && <Alert variant="destructive"><AlertTitle>Payment action was not confirmed</AlertTitle><AlertDescription>{message(actionError, 'Reload the saved payment before repeating an action.')}</AlertDescription></Alert>}
      {paymentsQuery.isError && <Alert variant="destructive"><AlertTitle>Payment history could not load</AlertTitle><AlertDescription>{message(paymentsQuery.error, 'Your organization role may have changed. Refresh your access.')}</AlertDescription></Alert>}

      {eligibleOrganizations.length > 1 && (
        <div className="grid max-w-xl gap-2">
          <Label htmlFor="payment-organization">Organization</Label>
          <select id="payment-organization" value={organization?.id ?? ''} onChange={(event) => { setSelectedId(event.target.value); setPage(0) }}>
            {eligibleOrganizations.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}
          </select>
        </div>
      )}

      {!organizationsQuery.isLoading && eligibleOrganizations.length === 0 && (
        <Card className="empty-card"><CardContent><Building2 size={24} /><h3>No payment role is available</h3><p>Ask an organization administrator to grant PAYMENT_MAKER or PAYMENT_APPROVER. Corporate accounts cannot be debited through personal transfers.</p></CardContent></Card>
      )}

      {organization && <>
        <Card className="border-emerald-200 bg-emerald-50/60">
          <CardContent className="flex flex-wrap items-center gap-3 p-4 text-sm text-emerald-950">
            <ShieldCheck size={18} aria-hidden="true" />
            <span><strong>{organization.displayName}</strong> · your access: {roles.map((role) => role.replaceAll('_', ' ')).join(', ')}</span>
          </CardContent>
        </Card>

        {canMake && <Card>
          <CardHeader><CardTitle>Create a corporate payment</CardTitle><CardDescription>The payment is a draft until submitted. A different payment approver must approve it before funds are booked.</CardDescription></CardHeader>
          <CardContent>
            <form className="grid gap-4 md:grid-cols-2" onSubmit={createPayment}>
              <div className="grid gap-2 md:col-span-2">
                <Label htmlFor="payment-source">Pay from</Label>
                <select id="payment-source" value={sourceAccountId} onChange={(event) => setSourceAccountId(event.target.value)} required>
                  <option value="">Choose an organization account</option>
                  {(accountsQuery.data ?? []).map((account) => <option key={account.id} value={account.id}>{account.displayName} · {formatNzd(account.balance)}</option>)}
                </select>
              </div>
              <div className="grid gap-2"><Label htmlFor="beneficiary-name">Beneficiary name</Label><Input id="beneficiary-name" maxLength={120} value={beneficiaryName} onChange={(event) => setBeneficiaryName(event.target.value)} required /></div>
              <div className="grid gap-2"><Label htmlFor="beneficiary-reference">Beneficiary account reference</Label><Input id="beneficiary-reference" maxLength={80} value={beneficiaryReference} onChange={(event) => setBeneficiaryReference(event.target.value)} required /></div>
              <div className="grid gap-2"><Label htmlFor="beneficiary-routing">Simulator route</Label>
                <select id="beneficiary-routing" value={routingCode} onChange={(event) => setRoutingCode(event.target.value)}>
                  <option value="SIM-SETTLE">Simulated settlement</option><option value="SIM-ACCEPT">Accepted, then inquiry</option>
                  <option value="SIM-PENDING">Pending outcome</option><option value="SIM-UNKNOWN">Unknown outcome</option><option value="SIM-REJECT">Rejected outcome</option>
                </select>
              </div>
              <div className="grid gap-2"><Label htmlFor="corporate-payment-amount">Amount (NZD)</Label><Input id="corporate-payment-amount" inputMode="decimal" pattern="(?:0|[1-9][0-9]{0,11})\.[0-9]{2}" placeholder="0.00" value={amount} onChange={(event) => setAmount(event.target.value)} required /><span className="text-xs text-muted-foreground">Use two decimal places, for example 25.00.</span></div>
              <div className="md:col-span-2"><Button type="submit" disabled={!sourceAccountId || !beneficiaryName.trim() || !beneficiaryReference.trim() || !AMOUNT_PATTERN.test(amount) || createMutation.isPending}><Plus size={16} />{createMutation.isPending ? 'Creating…' : 'Create payment draft'}</Button></div>
            </form>
          </CardContent>
        </Card>}

        {canApprove && !canMake && <p className="text-sm text-muted-foreground">Approver access is independent. Review submitted payments below; the maker cannot approve their own payment.</p>}

        <Card>
          <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3">
            <div><CardDescription>{organization.displayName}</CardDescription><CardTitle>Payment history</CardTitle><p className="mt-2 text-sm text-muted-foreground">Showing {rows.length ? page * PAGE_SIZE + 1 : 0}–{page * PAGE_SIZE + rows.length} of {total} payments.</p></div>
            <Button variant="outline" onClick={() => void paymentsQuery.refetch()} disabled={paymentsQuery.isFetching}>Refresh status</Button>
          </CardHeader>
          <CardContent>
            {paymentsQuery.isLoading ? <p role="status" className="py-8 text-center text-sm text-muted-foreground">Loading corporate payments…</p> : rows.length === 0 ? <p className="py-8 text-center text-sm text-muted-foreground">No corporate payments have been created yet.</p> : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[760px] text-left text-sm">
                  <caption className="sr-only">Corporate payment approvals and external clearing outcomes</caption>
                  <thead><tr className="border-b text-xs text-muted-foreground"><th className="px-3 py-3">Beneficiary</th><th className="px-3 py-3">Amount</th><th className="px-3 py-3">Approval</th><th className="px-3 py-3">Clearing outcome</th><th className="px-3 py-3">Action</th></tr></thead>
                  <tbody>{rows.map((payment) => {
                    const outcome = statusLabel(payment)
                    const isSubmitter = payment.submittedByCustomerId === customerId || payment.createdByCustomerId === customerId
                    const working = submitMutation.isPending || approveMutation.isPending
                    return <tr key={payment.id} className="border-b align-top" data-testid={`payment-${payment.id}`}>
                      <td className="px-3 py-4"><strong>{payment.beneficiaryName}</strong><code className="mt-1 block max-w-[205px] truncate text-xs text-muted-foreground">{payment.id}</code></td>
                      <td className="whitespace-nowrap px-3 py-4 font-semibold">{formatNzd(payment.amount)}</td>
                      <td className="px-3 py-4"><Badge variant="outline">{(payment.status ?? 'UNKNOWN').replaceAll('_', ' ')}</Badge><small className="mt-1 block text-muted-foreground">{payment.approvedByCustomerId ? 'Approved by a separate member' : payment.submittedByCustomerId ? 'Awaiting checker' : 'Draft'}</small></td>
                      <td className="max-w-[240px] px-3 py-4"><Badge variant="outline" className={outcome.tone}>{outcome.label}</Badge><small className="mt-1 block leading-5 text-muted-foreground">{outcome.detail}</small>{payment.externalStatus && <small className="block text-muted-foreground">External state: {payment.externalStatus}</small>}</td>
                      <td className="px-3 py-4"><div className="flex flex-wrap gap-2">
                        {canMake && payment.status === 'DRAFT' && payment.createdByCustomerId === customerId && <Button size="sm" onClick={() => payment.id && submitMutation.mutate(payment.id)} disabled={working}>Submit</Button>}
                        {canApprove && payment.status === 'SUBMITTED' && !isSubmitter && <Button size="sm" onClick={() => payment.id && approveMutation.mutate(payment.id)} disabled={working}>Approve</Button>}
                        {canApprove && payment.status === 'SUBMITTED' && isSubmitter && <span className="text-xs text-muted-foreground">Independent checker required</span>}
                        {canOperate && payment.status === 'PENDING_CLEARING' && <span className="inline-flex items-center gap-1 text-xs text-muted-foreground"><CircleDashed size={13} />Operations can inquire</span>}
                        {(payment.status === 'COMPLETED' || payment.status === 'REJECTED') && <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">{payment.status === 'COMPLETED' ? <CircleCheck size={14} /> : <CircleAlert size={14} />}{payment.status === 'COMPLETED' ? 'Recorded' : 'Closed'}</span>}
                      </div></td>
                    </tr>
                  })}</tbody>
                </table>
              </div>
            )}
            <div className="mt-4 flex items-center justify-between border-t pt-4">
              <Button variant="outline" onClick={() => setPage((current) => Math.max(0, current - 1))} disabled={page === 0 || paymentsQuery.isFetching}><ArrowLeft size={15} />Previous</Button>
              <span className="text-xs text-muted-foreground">Page {page + 1} of {pageCount}</span>
              <Button variant="outline" onClick={() => setPage((current) => Math.min(pageCount - 1, current + 1))} disabled={page + 1 >= pageCount || paymentsQuery.isFetching}>Next<ArrowRight size={15} /></Button>
            </div>
          </CardContent>
        </Card>
      </>}
    </div>
  )
}
