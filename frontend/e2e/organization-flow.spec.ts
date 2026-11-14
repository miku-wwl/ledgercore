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

async function openAccount(page: import('@playwright/test').Page, name: string) {
  await page.getByRole('button', { name: 'Open your first account' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Account name').fill(name)
  await dialog.getByRole('button', { name: 'Open account', exact: true }).click()
  await expect(page.getByRole('link', { name: new RegExp(name) })).toBeVisible()
}

test('organization admin grants a payroll maker access without exposing corporate funds to generic transfers', async ({ page, browser }) => {
  const unique = crypto.randomUUID()
  const adminEmail = `e2e-org-admin-${unique}@example.test`
  const makerEmail = `e2e-payroll-maker-${unique}@example.test`
  const organizationName = `Friday Payroll ${unique.slice(0, 8)} Ltd`

  const makerContext = await browser.newContext()
  const makerPage = await makerContext.newPage()

  try {
    await register(makerPage, 'Payroll Maker', makerEmail)
    await openAccount(makerPage, 'Maker destination')

    await register(page, 'Organization Admin', adminEmail)
    await page.getByRole('link', { name: 'Organizations', exact: true }).click()
    await page.getByLabel('Organization name').fill(organizationName)
    await page.getByRole('button', { name: 'Create organization' }).click()
    await expect(page.getByText(organizationName, { exact: true }).first()).toBeVisible()

    await page.getByLabel('Account name', { exact: true }).fill('Payroll account')
    await page.getByRole('button', { name: 'Open corporate account' }).click()
    await expect(page.getByText('Payroll account', { exact: true })).toBeVisible()

    await page.getByLabel('Existing customer email').fill(makerEmail)
    await page.getByLabel('Organization role', { exact: true }).selectOption('PAYROLL_MAKER')
    await page.getByRole('button', { name: 'Add member' }).click()
    await expect(page.getByText(makerEmail, { exact: true })).toBeVisible()

    await makerPage.getByRole('link', { name: 'Organizations', exact: true }).click()
    await expect(makerPage.getByText(organizationName, { exact: true }).first()).toBeVisible()
    await expect(makerPage.getByText('PAYROLL MAKER', { exact: true })).toBeVisible()
    await expect(makerPage.getByText('Payroll account', { exact: true })).toBeVisible()

    await makerPage.getByRole('link', { name: 'Transfer', exact: true }).click()
    await expect(makerPage.getByLabel('From account').locator('option', {
      hasText: `${organizationName} · Payroll account`,
    })).toHaveCount(0)
  } finally {
    await makerContext.close()
  }
})
