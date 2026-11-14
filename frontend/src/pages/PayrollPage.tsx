import { useState, type ChangeEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  addPayrollBatchItems,
  approvePayrollBatch,
  createPayrollBatch,
  executePayrollBatch,
  getOrganizationAccounts,
  getPayrollBatch,
  getPayrollBatches,
  getPayrollItems,
  getOrganizations,
  rejectPayrollBatch,
  submitPayrollBatch,
  type Organization,
  type PayrollBatch,
  type PayrollItemInput,
} from '@/api/client'

const AMOUNT_PATTERN = /^(?:0|[1-9][0-9]*)\.[0-9]{2}$/
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const EMPTY_ORGANIZATIONS: Organization[] = []

function parseCsv(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let quoted = false
  for (let index = 0; index < text.length; index++) {
    const character = text[index]
    if (character === '"') {
      if (quoted && text[index + 1] === '"') {
        field += '"'
        index++
      } else if (quoted) {
        quoted = false
      } else if (field.length === 0) {
        quoted = true
      } else {
        throw new Error('A quote must wrap an entire CSV field.')
      }
    } else if (character === ',' && !quoted) {
      row.push(field)
      field = ''
    } else if ((character === '\n' || character === '\r') && !quoted) {
      if (character === '\r' && text[index + 1] === '\n') index++
      row.push(field)
      if (row.some((value) => value.trim())) rows.push(row)
      row = []
      field = ''
    } else {
      field += character
    }
  }
  if (quoted) throw new Error('The CSV file contains an unclosed quoted field.')
  row.push(field)
  if (row.some((value) => value.trim())) rows.push(row)
  return rows
}

function parsePayrollCsv(text: string): PayrollItemInput[] {
  const rows = parseCsv(text.replace(/^\uFEFF/, ''))
  if (rows.length === 0) throw new Error('The CSV file is empty.')
  const first = rows[0].map((value) => value.trim().toLowerCase())
  const hasHeader = first[0] === 'employeereference' || first[0] === 'employee reference'
  const dataRows = hasHeader ? rows.slice(1) : rows
  if (dataRows.length === 0 || dataRows.length > 1_000) {
    throw new Error('Import between 1 and 1,000 payroll rows at a time.')
  }
  return dataRows.map((row, index) => {
    if (row.length !== 4) throw new Error(`Row ${index + (hasHeader ? 2 : 1)} must contain four columns.`)
    const [employeeReference, employeeName, payeeAccountId, amount] = row.map((value) => value.trim())
    if (!employeeReference || employeeReference.length > 80) {
      throw new Error(`Row ${index + (hasHeader ? 2 : 1)} needs an employee reference (1–80 characters).`)
    }
    if (!employeeName || employeeName.length > 120) {
      throw new Error(`Row ${index + (hasHeader ? 2 : 1)} needs an employee name (1–120 characters).`)
    }
    if (!UUID_PATTERN.test(payeeAccountId)) {
      throw new Error(`Row ${index + (hasHeader ? 2 : 1)} has an invalid payee account UUID.`)
    }
    if (!AMOUNT_PATTERN.test(amount) || amount === '0.00') {
      throw new Error(`Row ${index + (hasHeader ? 2 : 1)} needs a positive NZD amount with two decimal places.`)
    }
    return { employeeReference, employeeName, payeeAccountId, amount }
  })
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  if (status === 'COMPLETED') return 'default'
  if (status === 'FAILED' || status === 'PARTIALLY_COMPLETED' || status === 'REJECTED') return 'destructive'
  if (status === 'APPROVED' || status === 'PROCESSING') return 'secondary'
  return 'outline'
}

