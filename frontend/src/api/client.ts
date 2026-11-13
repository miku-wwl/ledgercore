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
