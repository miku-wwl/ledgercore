import { useState, type FormEvent, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, AlertTriangle, ArrowRight, ClipboardList, Clock3, RefreshCw, Search, ShieldAlert, Wifi } from 'lucide-react'
import {
  ApiError,
  assignOrganizationReconciliationException,
  getOrganizationAuditHistory,
  getOrganizationOperationsMetrics,
  getOrganizationOutbox,
  getOrganizationReconciliationExceptions,
  getCorporatePayments,
  getOrganizations,
  inquireClearingOperation,
  resolveOrganizationReconciliationException,
  retryOrganizationOutboxEvent,
  runOrganizationReconciliation,
  searchOrganizationOperations,
  type AuditEvent,
  type CorporatePayment,
  type OperationSearchResult,
  type ReconciliationException,
  type ReconciliationRun,
} from '@/api/client'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatNzd } from '@/lib/money'

type TimelineItem = {
  id: string
  occurredAt: string
  kind: string
  title: string
  state: string
  correlationId?: string
}

function errorText(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) return 'Your Operations role is no longer active. Refresh your organization access or contact an administrator.'
  if (error instanceof ApiError && error.status === 404) return 'This organization or record is not available to your account.'
  return error instanceof Error ? error.message : 'The request failed. Refresh the console and review the saved backend state.'
}

function categoryResource(exception: ReconciliationException): string | undefined {
  try {
    const facts = JSON.parse(exception.expectedFactsJson ?? '{}') as { resourceId?: string }
    return facts.resourceId
  } catch {
    return undefined
  }
}

function statusTone(status?: string): string {
  if (status === 'DEAD' || status === 'UNKNOWN' || status === 'OPEN') return 'border-amber-300 bg-amber-50 text-amber-900'
  if (status === 'DELIVERED' || status === 'RESOLVED' || status === 'SETTLED') return 'border-emerald-200 bg-emerald-50 text-emerald-900'
  if (status === 'ASSIGNED' || status === 'PROCESSING') return 'border-blue-200 bg-blue-50 text-blue-900'
  return 'border-slate-200 bg-slate-50 text-slate-800'
}

