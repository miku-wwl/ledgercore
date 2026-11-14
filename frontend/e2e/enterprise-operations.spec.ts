import { expect, test, type Page } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const password = 'LedgerCore-E2E-Password!'
const captureScreenshots = process.env.E11_CAPTURE_SCREENSHOTS === '1'

async function register(page: Page, name: string, email: string) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Your name').fill(name)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Your money, in view.' })).toBeVisible()
}

async function createOrganization(page: Page, displayName: string) {
  await page.getByRole('link', { name: 'Organizations', exact: true }).click()
  await page.getByLabel('Organization name').fill(displayName)
  await page.getByRole('button', { name: 'Create organization' }).click()
  await expect(page.getByText(displayName, { exact: true }).first()).toBeVisible()
  await page.getByLabel('Account name', { exact: true }).fill('Operations payer')
  await page.getByRole('button', { name: 'Open corporate account' }).click()
  await expect(page.getByText('Operations payer', { exact: true })).toBeVisible()
  return page.evaluate(async (name) => {
    const organizations = await (await fetch('/api/organizations', { credentials: 'include' })).json() as Array<{ id: string; displayName: string }>
    const organization = organizations.find((item) => item.displayName === name)
    if (!organization) throw new Error('New organization was not returned by the API.')
    const accounts = await (await fetch(`/api/organizations/${organization.id}/accounts`, { credentials: 'include' })).json() as Array<{ id: string }>
    if (!accounts[0]) throw new Error('New organization account was not returned by the API.')
    return { organizationId: organization.id, accountId: accounts[0].id }
  }, displayName)
}

async function addRole(page: Page, email: string, role: string) {
  await page.getByLabel('Existing customer email').fill(email)
  await page.getByLabel('Organization role', { exact: true }).selectOption(role)
  await page.getByRole('button', { name: 'Add member' }).click()
  await expect(page.getByText(email, { exact: true })).toBeVisible()
}

async function preparePayment(page: Page, accountId: string, beneficiary: string, route: string) {
  await page.getByRole('link', { name: 'Payments', exact: true }).click()
  await page.getByLabel('Pay from').selectOption(accountId)
  await page.getByLabel('Beneficiary name').fill(beneficiary)
  await page.getByLabel('Beneficiary account reference').fill(`E11-${crypto.randomUUID().slice(0, 8)}`)
  await page.getByLabel('Simulator route').selectOption(route)
  await page.getByLabel('Amount (NZD)').fill('25.50')
  await page.getByRole('button', { name: 'Create payment draft' }).click()
  const paymentRow = page.getByRole('row').filter({ hasText: beneficiary })
  await expect(paymentRow).toBeVisible()
  const paymentId = (await paymentRow.locator('code').first().textContent())?.trim()
  expect(paymentId).toMatch(/^[0-9a-f-]{36}$/i)
  await paymentRow.getByRole('button', { name: 'Submit' }).click()
  await expect(paymentRow).toContainText('Awaiting checker')
  return { paymentId: paymentId as string, paymentRow }
}

async function csrfPostStatus(page: Page, path: string) {
  return page.evaluate(async (target) => {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
    const csrf = (await csrfResponse.json() as { token: string }).token
    const response = await fetch(target, {
      method: 'POST', credentials: 'include', headers: { 'X-CSRF-TOKEN': csrf },
    })
    return response.status
  }, path)
}

async function screenshot(page: Page, name: string) {
  if (!captureScreenshots) return
  const directory = resolve(process.cwd(), '../evidence/round3/e11/screenshots')
  await mkdir(directory, { recursive: true })
  await page.screenshot({ path: resolve(directory, name) })
}

