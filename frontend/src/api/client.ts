import createClient from 'openapi-fetch'
import type { components, paths } from './schema'

const client = createClient<paths>({ baseUrl: '', credentials: 'include' })

export type Customer = Required<components['schemas']['CustomerProfile']>
export type Account = Required<components['schemas']['AccountView']>
export type Transaction = Required<components['schemas']['TransactionView']>
export type TransactionPage = Omit<Required<components['schemas']['TransactionPage']>, 'items'> & { items: Transaction[] }
export type TransactionDetail = Required<components['schemas']['TransactionDetail']>
export type Reconciliation = Required<components['schemas']['ReconciliationResult']>
export type TransferReceipt = Required<components['schemas']['TransferReceipt']>
export type TransferIntent = components['schemas']['TransferRequest']

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly correlationId?: string

  constructor(
    status: number,
    message: string,
    code?: string,
    correlationId?: string,
  ) {
    super(message)
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.name = 'ApiError'
  }
}

function asProblem(value: unknown): { title?: string; detail?: string; code?: string; correlationId?: string } {
  return value && typeof value === 'object'
    ? value as { title?: string; detail?: string; code?: string; correlationId?: string }
    : {}
}

function ensureOk(response: Response, error: unknown): void {
  if (response.ok) return
  const problem = asProblem(error)
  throw new ApiError(
    response.status,
    problem.detail || problem.title || `Request failed (${response.status})`,
    problem.code,
    problem.correlationId,
  )
}

function required<T>(value: T | undefined, description: string): T {
  if (value === undefined) throw new Error(`${description} returned an empty response.`)
  return value
}

async function csrfToken(): Promise<string> {
  const { data, error, response } = await client.GET('/api/auth/csrf')
  ensureOk(response, error)
  return required(data?.token, 'CSRF token request')
}

export async function getCurrentCustomer(): Promise<Customer | null> {
  const { data, error, response } = await client.GET('/api/auth/me')
  if (response.status === 401) return null
  ensureOk(response, error)
  return required(data as Customer | undefined, 'Current customer request')
}

export async function signIn(input: { email: string; password: string }): Promise<Customer> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/auth/login', {
    body: input,
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  await csrfToken()
  return required(data as Customer | undefined, 'Sign in')
}

export async function register(input: { email: string; password: string; displayName: string }): Promise<Customer> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/auth/register', {
    body: input,
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  await csrfToken()
  return required(data as Customer | undefined, 'Registration')
}

export async function signOut(): Promise<void> {
  const token = await csrfToken()
  const { error, response } = await client.POST('/api/auth/logout', {
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
}

export async function getAccounts(): Promise<Account[]> {
  const { data, error, response } = await client.GET('/api/accounts')
  ensureOk(response, error)
  return (data as Account[] | undefined) ?? []
}

export async function createAccount(displayName: string): Promise<Account> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/accounts', {
    body: { displayName },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as Account | undefined, 'Account creation')
}

export async function getAccount(accountId: string): Promise<Account> {
  const { data, error, response } = await client.GET('/api/accounts/{accountId}', {
    params: { path: { accountId } },
  })
  ensureOk(response, error)
  return required(data as Account | undefined, 'Account request')
}

export async function getTransactions(accountId: string, cursor?: string): Promise<TransactionPage> {
  const { data, error, response } = await client.GET('/api/accounts/{accountId}/transactions', {
    params: { path: { accountId }, query: { limit: 3, ...(cursor ? { cursor } : {}) } },
  })
  ensureOk(response, error)
  return required(data as unknown as TransactionPage | undefined, 'Transaction history')
}

export async function getTransactionDetail(accountId: string, transferId: string): Promise<TransactionDetail> {
  const { data, error, response } = await client.GET('/api/accounts/{accountId}/transactions/{transferId}', {
    params: { path: { accountId, transferId } },
  })
  ensureOk(response, error)
  return required(data as TransactionDetail | undefined, 'Transaction detail')
}

export async function getReconciliation(accountId: string): Promise<Reconciliation> {
  const { data, error, response } = await client.GET('/api/accounts/{accountId}/reconciliation', {
    params: { path: { accountId } },
  })
  ensureOk(response, error)
  return required(data as Reconciliation | undefined, 'Reconciliation')
}

export async function submitTransfer(intent: TransferIntent, idempotencyKey: string): Promise<TransferReceipt> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/transfers', {
    params: { header: { 'Idempotency-Key': idempotencyKey } },
    body: intent,
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as TransferReceipt | undefined, 'Transfer')
}