export function OperationsPage({ customerId }: { customerId: string }) {
  const queryClient = useQueryClient()
  const organizationsQuery = useQuery({ queryKey: ['organizations'], queryFn: getOrganizations })
  const organizations = organizationsQuery.data ?? []
  const operatorOrganizations = organizations.filter((item) => item.roles?.includes('OPERATIONS'))
  const [selectedId, setSelectedId] = useState('')
  const [queryText, setQueryText] = useState('')
  const [resolutionNotes, setResolutionNotes] = useState<Record<string, string>>({})
  const [lastRun, setLastRun] = useState<{ organizationId: string; result: ReconciliationRun } | null>(null)
  const organization = operatorOrganizations.find((item) => item.id === selectedId) ?? operatorOrganizations[0]
  const organizationId = organization?.id
  const currentRun = lastRun && lastRun.organizationId === organizationId ? lastRun.result : null

  const metricsQuery = useQuery({ queryKey: ['operations', organizationId, 'metrics'], queryFn: () => getOrganizationOperationsMetrics(organizationId as string), enabled: Boolean(organizationId), refetchInterval: 10_000 })
  const outboxQuery = useQuery({ queryKey: ['operations', organizationId, 'outbox'], queryFn: () => getOrganizationOutbox(organizationId as string), enabled: Boolean(organizationId), refetchInterval: 10_000 })
  const exceptionsQuery = useQuery({ queryKey: ['operations', organizationId, 'exceptions'], queryFn: () => getOrganizationReconciliationExceptions(organizationId as string), enabled: Boolean(organizationId), refetchInterval: 10_000 })
  const auditQuery = useQuery({ queryKey: ['operations', organizationId, 'audit'], queryFn: () => getOrganizationAuditHistory(organizationId as string), enabled: Boolean(organizationId), refetchInterval: 10_000 })
  const paymentsQuery = useQuery({ queryKey: ['corporate-payments', organizationId, 'operations'], queryFn: () => getCorporatePayments(organizationId as string, 0, 50), enabled: Boolean(organizationId), refetchInterval: 5000 })

  async function refreshConsole() {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['operations', organizationId] }),
      queryClient.invalidateQueries({ queryKey: ['corporate-payments', organizationId] }),
    ])
  }

  const reconcileMutation = useMutation({
    mutationFn: () => runOrganizationReconciliation(organizationId as string),
    onSuccess: async (run) => { if (organizationId) setLastRun({ organizationId, result: run }); await refreshConsole() },
  })
  const inquiryMutation = useMutation({ mutationFn: inquireClearingOperation, onSuccess: refreshConsole })
  const retryMutation = useMutation({
    mutationFn: (eventId: string) => retryOrganizationOutboxEvent(organizationId as string, eventId),
    onSuccess: refreshConsole,
  })
  const assignMutation = useMutation({
    mutationFn: (exceptionId: string) => assignOrganizationReconciliationException(organizationId as string, exceptionId, customerId),
    onSuccess: refreshConsole,
  })
  const resolveMutation = useMutation({
    mutationFn: ({ exceptionId, note }: { exceptionId: string; note: string }) => resolveOrganizationReconciliationException(organizationId as string, exceptionId, note),
    onSuccess: refreshConsole,
  })
  const searchMutation = useMutation({
    mutationFn: () => searchOrganizationOperations(organizationId as string, queryText.trim()),
  })

  const outbox = outboxQuery.data ?? []
  const audit = auditQuery.data ?? []
  const exceptions = exceptionsQuery.data ?? []
  const payments = paymentsQuery.data?.items ?? []
  const pendingClearing = payments.filter((payment) => ['PENDING_CLEARING', 'UNKNOWN'].includes(payment.status))
  const metrics = metricsQuery.data

  const timeline: TimelineItem[] = [
    ...outbox.map((event) => ({
      id: event.id ?? `${event.eventType}-${event.createdAt}`,
      occurredAt: event.createdAt ?? '', kind: 'Outbox event', title: event.eventType ?? 'Event',
      state: event.status ?? 'UNKNOWN', correlationId: event.correlationId,
    })),
    ...audit.map((event: AuditEvent) => ({
      id: event.id, occurredAt: event.occurredAt, kind: 'Audit', title: event.action,
      state: event.resourceType, correlationId: event.correlationId,
    })),
    ...exceptions.map((exception) => ({
      id: exception.id ?? `${exception.category}-${exception.detectedAt}`,
      occurredAt: exception.detectedAt ?? '', kind: 'Reconciliation', title: exception.category ?? 'Exception',
      state: exception.status ?? 'OPEN',
    })),
  ]
      .filter((item) => item.occurredAt)
      .sort((left, right) => right.occurredAt.localeCompare(left.occurredAt))
      .slice(0, 30)

  function submitSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (queryText.trim().length >= 3 && organizationId) searchMutation.mutate()
  }

  function exceptionStillPresent(exception: ReconciliationException): boolean {
    const resourceId = categoryResource(exception)
    return Boolean(currentRun?.findings?.some((finding) => finding.category === exception.category && finding.resourceId === resourceId))
  }

  const firstError = metricsQuery.error ?? outboxQuery.error ?? exceptionsQuery.error ?? auditQuery.error ?? paymentsQuery.error
  const actionError = reconcileMutation.error ?? inquiryMutation.error ?? retryMutation.error ?? assignMutation.error ?? resolveMutation.error ?? searchMutation.error
  const searchResults: OperationSearchResult[] = searchMutation.data ?? []

  return (
    <div className="page-stack">
      <div className="heading-with-actions page-heading">
        <div><p className="eyebrow">Controlled operations</p><h1>Operations console</h1><p className="muted">Organization-scoped recovery, clearing state, reconciliation, audit history and correlation tracing.</p></div>
        <div className="heading-actions"><Button variant="outline" onClick={() => void refreshConsole()} disabled={metricsQuery.isFetching}><RefreshCw size={15} />Refresh</Button></div>
      </div>

      {organizationsQuery.isError && <Alert variant="destructive"><AlertTitle>Organization access could not be checked</AlertTitle><AlertDescription>{errorText(organizationsQuery.error)}</AlertDescription></Alert>}
      {firstError && <Alert variant="destructive"><ShieldAlert size={17} /><div><AlertTitle>Operations data is unavailable</AlertTitle><AlertDescription>{errorText(firstError)}</AlertDescription></div></Alert>}
      {actionError && <Alert variant="destructive"><AlertTitle>Action was not confirmed</AlertTitle><AlertDescription>{errorText(actionError)}</AlertDescription></Alert>}

      {operatorOrganizations.length > 1 && <div className="grid max-w-xl gap-2"><Label htmlFor="operations-organization">Organization</Label><select id="operations-organization" value={organization?.id ?? ''} onChange={(event) => { setSelectedId(event.target.value); setLastRun(null) }}>{operatorOrganizations.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}</select></div>}
      {!organizationsQuery.isLoading && operatorOrganizations.length === 0 && <Card className="empty-card"><CardContent><ShieldAlert size={24} /><h3>Operations access is required</h3><p>No organization currently grants this account the OPERATIONS role. Financial recovery actions remain protected by the backend.</p></CardContent></Card>}

      {organization && <>
        <Card className="border-emerald-200 bg-emerald-50/50"><CardContent className="flex flex-wrap items-center gap-2 p-4 text-sm"><Wifi size={16} className="text-emerald-800"/><strong>{organization.displayName}</strong><span className="text-muted-foreground">· Metrics are exact organization-scoped database counts.</span></CardContent></Card>

        <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5" aria-label="Operational metrics">
          <MetricCard label="Pending clearing" value={metrics?.pendingClearing} note="Payment obligations still open" icon={<Clock3 size={17}/>} loading={metricsQuery.isLoading}/>
          <MetricCard label="Unknown outcomes" value={metrics?.unknownClearing} note="Require simulator inquiry" icon={<AlertTriangle size={17}/>} loading={metricsQuery.isLoading}/>
          <MetricCard label="Queued events" value={metrics?.pendingEvents} note={metrics?.oldestPendingEventAt ? `Oldest since ${new Date(metrics.oldestPendingEventAt).toLocaleString()}` : 'No pending or processing events'} icon={<Activity size={17}/>} loading={metricsQuery.isLoading}/>
          <MetricCard label="Failed deliveries" value={metrics?.failedEvents} note="Dead-letter events" icon={<RefreshCw size={17}/>} loading={metricsQuery.isLoading}/>
          <MetricCard label="Open exceptions" value={metrics?.openExceptions} note="Open or assigned cases" icon={<ClipboardList size={17}/>} loading={metricsQuery.isLoading}/>
        </section>

        <Card>
          <CardHeader><CardDescription>Exact payment and correlation ID lookup</CardDescription><CardTitle>Search financial operations</CardTitle></CardHeader>
          <CardContent>
            <form className="flex flex-col gap-2 sm:flex-row" onSubmit={submitSearch}>
              <Label htmlFor="operations-search" className="sr-only">Payment, operation, event or correlation ID</Label>
              <Input id="operations-search" value={queryText} onChange={(event) => setQueryText(event.target.value)} placeholder="Payment, operation, event or correlation ID" minLength={3} maxLength={80} />
              <Button type="submit" disabled={queryText.trim().length < 3 || searchMutation.isPending}><Search size={15}/>{searchMutation.isPending ? 'Searching…' : 'Search'}</Button>
            </form>
            {searchMutation.isSuccess && <div className="mt-4 overflow-x-auto"><p className="mb-2 text-sm text-muted-foreground">{searchResults.length} matching records in this organization</p>{searchResults.length === 0 ? <p className="text-sm text-muted-foreground">No matching payment, event, audit entry or correlation ID.</p> : <table className="w-full min-w-[650px] text-left text-sm"><thead><tr className="border-b text-xs text-muted-foreground"><th className="px-2 py-2">Record</th><th className="px-2 py-2">State</th><th className="px-2 py-2">Description</th><th className="px-2 py-2">Correlation ID</th><th className="px-2 py-2">Time</th></tr></thead><tbody>{searchResults.map((result) => <tr className="border-b" key={`${result.resultType}-${result.resultId}`}><td className="px-2 py-3"><Badge variant="outline">{result.resultType.replaceAll('_', ' ')}</Badge><code className="mt-1 block max-w-48 truncate text-xs text-muted-foreground">{result.resultId}</code></td><td className="px-2 py-3">{result.externalStatus ? `${result.status} · ${result.externalStatus}` : result.status ?? '—'}</td><td className="px-2 py-3">{result.amountMinor !== undefined && result.amountMinor !== null ? formatNzd(`${Math.floor(result.amountMinor / 100)}.${String(result.amountMinor % 100).padStart(2, '0')}`) : result.description}</td><td className="px-2 py-3"><code className="block max-w-48 truncate text-xs">{result.correlationId ?? '—'}</code></td><td className="px-2 py-3 text-xs text-muted-foreground">{new Date(result.occurredAt).toLocaleString()}</td></tr>)}</tbody></table>}</div>}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3"><div><CardDescription>Pending, accepted and unknown outcomes remain distinct</CardDescription><CardTitle>Clearing instructions</CardTitle><p className="mt-2 text-sm text-muted-foreground">Recent 50 payments · inquiry requests the persisted simulator outcome.</p></div></CardHeader>
          <CardContent>{paymentsQuery.isLoading ? <p role="status" className="py-5 text-sm text-muted-foreground">Loading payment operations…</p> : pendingClearing.length === 0 ? <p className="py-5 text-sm text-muted-foreground">No pending clearing instructions in the recent payment page.</p> : <div className="overflow-x-auto"><table className="w-full min-w-[690px] text-left text-sm"><thead><tr className="border-b text-xs text-muted-foreground"><th className="px-2 py-3">Payment / operation</th><th className="px-2 py-3">Beneficiary</th><th className="px-2 py-3">Amount</th><th className="px-2 py-3">External outcome</th><th className="px-2 py-3">Recovery</th></tr></thead><tbody>{pendingClearing.map((payment: CorporatePayment) => <tr className="border-b" key={payment.id}><td className="px-2 py-3"><code className="block max-w-48 truncate text-xs">{payment.operationId}</code><small className="text-muted-foreground">{payment.status}</small></td><td className="px-2 py-3">{payment.beneficiaryName}</td><td className="px-2 py-3">{formatNzd(payment.amount)}</td><td className="px-2 py-3"><Badge variant="outline" className={statusTone(payment.externalStatus ?? 'PENDING')}>{payment.externalStatus ?? 'Not yet confirmed'}</Badge><p className="mt-1 max-w-48 text-xs text-muted-foreground">{payment.externalStatus === 'UNKNOWN' ? 'The request outcome is uncertain. Inquire before taking another action.' : payment.externalStatus === 'SETTLED' ? 'Settlement callback recorded.' : 'Accepted or pending does not mean settled.'}</p></td><td className="px-2 py-3">{payment.operationId && <Button size="sm" variant="outline" onClick={() => inquiryMutation.mutate(payment.operationId as string)} disabled={inquiryMutation.isPending || payment.externalStatus === 'SETTLED' || payment.externalStatus === 'REJECTED'}>Inquire<ArrowRight size={14}/></Button>}</td></tr>)}</tbody></table></div>}</CardContent>
        </Card>

        <div className="grid gap-4 xl:grid-cols-[1.15fr_.85fr]">
          <Card>
            <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3"><div><CardDescription>Safe recovery is limited to dead-letter events</CardDescription><CardTitle>Retry queue</CardTitle></div><Badge variant="outline">{outbox.filter((event) => event.status === 'DEAD').length} failed in recent 100</Badge></CardHeader>
            <CardContent>{outboxQuery.isLoading ? <p role="status" className="py-4 text-sm text-muted-foreground">Loading durable event status…</p> : outbox.length === 0 ? <p className="py-4 text-sm text-muted-foreground">No organization events are recorded.</p> : <div className="overflow-x-auto"><table className="w-full min-w-[570px] text-left text-sm"><thead><tr className="border-b text-xs text-muted-foreground"><th className="px-2 py-2">Event</th><th className="px-2 py-2">Status</th><th className="px-2 py-2">Attempts</th><th className="px-2 py-2">Trace</th><th className="px-2 py-2">Action</th></tr></thead><tbody>{outbox.slice(0, 30).map((event) => <tr className="border-b" key={event.id}><td className="px-2 py-3"><strong>{event.eventType}</strong><code className="mt-1 block max-w-44 truncate text-xs text-muted-foreground">{event.id}</code>{event.lastError && <small className="mt-1 block max-w-48 text-rose-800">{event.lastError}</small>}</td><td className="px-2 py-3"><Badge variant="outline" className={statusTone(event.status)}>{event.status}</Badge></td><td className="px-2 py-3">{event.attemptCount}</td><td className="px-2 py-3"><CorrelationButton value={event.correlationId} onSelect={(value) => setQueryText(value)}/></td><td className="px-2 py-3">{event.status === 'DEAD' ? <Button size="sm" variant="outline" onClick={() => event.id && retryMutation.mutate(event.id)} disabled={retryMutation.isPending}>Retry dead event</Button> : <span className="text-xs text-muted-foreground">No action</span>}</td></tr>)}</tbody></table></div>}</CardContent>
          </Card>

          <Card>
            <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3"><div><CardDescription>Fresh scan before a case can be resolved</CardDescription><CardTitle>Reconciliation exceptions</CardTitle></div><Button onClick={() => reconcileMutation.mutate()} disabled={reconcileMutation.isPending}><RefreshCw size={15}/>{reconcileMutation.isPending ? 'Scanning…' : 'Run reconciliation'}</Button></CardHeader>
            <CardContent className="grid gap-3">
              {currentRun && <Alert><AlertTitle>Latest scan: {currentRun.findingCount} finding(s)</AlertTitle><AlertDescription>Completed {currentRun.completedAt ? new Date(currentRun.completedAt).toLocaleString() : 'just now'}. Checked: {(currentRun.checkedDomains ?? []).join(', ') || 'none'}.</AlertDescription></Alert>}
              {exceptionsQuery.isLoading ? <p role="status" className="text-sm text-muted-foreground">Loading reconciliation cases…</p> : exceptions.length === 0 ? <p className="text-sm text-muted-foreground">No exceptions have been recorded.</p> : exceptions.slice(0, 20).map((exception) => {
                const stillPresent = exceptionStillPresent(exception)
                const canResolve = Boolean(currentRun) && !stillPresent && exception.status !== 'RESOLVED'
                return <div key={exception.id} className="rounded-lg border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2"><strong className="text-sm">{exception.category}</strong><Badge variant="outline" className={statusTone(exception.status)}>{exception.severity} · {exception.status}</Badge></div>
                  <p className="mt-2 text-xs text-muted-foreground">Exception <code>{exception.id}</code><br/>Resource <code>{categoryResource(exception) ?? exception.operationId ?? 'not specified'}</code></p>
                  {exception.status !== 'RESOLVED' && <div className="mt-3 grid gap-2">
                    <div className="flex flex-wrap gap-2"><Button size="sm" variant="outline" onClick={() => exception.id && assignMutation.mutate(exception.id)} disabled={assignMutation.isPending || exception.assignedToCustomerId === customerId}>Assign to me</Button>
                      <Button size="sm" variant="outline" onClick={() => exception.id && resolveMutation.mutate({ exceptionId: exception.id, note: resolutionNotes[exception.id] ?? '' })} disabled={!canResolve || !(resolutionNotes[exception.id] ?? '').trim() || resolveMutation.isPending}>Resolve after verified scan</Button></div>
                    <Label htmlFor={`resolve-note-${exception.id}`} className="sr-only">Resolution note for {exception.category}</Label><Input id={`resolve-note-${exception.id}`} value={resolutionNotes[exception.id] ?? ''} onChange={(event) => setResolutionNotes((current) => ({ ...current, [exception.id as string]: event.target.value }))} maxLength={500} placeholder="Document the completed correction" />
                    {!currentRun && <small className="text-amber-800">Run reconciliation after correcting the underlying issue before resolving this case.</small>}
                    {currentRun && stillPresent && <small className="text-amber-800">The latest scan still finds this issue; the case cannot be resolved yet.</small>}
                    {currentRun && !stillPresent && <small className="text-emerald-800">Latest scan did not find this category and resource. Add the correction record above.</small>}
                  </div>}
                  {exception.status === 'RESOLVED' && <p className="mt-2 text-xs text-muted-foreground">Resolved by an authorized operator on {exception.resolvedAt ? new Date(exception.resolvedAt).toLocaleString() : 'recorded time'}.</p>}
                </div>
              })}
            </CardContent>
          </Card>
        </div>

        <div className="grid gap-4 xl:grid-cols-2">
          <Card>
            <CardHeader><CardDescription>Durable operation, audit and exception records</CardDescription><CardTitle>Incident timeline</CardTitle></CardHeader>
            <CardContent>{timeline.length === 0 ? <p className="text-sm text-muted-foreground">No incidents or organization events are recorded yet.</p> : <ol className="grid gap-0">{timeline.map((item) => <li key={`${item.kind}-${item.id}`} className="grid grid-cols-[18px_1fr] gap-3 border-l pl-3 last:border-l-0"><span className="-ml-[21px] mt-1 h-3 w-3 rounded-full border-2 border-emerald-700 bg-white"/><div className="min-w-0 border-b pb-3"><div className="flex flex-wrap items-center gap-2"><Badge variant="outline">{item.kind}</Badge><Badge variant="outline" className={statusTone(item.state)}>{item.state}</Badge><time className="text-xs text-muted-foreground">{new Date(item.occurredAt).toLocaleString()}</time></div><p className="mt-1 truncate text-sm font-medium">{item.title}</p>{item.correlationId && <CorrelationButton value={item.correlationId} onSelect={(value) => setQueryText(value)}/>}</div></li>)}</ol>}</CardContent>
          </Card>
          <Card>
            <CardHeader><CardDescription>Recent organization audit entries</CardDescription><CardTitle>Audit history</CardTitle></CardHeader>
            <CardContent>{auditQuery.isLoading ? <p role="status" className="text-sm text-muted-foreground">Loading audit history…</p> : audit.length === 0 ? <p className="text-sm text-muted-foreground">No audit actions have been recorded.</p> : <div className="grid gap-3">{audit.slice(0, 20).map((event) => <div key={event.id} className="border-b pb-3 last:border-b-0"><div className="flex flex-wrap items-center justify-between gap-2"><strong className="text-sm">{event.action}</strong><time className="text-xs text-muted-foreground">{new Date(event.occurredAt).toLocaleString()}</time></div><p className="mt-1 text-xs text-muted-foreground">{event.resourceType} · {event.resourceId ?? 'resource not specified'} · actor {event.actorCustomerId ?? 'system'}</p><CorrelationButton value={event.correlationId} onSelect={(value) => setQueryText(value)}/></div>)}</div>}</CardContent>
          </Card>
        </div>
        <p className="text-xs text-muted-foreground">The console never forces a payment to succeed. Clearing inquiries read the simulator’s saved state; event retries are offered only for dead-letter records; exception resolution requires a fresh scan that no longer finds the issue.</p>
      </>}
    </div>
  )
}

function MetricCard({ label, value, note, icon, loading }: { label: string; value?: number; note: string; icon: ReactNode; loading: boolean }) {
  return <Card><CardContent className="min-h-28 p-4"><div className="flex items-center justify-between gap-2 text-sm text-muted-foreground"><span>{label}</span>{icon}</div><div className="mt-3 text-2xl font-semibold text-emerald-950" aria-live="polite">{loading ? '…' : value ?? '—'}</div><p className="mt-1 text-xs text-muted-foreground">{note}</p></CardContent></Card>
}

function CorrelationButton({ value, onSelect }: { value?: string; onSelect: (value: string) => void }) {
  if (!value) return <span className="text-xs text-muted-foreground">No correlation ID</span>
  return <button type="button" className="mt-1 inline-flex max-w-full items-center gap-1 text-left text-xs text-emerald-800 underline underline-offset-2" onClick={() => onSelect(value)} title="Use this ID in operation search"><code className="max-w-48 truncate">{value}</code><ArrowRight size={12}/></button>
}