test('maker/checker payment reaches a real settlement and self-approval is rejected', async ({ page, browser }) => {
  test.setTimeout(90_000)
  const nonce = crypto.randomUUID()
  const makerEmail = `e11-payment-maker-${nonce}@example.test`
  const approverEmail = `e11-payment-approver-${nonce}@example.test`
  const makerContext = await browser.newContext()
  const approverContext = await browser.newContext()
  const makerPage = await makerContext.newPage()
  const approverPage = await approverContext.newPage()

  try {
    await register(makerPage, 'Payment Maker', makerEmail)
    await register(approverPage, 'Payment Approver', approverEmail)
    await register(page, 'Treasury Administrator', `e11-payment-admin-${nonce}@example.test`)
    const organization = await createOrganization(page, 'Northstar Treasury Ltd')
    await addRole(page, makerEmail, 'PAYMENT_MAKER')
    await addRole(page, approverEmail, 'PAYMENT_APPROVER')
    await makerPage.reload()
    await approverPage.reload()

    const { paymentId, paymentRow } = await preparePayment(makerPage, organization.accountId, 'Northstar Supplier', 'SIM-SETTLE')
    const selfApprovalStatus = await csrfPostStatus(makerPage, `/api/payments/${paymentId}/approve`)
    expect(selfApprovalStatus).toBe(403)
    const savedAfterSelfApproval = await makerPage.evaluate(async (id) => {
      const response = await fetch(`/api/payments/${id}`, { credentials: 'include' })
      return await response.json() as { status: string; bookingJournalEntryId?: string }
    }, paymentId)
    expect(savedAfterSelfApproval.status).toBe('SUBMITTED')
    expect(savedAfterSelfApproval.bookingJournalEntryId).toBeFalsy()

    await approverPage.getByRole('link', { name: 'Payments', exact: true }).click()
    const approvalRow = approverPage.getByRole('row').filter({ hasText: 'Northstar Supplier' })
    await expect(approvalRow).toBeVisible()
    await approvalRow.getByRole('button', { name: 'Approve' }).click()
    await expect(approvalRow.getByText('Settled', { exact: true })).toBeVisible({ timeout: 20_000 })
    await expect(approvalRow).toContainText('COMPLETED')
    await expect(paymentRow.getByText('Settled', { exact: true })).toBeVisible({ timeout: 20_000 })
    await expect(paymentRow).toContainText('COMPLETED')
    await screenshot(approverPage, 'corporate-payments-desktop.png')
  } finally {
    await makerContext.close()
    await approverContext.close()
  }
})