export type OrganizationRole = components['schemas']['AddMemberRequest']['roles'][number]
export type Organization = Required<components['schemas']['OrganizationView']>
export type OrganizationMember = Required<components['schemas']['MemberView']>
export type PayrollBatch = Required<components['schemas']['BatchView']>
export type PayrollItem = Required<components['schemas']['ItemView']>
export type PayrollItemInput = components['schemas']['PayrollItemRequest']
export type TransferAccount = Account & { canDebit: boolean }
export type CorporatePayment = Required<components['schemas']['PaymentView']>
export type CorporatePaymentPage = Omit<Required<components['schemas']['PaymentPage']>, 'items'> & {
  items: CorporatePayment[]
}
export type OutboxEvent = Required<components['schemas']['OutboxStatusView']>
export type ReconciliationException = Required<components['schemas']['ReconciliationExceptionView']>
export type ReconciliationRun = Required<components['schemas']['ReconciliationRunResult']>
export type OperationsMetrics = Required<components['schemas']['OperationsMetrics']>
export type AuditEvent = Required<components['schemas']['AuditEventView']>
export type OperationSearchResult = Required<components['schemas']['OperationSearchResult']>

export async function getCorporatePayments(organizationId: string, page = 0, size = 10): Promise<CorporatePaymentPage> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/payments', {
    params: { path: { organizationId }, query: { page, size } },
  })
  ensureOk(response, error)
  return required(data as CorporatePaymentPage | undefined, 'Corporate payment list')
}

export async function createCorporatePayment(organizationId: string, input: {
  sourceAccountId: string
  beneficiaryName: string
  beneficiaryAccountReference: string
  beneficiaryRoutingCode: string
  amount: string
}): Promise<CorporatePayment> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations/{organizationId}/payments', {
    params: { path: { organizationId } }, body: input, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as CorporatePayment | undefined, 'Corporate payment creation')
}

export async function submitCorporatePayment(paymentId: string): Promise<CorporatePayment> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payments/{paymentId}/submit', {
    params: { path: { paymentId } }, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as CorporatePayment | undefined, 'Corporate payment submission')
}

export async function approveCorporatePayment(paymentId: string): Promise<CorporatePayment> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payments/{paymentId}/approve', {
    params: { path: { paymentId } }, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as CorporatePayment | undefined, 'Corporate payment approval')
}

export async function rejectCorporatePayment(paymentId: string, reason: string): Promise<CorporatePayment> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payments/{paymentId}/reject', {
    params: { path: { paymentId } }, body: { reason }, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as CorporatePayment | undefined, 'Corporate payment rejection')
}

export async function getOrganizationOperationsMetrics(organizationId: string): Promise<OperationsMetrics> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/operations/metrics', {
    params: { path: { organizationId } },
  })
  ensureOk(response, error)
  return required(data as OperationsMetrics | undefined, 'Operations metrics')
}

export async function getOrganizationOutbox(organizationId: string): Promise<OutboxEvent[]> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/operations/outbox', {
    params: { path: { organizationId }, query: { limit: 100 } },
  })
  ensureOk(response, error)
  return (data as OutboxEvent[] | undefined) ?? []
}

export async function retryOrganizationOutboxEvent(organizationId: string, eventId: string): Promise<void> {
  const token = await csrfToken()
  const { error, response } = await client.POST('/api/organizations/{organizationId}/operations/outbox/{eventId}/retry', {
    params: { path: { organizationId, eventId } }, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
}

export async function runOrganizationReconciliation(organizationId: string): Promise<ReconciliationRun> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations/{organizationId}/operations/reconciliation/runs', {
    params: { path: { organizationId }, query: { limit: 500 } }, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as ReconciliationRun | undefined, 'Organization reconciliation')
}

export async function getOrganizationReconciliationExceptions(organizationId: string): Promise<ReconciliationException[]> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/operations/reconciliation/exceptions', {
    params: { path: { organizationId }, query: { limit: 100 } },
  })
  ensureOk(response, error)
  return (data as ReconciliationException[] | undefined) ?? []
}

export async function assignOrganizationReconciliationException(
  organizationId: string,
  exceptionId: string,
  assignedToCustomerId: string,
): Promise<ReconciliationException> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations/{organizationId}/operations/reconciliation/exceptions/{exceptionId}/assign', {
    params: { path: { organizationId, exceptionId } }, body: { assignedToCustomerId },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as ReconciliationException | undefined, 'Assign reconciliation exception')
}

export async function resolveOrganizationReconciliationException(
  organizationId: string,
  exceptionId: string,
  resolution: string,
): Promise<ReconciliationException> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations/{organizationId}/operations/reconciliation/exceptions/{exceptionId}/resolve', {
    params: { path: { organizationId, exceptionId } }, body: { resolution },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as ReconciliationException | undefined, 'Resolve reconciliation exception')
}

export async function inquireClearingOperation(operationId: string): Promise<components['schemas']['OperationResponse']> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/operations/clearing/{operationId}/inquiry', {
    params: { path: { operationId } }, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data, 'Clearing inquiry')
}

export async function getOrganizationAuditHistory(organizationId: string): Promise<AuditEvent[]> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/operations/audit', {
    params: { path: { organizationId }, query: { limit: 100 } },
  })
  ensureOk(response, error)
  return (data as AuditEvent[] | undefined) ?? []
}

export async function searchOrganizationOperations(organizationId: string, query: string): Promise<OperationSearchResult[]> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/operations/search', {
    params: { path: { organizationId }, query: { query } },
  })
  ensureOk(response, error)
  return (data as OperationSearchResult[] | undefined) ?? []
}