export function PayrollPage() {
  const queryClient = useQueryClient()
  const organizationsQuery = useQuery({ queryKey: ['organizations'], queryFn: getOrganizations })
  const organizations = organizationsQuery.data ?? EMPTY_ORGANIZATIONS
  const [organizationId, setOrganizationId] = useState('')
  const [batchId, setBatchId] = useState('')
  const [batchName, setBatchName] = useState('')
  const [payerAccountId, setPayerAccountId] = useState('')
  const [importItems, setImportItems] = useState<PayrollItemInput[]>([])
  const [importName, setImportName] = useState('')
  const [importError, setImportError] = useState('')
  const [rejectionReason, setRejectionReason] = useState('')
  const [actionError, setActionError] = useState('')

  const selectedOrganizationId = organizationId || organizations[0]?.id || ''
  const organization = organizations.find((item) => item.id === selectedOrganizationId)
  const roles = organization?.roles ?? []
  const canMake = roles.includes('PAYROLL_MAKER')
  const canApprove = roles.includes('PAYROLL_APPROVER')
  const canExecute = canApprove || roles.includes('OPERATIONS')

  const accountsQuery = useQuery({
    queryKey: ['organizations', selectedOrganizationId, 'accounts'],
    queryFn: () => getOrganizationAccounts(selectedOrganizationId),
    enabled: Boolean(selectedOrganizationId),
  })
  const batchesQuery = useQuery({
    queryKey: ['payroll-batches', selectedOrganizationId],
    queryFn: () => getPayrollBatches(selectedOrganizationId),
    enabled: Boolean(selectedOrganizationId),
  })
  const batches = batchesQuery.data ?? []
  const selectedBatch = batches.find((batch) => batch.id === batchId) ?? (batchId ? undefined : batches[0])
  const activeBatchId = batchId || batches[0]?.id || ''

  const batchQuery = useQuery({
    queryKey: ['payroll-batch', activeBatchId],
    queryFn: () => getPayrollBatch(activeBatchId),
    enabled: Boolean(activeBatchId),
  })
  const itemsQuery = useQuery({
    queryKey: ['payroll-items', activeBatchId],
    queryFn: () => getPayrollItems(activeBatchId),
    enabled: Boolean(activeBatchId),
  })
  const batch: PayrollBatch | undefined = batchQuery.data ?? selectedBatch
  const accountOptions = accountsQuery.data ?? []

  async function refreshBatch(updated?: PayrollBatch) {
    if (updated) setBatchId(updated.id)
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['payroll-batches', selectedOrganizationId] }),
      queryClient.invalidateQueries({ queryKey: ['payroll-batch', activeBatchId] }),
      queryClient.invalidateQueries({ queryKey: ['payroll-items', activeBatchId] }),
    ])
  }

  const createMutation = useMutation({
    mutationFn: () => createPayrollBatch(selectedOrganizationId, { name: batchName.trim(), payerAccountId }),
    onSuccess: async (created) => {
      setBatchName('')
      setBatchId(created.id)
      setActionError('')
      await queryClient.invalidateQueries({ queryKey: ['payroll-batches', selectedOrganizationId] })
    },
  })
  const addItemsMutation = useMutation({
    mutationFn: () => addPayrollBatchItems(activeBatchId, importItems),
    onSuccess: async (updated) => {
      setImportItems([])
      setImportName('')
      setImportError('')
      await refreshBatch(updated)
    },
  })
  const submitMutation = useMutation({
    mutationFn: () => submitPayrollBatch(activeBatchId),
    onSuccess: refreshBatch,
  })
  const approveMutation = useMutation({
    mutationFn: () => approvePayrollBatch(activeBatchId),
    onSuccess: refreshBatch,
  })
  const rejectMutation = useMutation({
    mutationFn: () => rejectPayrollBatch(activeBatchId, rejectionReason.trim()),
    onSuccess: async (updated) => {
      setRejectionReason('')
      await refreshBatch(updated)
    },
  })
  const executeMutation = useMutation({
    mutationFn: () => executePayrollBatch(activeBatchId, 1_000),
    onSuccess: async (updated) => {
      setActionError('')
      await refreshBatch(updated)
    },
    onError: () => setActionError('The connection ended before progress was confirmed. Refresh the saved batch status before retrying.'),
  })

  async function readImport(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    setImportError('')
    setImportItems([])
    setImportName('')
    if (!file) return
    if (file.size > 2_000_000) {
      setImportError('Choose a CSV file smaller than 2 MB.')
      return
    }
    try {
      const parsed = parsePayrollCsv(await file.text())
      const references = new Set<string>()
      for (const item of parsed) {
        const key = item.employeeReference.toLocaleLowerCase()
        if (references.has(key)) throw new Error(`Employee reference ${item.employeeReference} appears more than once.`)
        references.add(key)
      }
      setImportItems(parsed)
      setImportName(file.name)
    } catch (error) {
      setImportError(errorMessage(error, 'The CSV file could not be read.'))
    } finally {
      event.target.value = ''
    }
  }

  const isDraft = batch?.status === 'DRAFT'
  const percentComplete = batch && batch.itemCount > 0
    ? Math.round(((batch.succeededCount + batch.failedCount) / batch.itemCount) * 100)
    : 0

  return (
    <div className="page-stack">
      <div className="page-heading">
        <p className="eyebrow">Friday Payday · Enterprise banking</p>
        <h1>Payroll</h1>
        <p className="muted">Prepare exact NZD payments, obtain independent approval, and track each durable result.</p>
      </div>

      {organizationsQuery.isError && <p role="alert" className="text-sm text-red-700">Organizations could not load.</p>}
      {organizations.length > 1 && (
        <div className="grid max-w-md gap-2">
          <Label htmlFor="payroll-organization">Organization</Label>
          <select id="payroll-organization" value={selectedOrganizationId}
            onChange={(event) => { setOrganizationId(event.target.value); setBatchId('') }}>
            {organizations.map((item) => <option key={item.id} value={item.id}>{item.displayName}</option>)}
          </select>
        </div>
      )}

      {!organization && !organizationsQuery.isLoading ? (
        <Card><CardContent className="p-6"><h2 className="font-semibold">Organization membership required</h2>
          <p className="mt-2 text-sm text-muted-foreground">Ask an organization administrator to add your customer account.</p></CardContent></Card>
      ) : organization ? (
        <>
          <div className="flex flex-wrap items-center gap-2" aria-label="Your payroll roles">
            <span className="text-sm font-medium">{organization.displayName}</span>
            {roles.map((role) => <Badge key={role} variant="secondary">{role.replaceAll('_', ' ')}</Badge>)}
          </div>

          {canMake && (
            <Card>
              <CardHeader><CardTitle>Create a payroll draft</CardTitle>
                <CardDescription>The payer must be an open corporate account. Each payee must be a personal customer account.</CardDescription></CardHeader>
              <CardContent>
                <form className="grid gap-3 md:grid-cols-[1fr_1fr_auto]" onSubmit={(event) => { event.preventDefault(); createMutation.mutate() }}>
                  <div className="grid gap-2"><Label htmlFor="payroll-batch-name">Batch name</Label>
                    <Input id="payroll-batch-name" value={batchName} maxLength={120} required
                      onChange={(event) => setBatchName(event.target.value)} placeholder="Friday 27 November payroll" /></div>
                  <div className="grid gap-2"><Label htmlFor="payroll-payer">Payer account</Label>
                    <select id="payroll-payer" value={payerAccountId} required
                      onChange={(event) => setPayerAccountId(event.target.value)}>
                      <option value="">Choose an organization account</option>
                      {accountOptions.map((account) => <option key={account.id} value={account.id}>
                        {account.displayName} · NZ${account.balance}
                      </option>)}
                    </select></div>
                  <Button className="self-end" type="submit"
                    disabled={!batchName.trim() || !payerAccountId || createMutation.isPending}>
                    {createMutation.isPending ? 'Creating…' : 'Create draft batch'}
                  </Button>
                </form>
                {createMutation.isError && <p role="alert" className="mt-3 text-sm text-red-700">{errorMessage(createMutation.error, 'The draft could not be created.')}</p>}
              </CardContent>
            </Card>
          )}

          {batches.length > 0 && (
            <div className="grid max-w-2xl gap-2">
              <Label htmlFor="payroll-batch-choice">Payroll batch</Label>
              <select id="payroll-batch-choice" value={activeBatchId}
                onChange={(event) => setBatchId(event.target.value)}>
                {batches.map((item) => <option key={item.id} value={item.id}>
                  {item.name} · {item.status} · {item.itemCount} items
                </option>)}
              </select>
            </div>
          )}

          {!batchesQuery.isLoading && batches.length === 0 && !canMake && (
            <Card><CardContent className="p-6"><h2 className="font-semibold">No payroll batches yet</h2>
              <p className="mt-2 text-sm text-muted-foreground">A Payroll Maker can prepare the first batch.</p></CardContent></Card>
          )}

          {batch && (
            <>
              {isDraft && canMake && (
                <Card>
                  <CardHeader><CardTitle>Import payroll items</CardTitle>
                    <CardDescription>Use one row per employee. Amounts are exact NZD decimals and account IDs identify personal payee accounts.</CardDescription></CardHeader>
                  <CardContent className="grid gap-4">
                    <div className="rounded-md bg-muted/50 p-3 text-xs">
                      <p className="font-medium">CSV columns</p>
                      <code className="mt-1 block break-all">employeeReference,employeeName,payeeAccountId,amount</code>
                      <p className="mt-1 text-muted-foreground">Example: EMP-0001,Alex Employee,00000000-0000-4000-8000-000000000001,1250.00</p>
                    </div>
                    <div className="grid gap-2">
                      <Label htmlFor="payroll-csv">Payroll CSV</Label>
                      <Input id="payroll-csv" type="file" accept=".csv,text/csv" onChange={readImport} />
                    </div>
                    {importError && <p role="alert" className="text-sm text-red-700">{importError}</p>}
                    {importItems.length > 0 && (
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <p role="status" className="text-sm">{importItems.length} items ready from {importName}.</p>
                        <Button onClick={() => addItemsMutation.mutate()} disabled={addItemsMutation.isPending}>
                          {addItemsMutation.isPending ? 'Adding items…' : 'Add imported items'}
                        </Button>
                      </div>
                    )}
                    {addItemsMutation.isError && <p role="alert" className="text-sm text-red-700">{errorMessage(addItemsMutation.error, 'Payroll items could not be added.')}</p>}
                  </CardContent>
                </Card>
              )}

              <Card>
                <CardHeader>
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div><CardDescription>Payroll batch</CardDescription><CardTitle className="mt-1">{batch.name}</CardTitle></div>
                    <Badge variant={statusVariant(batch.status)}>{batch.status.replaceAll('_', ' ')}</Badge>
                  </div>
                  <CardDescription>Batch {batch.id}</CardDescription>
                </CardHeader>
                <CardContent className="grid gap-5">
                  <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                    <Summary label="Items" value={String(batch.itemCount)} />
                    <Summary label="Payroll total" value={'NZ$' + batch.totalAmount} />
                    <Summary label="Completed" value={String(batch.succeededCount)} />
                    <Summary label="Failed / pending" value={`${batch.failedCount} / ${batch.pendingCount}`} />
                  </div>
                  {batch.itemCount > 0 && (
                    <div className="grid gap-2" aria-label="Payroll execution progress">
                      <div className="flex justify-between text-xs"><span>Accounted for</span><span>{percentComplete}%</span></div>
                      <div className="h-2 overflow-hidden rounded-full bg-muted"><div className="h-full rounded-full bg-emerald-700 transition-all"
                        style={{ width: `${percentComplete}%` }} /></div>
                    </div>
                  )}

                  {actionError && <div role="alert" className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950">
                    <p>{actionError}</p>
                    <Button className="mt-2" size="sm" variant="outline"
                      onClick={() => { void queryClient.invalidateQueries({ queryKey: ['payroll-batch', activeBatchId] }); void queryClient.invalidateQueries({ queryKey: ['payroll-items', activeBatchId] }) }}>
                      Refresh payroll status
                    </Button>
                  </div>}

                  <div className="flex flex-wrap gap-2">
                    {isDraft && canMake && batch.itemCount > 0 && <Button onClick={() => submitMutation.mutate()} disabled={submitMutation.isPending}>
                      {submitMutation.isPending ? 'Submitting…' : 'Submit for approval'}
                    </Button>}
                    {batch.status === 'SUBMITTED' && canApprove && <>
                      <Button onClick={() => approveMutation.mutate()} disabled={approveMutation.isPending}>
                        {approveMutation.isPending ? 'Approving…' : 'Approve batch'}
                      </Button>
                      <Input className="max-w-sm" aria-label="Rejection reason" value={rejectionReason}
                        onChange={(event) => setRejectionReason(event.target.value)} placeholder="Reason if rejecting" />
                      <Button variant="outline" onClick={() => rejectMutation.mutate()}
                        disabled={!rejectionReason.trim() || rejectMutation.isPending}>
                        Reject batch
                      </Button>
                    </>}
                    {(batch.status === 'APPROVED' || batch.status === 'PROCESSING') && canExecute && (
                      <Button onClick={() => { setActionError(''); executeMutation.mutate() }} disabled={executeMutation.isPending}>
                        {executeMutation.isPending ? 'Processing payroll…' : batch.status === 'PROCESSING' ? 'Resume payroll' : 'Run payroll'}
                      </Button>
                    )}
                    {(submitMutation.isError || approveMutation.isError || rejectMutation.isError) && (
                      <p role="alert" className="w-full text-sm text-red-700">
                        {errorMessage(submitMutation.error ?? approveMutation.error ?? rejectMutation.error,
                          'The payroll action could not be completed.')}
                      </p>
                    )}
                  </div>

                  {batch.status === 'REJECTED' && <p className="text-sm text-red-700">Rejected: {batch.rejectionReason}</p>}
                  {batch.approvedContentDigest && <p className="text-xs text-muted-foreground">Approved content digest: <code>{batch.approvedContentDigest}</code></p>}
                  {batch.status === 'PARTIALLY_COMPLETED' && <p role="status" className="text-sm">Completed payments remain posted. Failed items require a new approved batch after their business issue is corrected.</p>}
                  {batch.status === 'FAILED' && <p role="status" className="text-sm">No payroll items completed. Review the failed item details before preparing a new batch.</p>}
                </CardContent>
              </Card>

              <Card>
                <CardHeader><CardTitle>Item results</CardTitle>
                  <CardDescription>Committed transfers show their durable receipt ID. Failed business outcomes are not retried automatically.</CardDescription></CardHeader>
                <CardContent>
                  {itemsQuery.isLoading ? <p className="text-sm text-muted-foreground">Loading payroll items…</p> : (
                    <div className="max-h-[34rem] overflow-auto rounded-md border">
                      <table className="w-full min-w-[780px] text-left text-sm">
                        <thead className="sticky top-0 bg-muted/80 text-xs">
                          <tr><th className="p-3">Employee</th><th className="p-3">Reference</th><th className="p-3">Amount</th>
                            <th className="p-3">Result</th><th className="p-3">Receipt / reason</th></tr>
                        </thead>
                        <tbody>
                          {(itemsQuery.data ?? []).map((item) => (
                            <tr key={item.id} className="border-t">
                              <td className="p-3"><span className="font-medium">{item.employeeName}</span><span className="block text-xs text-muted-foreground">Account · {item.payeeAccountId.slice(-8)}</span></td>
                              <td className="p-3">{item.employeeReference}</td>
                              <td className="p-3">NZ${item.amount}</td>
                              <td className="p-3"><Badge variant={item.status === 'FAILED' ? 'destructive' : item.status === 'SUCCEEDED' ? 'secondary' : 'outline'}>{item.status}</Badge></td>
                              <td className="p-3 text-xs">{item.transferId ? <code>{item.transferId}</code> : item.errorCode ? <span>{item.errorCode}: {item.errorMessage}</span> : '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                      {(itemsQuery.data?.length ?? 0) === 0 && <p className="p-4 text-sm text-muted-foreground">No items in this draft.</p>}
                    </div>
                  )}
                  {itemsQuery.isError && <p role="alert" className="mt-3 text-sm text-red-700">Payroll item results could not load.</p>}
                </CardContent>
              </Card>
            </>
          )}
        </>
      ) : null}
    </div>
  )
}

function Summary({ label, value }: { label: string; value: string }) {
  return <div className="rounded-md border p-3"><p className="text-xs text-muted-foreground">{label}</p><p className="mt-1 font-semibold tabular-nums">{value}</p></div>
}