test('Operations resolves an UNKNOWN clearing result and records a verified reconciliation exception', async ({ page, browser }) => {
  test.setTimeout(120_000)
  const nonce = crypto.randomUUID()
  const makerEmail = `e11-unknown-maker-${nonce}@example.test`
  const approverEmail = `e11-unknown-approver-${nonce}@example.test`
  const operatorEmail = `e11-unknown-operator-${nonce}@example.test`
  const makerContext = await browser.newContext()
  const approverContext = await browser.newContext()
  const operatorContext = await browser.newContext()
  const makerPage = await makerContext.newPage()
  const approverPage = await approverContext.newPage()
  const operatorPage = await operatorContext.newPage()

  try {
    await register(makerPage, 'Unknown Outcome Maker', makerEmail)
    await register(approverPage, 'Unknown Outcome Checker', approverEmail)
    await register(operatorPage, 'Clearing Operator', operatorEmail)
    await register(page, 'Unknown Outcome Admin', `e11-unknown-admin-${nonce}@example.test`)
    const organization = await createOrganization(page, 'Southern Arc Logistics')
    await addRole(page, makerEmail, 'PAYMENT_MAKER')
    await addRole(page, approverEmail, 'PAYMENT_APPROVER')
    await addRole(page, operatorEmail, 'OPERATIONS')
    await makerPage.reload()
    await approverPage.reload()
    await operatorPage.reload()

    const firstPayment = await preparePayment(makerPage, organization.accountId, 'Southern Arc Carrier', 'SIM-UNKNOWN')
    await approverPage.getByRole('link', { name: 'Payments', exact: true }).click()
    const approvalRow = approverPage.getByRole('row').filter({ hasText: 'Southern Arc Carrier' })
    await approvalRow.getByRole('button', { name: 'Approve' }).click()
    await expect(approvalRow.getByText('Outcome unknown', { exact: true })).toBeVisible({ timeout: 20_000 })

    await operatorPage.getByRole('link', { name: 'Operations', exact: true }).click()
    await expect(operatorPage.getByRole('heading', { name: 'Operations console' })).toBeVisible()
    await expect(operatorPage.getByText('Unknown outcomes', { exact: true })).toBeVisible()
    await expect(operatorPage.getByRole('row').filter({ hasText: 'Southern Arc Carrier' })).toContainText('UNKNOWN')
    const unknownRow = operatorPage.getByRole('row').filter({ hasText: 'Southern Arc Carrier' })
    await unknownRow.getByRole('button', { name: 'Inquire' }).click()
    await expect(unknownRow).toHaveCount(0, { timeout: 20_000 })
    const recoveredPayment = await makerPage.evaluate(async (id) => {
      const response = await fetch(`/api/payments/${id}`, { credentials: 'include' })
      return await response.json() as { status: string; externalStatus?: string }
    }, firstPayment.paymentId)
    expect(recoveredPayment.status).toBe('COMPLETED')
    expect(recoveredPayment.externalStatus).toBe('SETTLED')

    await preparePayment(makerPage, organization.accountId, 'Southern Arc Follow-up', 'SIM-UNKNOWN')
    const followupApproval = approverPage.getByRole('row').filter({ hasText: 'Southern Arc Follow-up' })
    await followupApproval.getByRole('button', { name: 'Approve' }).click()
    await expect(followupApproval.getByText('Outcome unknown', { exact: true })).toBeVisible({ timeout: 20_000 })
    await operatorPage.getByRole('button', { name: 'Run reconciliation' }).click()
    await expect(operatorPage.getByText('CLEARING_CALLBACK_MISSING', { exact: true }).first()).toBeVisible({ timeout: 20_000 })
    await expect(operatorPage.getByText('Latest scan: 1 finding(s)')).toBeVisible()
    const exceptionCard = operatorPage.locator('div.rounded-lg.border').filter({ hasText: 'CLEARING_CALLBACK_MISSING' }).first()
    await expect(exceptionCard).toContainText('OPEN')
    await exceptionCard.getByRole('button', { name: 'Assign to me' }).click()
    await expect(exceptionCard).toContainText('ASSIGNED')
    await exceptionCard.scrollIntoViewIfNeeded()
    await screenshot(operatorPage, 'operations-console-desktop.png')

    await operatorPage.setViewportSize({ width: 390, height: 844 })
    await expect(operatorPage.getByRole('navigation', { name: 'Mobile navigation' })).toBeVisible()
    expect(await operatorPage.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await exceptionCard.scrollIntoViewIfNeeded()
    await operatorPage.keyboard.press('Tab')
    expect(await operatorPage.evaluate(() => {
      const active = document.activeElement
      return active instanceof HTMLElement && active !== document.body && active.matches(':focus-visible')
    })).toBe(true)
    await screenshot(operatorPage, 'operations-console-mobile.png')

    await operatorPage.getByRole('button', { name: 'Run reconciliation' }).click()
    await expect(operatorPage.getByText('Latest scan: 0 finding(s)')).toBeVisible({ timeout: 20_000 })
    const updatedException = operatorPage.locator('div.rounded-lg.border').filter({ hasText: 'CLEARING_CALLBACK_MISSING' }).first()
    await updatedException.getByLabel('Resolution note for CLEARING_CALLBACK_MISSING').fill('Simulator callback replay was verified; the follow-up scan confirmed matching terminal state and sequence.')
    await updatedException.getByRole('button', { name: 'Resolve after verified scan' }).click()
    await expect(updatedException).toContainText('RESOLVED')
  } finally {
    await makerContext.close()
    await approverContext.close()
    await operatorContext.close()
  }
})

test('Operations endpoints conceal a different organization', async ({ page, browser }) => {
  test.setTimeout(60_000)
  const nonce = crypto.randomUUID()
  const operatorEmail = `e11-tenant-operator-${nonce}@example.test`
  const operatorContext = await browser.newContext()
  const operatorPage = await operatorContext.newPage()
  try {
    await register(page, 'Tenant Boundary Admin', `e11-tenant-admin-${nonce}@example.test`)
    const first = await createOrganization(page, 'Harbourline Finance')
    await register(operatorPage, 'Harbourline Operator', operatorEmail)
    await addRole(page, operatorEmail, 'OPERATIONS')
    await page.getByRole('link', { name: 'Organizations', exact: true }).click()
    const secondOrganizationName = `Alpine Transit ${nonce.slice(0, 8)}`
    await page.getByLabel('Organization name').fill(secondOrganizationName)
    await page.getByRole('button', { name: 'Create organization' }).click()
    await expect(page.getByText(secondOrganizationName, { exact: true }).first()).toBeVisible()
    const secondOrganizationId = await page.evaluate(async (name) => {
      const organizations = await (await fetch('/api/organizations', { credentials: 'include' })).json() as Array<{ id: string; displayName: string }>
      return organizations.find((item) => item.displayName === name)?.id
    }, secondOrganizationName)
    expect(secondOrganizationId).toBeTruthy()
    await operatorPage.reload()
    const status = await operatorPage.evaluate(async (organizationId) =>
      (await fetch(`/api/organizations/${organizationId}/operations/audit`, { credentials: 'include' })).status,
    secondOrganizationId as string)
    expect(status).toBe(404)
    const visibleOrganizations = await operatorPage.evaluate(async () =>
      await (await fetch('/api/organizations', { credentials: 'include' })).json() as Array<{ id: string }>)
    expect(visibleOrganizations.map((item) => item.id)).toEqual([first.organizationId])
  } finally {
    await operatorContext.close()
  }
})

test('non-Operations member is denied a reconciliation action by the backend', async ({ page }) => {
  test.setTimeout(60_000)
  const nonce = crypto.randomUUID()
  const makerEmail = `e11-unauthorized-maker-${nonce}@example.test`
  const makerContext = await page.context().browser()?.newContext()
  const makerPage = await makerContext?.newPage()
  if (!makerContext || !makerPage) throw new Error('Could not create maker browser context.')
  try {
    await register(makerPage, 'Unauthorized Payment Maker', makerEmail)
    await register(page, 'Unauthorized Action Admin', `e11-unauthorized-admin-${nonce}@example.test`)
    const organization = await createOrganization(page, 'Copperleaf Services')
    await addRole(page, makerEmail, 'PAYMENT_MAKER')
    await makerPage.reload()
    await expect(makerPage.getByRole('link', { name: 'Operations', exact: true })).toHaveCount(0)
    const status = await csrfPostStatus(makerPage, `/api/organizations/${organization.organizationId}/operations/reconciliation/runs`)
    expect(status).toBe(403)
  } finally {
    await makerContext.close()
  }
})

test('revoked Operations role cannot inquire during a pending business operation', async ({ page, browser }) => {
  test.setTimeout(120_000)
  const nonce = crypto.randomUUID()
  const makerEmail = `e11-revoke-maker-${nonce}@example.test`
  const approverEmail = `e11-revoke-checker-${nonce}@example.test`
  const operatorEmail = `e11-revoke-operator-${nonce}@example.test`
  const makerContext = await browser.newContext()
  const approverContext = await browser.newContext()
  const operatorContext = await browser.newContext()
  const makerPage = await makerContext.newPage()
  const approverPage = await approverContext.newPage()
  const operatorPage = await operatorContext.newPage()
  try {
    await register(makerPage, 'Role Revocation Maker', makerEmail)
    await register(approverPage, 'Role Revocation Checker', approverEmail)
    await register(operatorPage, 'Revocable Operator', operatorEmail)
    await register(page, 'Role Revocation Admin', `e11-revoke-admin-${nonce}@example.test`)
    const organization = await createOrganization(page, 'Westhaven Produce')
    await addRole(page, makerEmail, 'PAYMENT_MAKER')
    await addRole(page, approverEmail, 'PAYMENT_APPROVER')
    await addRole(page, operatorEmail, 'OPERATIONS')
    await makerPage.reload()
    await approverPage.reload()
    await operatorPage.reload()

    await preparePayment(makerPage, organization.accountId, 'Westhaven Grower', 'SIM-UNKNOWN')
    const approvalRow = approverPage.getByRole('row').filter({ hasText: 'Westhaven Grower' })
    await approverPage.getByRole('link', { name: 'Payments', exact: true }).click()
    await approvalRow.getByRole('button', { name: 'Approve' }).click()
    await expect(approvalRow.getByText('Outcome unknown', { exact: true })).toBeVisible({ timeout: 20_000 })

    await operatorPage.getByRole('link', { name: 'Operations', exact: true }).click()
    const pendingRow = operatorPage.getByRole('row').filter({ hasText: 'Westhaven Grower' })
    await expect(pendingRow).toContainText('UNKNOWN', { timeout: 20_000 })
    const operationId = (await pendingRow.locator('code').first().textContent())?.trim()
    expect(operationId).toMatch(/^[0-9a-f-]{36}$/i)
    await page.getByRole('link', { name: 'Organizations', exact: true }).click()
    const revokeOperationsButton = page.getByRole('button', { name: `Revoke operations from ${operatorEmail}` })
    await revokeOperationsButton.click()
    await expect(revokeOperationsButton).toHaveCount(0, { timeout: 20_000 })
    const status = await csrfPostStatus(operatorPage, `/api/operations/clearing/${operationId}/inquiry`)
    expect(status).toBe(404)
    await operatorPage.reload()
    await expect(operatorPage.getByRole('heading', { name: 'Operations access is required' })).toBeVisible()
    await expect(operatorPage.getByRole('link', { name: 'Operations', exact: true })).toHaveCount(0)
  } finally {
    await makerContext.close()
    await approverContext.close()
    await operatorContext.close()
  }
})