export async function getOrganizations(): Promise<Organization[]> {
  const { data, error, response } = await client.GET('/api/organizations')
  ensureOk(response, error)
  return (data as Organization[] | undefined) ?? []
}

export async function createOrganization(displayName: string): Promise<Organization> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations', {
    body: { displayName },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as Organization | undefined, 'Create organization')
}

export async function getOrganizationAccounts(organizationId: string): Promise<Account[]> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/accounts', {
    params: { path: { organizationId } },
  })
  ensureOk(response, error)
  return (data as Account[] | undefined) ?? []
}

export async function createOrganizationAccount(organizationId: string, displayName: string): Promise<Account> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations/{organizationId}/accounts', {
    params: { path: { organizationId } },
    body: { displayName },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as Account | undefined, 'Create corporate account')
}

export async function getOrganizationMembers(organizationId: string): Promise<OrganizationMember[]> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/members', {
    params: { path: { organizationId } },
  })
  ensureOk(response, error)
  return (data as OrganizationMember[] | undefined) ?? []
}

export async function addOrganizationMember(
  organizationId: string,
  input: { email: string; roles: OrganizationRole[] },
): Promise<OrganizationMember> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations/{organizationId}/members', {
    params: { path: { organizationId } },
    body: input,
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as OrganizationMember | undefined, 'Add organization member')
}

export async function revokeOrganizationMemberRole(
  organizationId: string,
  memberCustomerId: string,
  role: OrganizationRole,
): Promise<OrganizationMember> {
  const token = await csrfToken()
  const { data, error, response } = await client.DELETE('/api/organizations/{organizationId}/members/{memberCustomerId}/roles/{role}', {
    params: { path: { organizationId, memberCustomerId, role } }, headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as OrganizationMember | undefined, 'Revoke organization role')
}

export async function getPayrollBatches(organizationId: string): Promise<PayrollBatch[]> {
  const { data, error, response } = await client.GET('/api/organizations/{organizationId}/payroll/batches', {
    params: { path: { organizationId } },
  })
  ensureOk(response, error)
  return (data as PayrollBatch[] | undefined) ?? []
}

export async function createPayrollBatch(
  organizationId: string,
  input: { name: string; payerAccountId: string },
): Promise<PayrollBatch> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/organizations/{organizationId}/payroll/batches', {
    params: { path: { organizationId } },
    body: input,
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as PayrollBatch | undefined, 'Create payroll batch')
}

export async function getPayrollBatch(batchId: string): Promise<PayrollBatch> {
  const { data, error, response } = await client.GET('/api/payroll/batches/{batchId}', {
    params: { path: { batchId } },
  })
  ensureOk(response, error)
  return required(data as PayrollBatch | undefined, 'Payroll batch')
}

export async function getPayrollItems(batchId: string): Promise<PayrollItem[]> {
  const { data, error, response } = await client.GET('/api/payroll/batches/{batchId}/items', {
    params: { path: { batchId } },
  })
  ensureOk(response, error)
  return (data as PayrollItem[] | undefined) ?? []
}

export async function addPayrollBatchItems(batchId: string, items: PayrollItemInput[]): Promise<PayrollBatch> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payroll/batches/{batchId}/items', {
    params: { path: { batchId } },
    body: { items },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as PayrollBatch | undefined, 'Add payroll items')
}

export async function submitPayrollBatch(batchId: string): Promise<PayrollBatch> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payroll/batches/{batchId}/submit', {
    params: { path: { batchId } },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as PayrollBatch | undefined, 'Submit payroll batch')
}

export async function approvePayrollBatch(batchId: string): Promise<PayrollBatch> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payroll/batches/{batchId}/approve', {
    params: { path: { batchId } },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as PayrollBatch | undefined, 'Approve payroll batch')
}

export async function rejectPayrollBatch(batchId: string, reason: string): Promise<PayrollBatch> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payroll/batches/{batchId}/reject', {
    params: { path: { batchId } },
    body: { reason },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as PayrollBatch | undefined, 'Reject payroll batch')
}

export async function executePayrollBatch(batchId: string, limit = 500): Promise<PayrollBatch> {
  const token = await csrfToken()
  const { data, error, response } = await client.POST('/api/payroll/batches/{batchId}/execute', {
    params: { path: { batchId }, query: { limit } },
    headers: { 'X-CSRF-TOKEN': token },
  })
  ensureOk(response, error)
  return required(data as PayrollBatch | undefined, 'Execute payroll batch')
}

export async function getTransferAccounts(): Promise<TransferAccount[]> {
  const personal = (await getAccounts()).map((account) => ({ ...account, canDebit: true }))
  const organizations = await getOrganizations()
  const corporate = await Promise.all(organizations.map(async (organization) => {
    const accounts = await getOrganizationAccounts(organization.id)
    return accounts.map((account) => ({
      ...account,
      displayName: organization.displayName + ' · ' + account.displayName,
      canDebit: false,
    }))
  }))
  return [...personal, ...corporate.flat()]
}
