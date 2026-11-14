import { expect, test } from '@playwright/test'

const password = 'LedgerCore-E2E-Password!'

async function register(page: import('@playwright/test').Page, name: string, email: string) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Your name').fill(name)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Your money, in view.' })).toBeVisible()
}

async function openFirstAccount(page: import('@playwright/test').Page, name: string) {
  await page.getByRole('button', { name: 'Open your first account' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Account name').fill(name)
  await dialog.getByRole('button', { name: 'Open account', exact: true }).click()
  const accountLink = page.getByRole('link', { name: new RegExp(name) })
  await expect(accountLink).toBeVisible()
  const href = await accountLink.getAttribute('href')
  const accountId = href?.split('/').at(-1)
  expect(accountId).toBeTruthy()
  return accountId as string
}

async function addMember(
  page: import('@playwright/test').Page,
  email: string,
  role: 'PAYROLL_MAKER' | 'PAYROLL_APPROVER',
) {
  await page.getByLabel('Existing customer email').fill(email)
  await page.getByLabel('Organization role', { exact: true }).selectOption(role)
  await page.getByRole('button', { name: 'Add member' }).click()
  await expect(page.getByText(email, { exact: true })).toBeVisible()
}

test('maker imports and submits payroll; independent approver executes once after a lost response', async ({ page, browser }) => {
  const unique = crypto.randomUUID()
  const makerEmail = `e2e-payroll-maker-${unique}@example.test`
  const approverEmail = `e2e-payroll-approver-${unique}@example.test`
  const adminEmail = `e2e-payroll-admin-${unique}@example.test`
  const organizationName = `Payroll ${unique.slice(0, 8)} Ltd`
  const makerContext = await browser.newContext()
  const approverContext = await browser.newContext()
  const makerPage = await makerContext.newPage()
  const approverPage = await approverContext.newPage()

  try {
    await register(makerPage, 'Payroll Maker', makerEmail)
    const firstPayeeId = await openFirstAccount(makerPage, 'Employee One account')
    const secondPayeeId = await makerPage.evaluate(async (name) => {
      const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
      const csrf = (await csrfResponse.json() as { token: string }).token
      const response = await fetch('/api/accounts', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
        body: JSON.stringify({ displayName: name }),
      })
      if (!response.ok) throw new Error(`Could not open a second payee account (${response.status}).`)
      return (await response.json() as { id: string }).id
    }, 'Employee Two account')

    await register(approverPage, 'Payroll Approver', approverEmail)
    await register(page, 'Payroll Administrator', adminEmail)
    await page.getByRole('link', { name: 'Organizations', exact: true }).click()
    await page.getByLabel('Organization name').fill(organizationName)
    await page.getByRole('button', { name: 'Create organization' }).click()
    await expect(page.getByText(organizationName, { exact: true }).first()).toBeVisible()
    await page.getByLabel('Account name', { exact: true }).fill('Payroll payer')
    await page.getByRole('button', { name: 'Open corporate account' }).click()
    await expect(page.getByText('Payroll payer', { exact: true })).toBeVisible()
    const payerId = await page.evaluate(async () => {
      const organizations = await (await fetch('/api/organizations', { credentials: 'include' })).json() as Array<{ id: string }>
      const accounts = await (await fetch(`/api/organizations/${organizations[0].id}/accounts`, {
        credentials: 'include',
      })).json() as Array<{ id: string }>
      return accounts[0].id
    })
    await addMember(page, makerEmail, 'PAYROLL_MAKER')
    await addMember(page, approverEmail, 'PAYROLL_APPROVER')

    await makerPage.getByRole('link', { name: 'Payroll', exact: true }).click()
    await makerPage.getByLabel('Batch name').fill('Friday payroll E2E')
    await makerPage.getByLabel('Payer account').selectOption(payerId)
    await makerPage.getByRole('button', { name: 'Create draft batch' }).click()
    await expect(makerPage.getByText('Friday payroll E2E', { exact: true }).first()).toBeVisible()

    const csv = [
      'employeeReference,employeeName,payeeAccountId,amount',
      `EMP-E2E-001,Employee One,${firstPayeeId},25.00`,
      `EMP-E2E-002,Employee Two,${secondPayeeId},35.00`,
    ].join('\n')
    await makerPage.locator('#payroll-csv').setInputFiles({
      name: 'friday-payroll.csv', mimeType: 'text/csv', buffer: Buffer.from(csv),
    })
    await expect(makerPage.getByText('2 items ready from friday-payroll.csv.')).toBeVisible()
    await makerPage.getByRole('button', { name: 'Add imported items' }).click()
    await expect(makerPage.getByText('EMP-E2E-001', { exact: true })).toBeVisible()
    await makerPage.getByRole('button', { name: 'Submit for approval' }).click()
    await expect(makerPage.getByText('SUBMITTED', { exact: true }).first()).toBeVisible()

    const selfApprovalStatus = await makerPage.evaluate(async () => {
      const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'include' })
      const csrf = (await csrfResponse.json() as { token: string }).token
      const batchChoice = document.querySelector('#payroll-batch-choice') as HTMLSelectElement | null
      if (!batchChoice?.value) throw new Error('Submitted payroll batch ID was not available.')
      const response = await fetch(`/api/payroll/batches/${batchChoice.value}/approve`, {
        method: 'POST', credentials: 'include', headers: { 'X-CSRF-TOKEN': csrf },
      })
      return response.status
    })
    expect(selfApprovalStatus).toBe(403)

    await approverPage.getByRole('link', { name: 'Payroll', exact: true }).click()
    await expect(approverPage.getByText('Friday payroll E2E', { exact: true }).first()).toBeVisible()
    await expect(approverPage.getByRole('button', { name: 'Approve batch' })).toBeVisible()
    await approverPage.getByRole('button', { name: 'Approve batch' }).click()
    await expect(approverPage.getByRole('button', { name: 'Run payroll' })).toBeVisible()

    let executionResponseLost = false
    await approverPage.route('**/api/payroll/batches/*/execute?*', async (route) => {
      if (executionResponseLost) return route.continue()
      executionResponseLost = true
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      await route.abort('internetdisconnected')
    })
    await approverPage.getByRole('button', { name: 'Run payroll' }).click()
    await expect(approverPage.getByRole('alert')).toContainText('Refresh the saved batch status')
    await approverPage.getByRole('button', { name: 'Refresh payroll status' }).click()
    await expect(approverPage.getByText('COMPLETED', { exact: true }).first()).toBeVisible()
    await expect(approverPage.getByText('NZ$60.00', { exact: true })).toBeVisible()
    await expect(approverPage.getByText('SUCCEEDED', { exact: true })).toHaveCount(2)
    const resultRows = approverPage.locator('tbody tr')
    await expect(resultRows).toHaveCount(2)
    const receiptIds = await resultRows.locator('code').allTextContents()
    expect(receiptIds).toHaveLength(2)
    expect(new Set(receiptIds).size).toBe(2)
    expect(receiptIds.every((id) => UUID_PATTERN.test(id))).toBe(true)
  } finally {
    await makerContext.close()
    await approverContext.close()
  }
})

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
