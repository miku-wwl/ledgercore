import { expect, test } from '@playwright/test'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const password = 'LedgerCore-E2E-Password!'

async function register(page: import('@playwright/test').Page, name: string, email: string) {
  await page.goto('/')
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Your name').fill(name)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
}

async function openAccount(page: import('@playwright/test').Page, name: string) {
  const firstAccountCta = page.getByRole('button', { name: 'Open your first account' })
  if (await firstAccountCta.count()) await firstAccountCta.click()
  else await page.getByRole('button', { name: 'Open account', exact: true }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Account name').fill(name)
  await dialog.getByRole('button', { name: 'Open account', exact: true }).click()
  await expect(page.getByRole('link', { name: new RegExp(name) })).toBeVisible()
}

test('registers, transfers, recovers a lost response, pages history, and enforces ownership', async ({ page, browser }) => {
  const aliceEmail = `e2e-${crypto.randomUUID()}@example.test`
  await register(page, 'Alex Ledger', aliceEmail)
  await expect(page.getByRole('heading', { name: 'Your money, in view.' })).toBeVisible()
  await openAccount(page, 'Everyday account')
  await page.getByRole('button', { name: 'Open account', exact: true }).first().click()
  const secondDialog = page.getByRole('dialog')
  await secondDialog.getByLabel('Account name').fill('Savings account')
  await secondDialog.getByRole('button', { name: 'Open account', exact: true }).click()
  await expect(page.getByRole('link', { name: /Savings account/ })).toBeVisible()

  const transferNav = page.getByRole('link', { name: 'Transfer', exact: true })
  await transferNav.click()
  const sourceSelect = page.getByLabel('From account')
  const targetSelect = page.getByLabel('To account')
  await sourceSelect.selectOption({ label: 'Everyday account · NZ$1,000.00' })
  await targetSelect.selectOption({ label: 'Savings account' })

  await page.getByLabel('Amount').fill('2000.00')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await expect(page.getByRole('alert').getByText('Transfer not posted')).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('not have enough available funds')

  await page.getByLabel('Amount').fill('30.00')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await expect(page.getByRole('heading', { name: 'Transfer complete' })).toBeVisible()
  await page.getByRole('button', { name: 'Make another transfer' }).click()

  let droppedTransferId = ''
  let dropFirstTransferResponse = true
  await page.route('**/api/transfers', async (route) => {
    if (dropFirstTransferResponse) {
      dropFirstTransferResponse = false
      const response = await route.fetch()
      expect(response.status()).toBe(200)
      const body = await response.json() as { transferId: string }
      droppedTransferId = body.transferId
      await route.abort('internetdisconnected')
      return
    }
    await route.continue()
  })

  await page.getByLabel('Amount').fill('25.00')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await expect(page.getByRole('alert').getByText('Transfer result unknown')).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('original request is saved')

  await page.reload()
  await expect(page.getByRole('alert').getByText('Transfer result unknown')).toBeVisible()
  await page.getByRole('button', { name: 'Check the saved transfer' }).click()
  await expect(page.getByRole('heading', { name: 'Transfer complete' })).toBeVisible()
  await expect(page.getByText(droppedTransferId)).toBeVisible()

  await page.getByRole('button', { name: 'Make another transfer' }).click()
  await page.getByLabel('Amount').fill('1.00')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await expect(page.getByRole('heading', { name: 'Transfer complete' })).toBeVisible()
  await page.getByRole('button', { name: 'Make another transfer' }).click()
  await page.getByLabel('Amount').fill('2.00')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await expect(page.getByRole('heading', { name: 'Transfer complete' })).toBeVisible()

  await page.getByRole('link', { name: 'Overview', exact: true }).click()
  await expect(page.getByTestId('total-balance')).toHaveText('NZ$2,000.00')
  await expect(page.getByRole('link', { name: /Everyday account/ })).toContainText('NZ$942.00')
  await expect(page.getByRole('link', { name: /Savings account/ })).toContainText('NZ$1,058.00')

  await page.getByRole('link', { name: /Everyday account/ }).click()
  await expect(page.getByText('Ledger and projection match')).toBeVisible()

  await page.getByRole('link', { name: 'History', exact: true }).click()
  await page.getByLabel('Choose account for transaction history').selectOption({ label: 'Everyday account' })
  await expect(page.getByRole('button', { name: /Outgoing 25.00 NZD with Savings account/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /Load older activity/ })).toBeVisible()
  await page.getByRole('button', { name: /Load older activity/ }).click()
  await expect(page.getByRole('button', { name: /Outgoing 30.00 NZD with Savings account/ })).toBeVisible()
  await page.getByRole('button', { name: /Outgoing 25.00 NZD with Savings account/ }).click()
  await expect(page.getByRole('dialog').getByText('Balanced journal entry')).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('This account')
  await expect(page.getByRole('dialog')).toContainText('Counterparty')

  const bobContext = await browser.newContext()
  const bobPage = await bobContext.newPage()
  const bobEmail = `e2e-bob-${crypto.randomUUID()}@example.test`
  await register(bobPage, 'Robin Counterparty', bobEmail)
  await expect(bobPage.getByRole('heading', { name: 'Your money, in view.' })).toBeVisible()
  await openAccount(bobPage, 'Robin account')
  const bobAccountHref = await bobPage.getByRole('link', { name: /Robin account/ }).getAttribute('href')
  const bobAccountId = bobAccountHref?.split('/').at(-1)
  expect(bobAccountId).toBeTruthy()
  const unauthorizedStatus = await page.evaluate(async (id) => {
    const response = await fetch(`/api/accounts/${id}`, { credentials: 'include' })
    return response.status
  }, bobAccountId)
  expect(unauthorizedStatus).toBe(404)

  await bobContext.close()
  await page.getByRole('button', { name: 'Close' }).click().catch(() => undefined)
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Sign in to continue' })).toBeVisible()
  await page.getByLabel('Email').fill(aliceEmail)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByTestId('total-balance')).toHaveText('NZ$2,000.00')

  await mkdir(resolve(process.cwd(), '../work/evidence'), { recursive: true })
  await page.screenshot({ path: resolve(process.cwd(), '../work/evidence/p4-dashboard-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('navigation', { name: 'Mobile navigation' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: resolve(process.cwd(), '../work/evidence/p4-dashboard-mobile.png'), fullPage: false })
})
