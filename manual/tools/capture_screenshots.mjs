import { chromium } from '../../frontend/node_modules/playwright/index.mjs'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const outDir = resolve(root, 'manual/assets/screenshots')
await mkdir(outDir, { recursive: true })
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
const page = await context.newPage()
const capture = async (name) => page.screenshot({ path: resolve(outDir, `${name}.png`), fullPage: true })
const email = `textbook-${crypto.randomUUID()}@example.test`
const password = 'LedgerCore-Textbook-Demo!'

async function register() {
  await page.goto('http://127.0.0.1:5173/')
  await capture('login')
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Your name').fill('Alex Ledger')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await page.getByRole('heading', { name: 'Your money, in view.' }).waitFor()
}

async function openAccount(name) {
  const firstCta = page.getByRole('button', { name: 'Open your first account' })
  if (await firstCta.count()) await firstCta.click()
  else await page.getByRole('button', { name: 'Open account', exact: true }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Account name').fill(name)
  await dialog.getByRole('button', { name: 'Open account', exact: true }).click()
  await page.getByRole('link', { name: new RegExp(name) }).waitFor()
}

async function prepare(amount) {
  await page.getByLabel('From account').selectOption({ label: 'Everyday account · NZ$1,000.00' })
  await page.getByLabel('To account').selectOption({ label: 'Savings account' })
  await page.getByLabel('Amount').fill(amount)
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').waitFor()
}

try {
  await register()
  await openAccount('Everyday account')
  await page.getByRole('button', { name: 'Open account', exact: true }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Account name').fill('Savings account')
  await dialog.getByRole('button', { name: 'Open account', exact: true }).click()
  await page.getByRole('link', { name: /Savings account/ }).waitFor()
  await capture('dashboard-desktop')

  await page.getByRole('link', { name: /Everyday account/ }).click()
  await page.getByText('Ledger and projection match').waitFor()
  await capture('account-reconciliation')
  await page.getByRole('link', { name: 'Overview', exact: true }).click()
  await page.getByRole('link', { name: 'Transfer', exact: true }).click()

  await prepare('2000.00')
  await capture('transfer-confirmation')
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await page.getByRole('alert').getByText('Transfer not posted').waitFor()
  await capture('transfer-insufficient-funds')

  await page.getByLabel('Amount').fill('30.00')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await page.getByRole('heading', { name: 'Transfer complete' }).waitFor()
  await capture('transfer-success')
  await page.getByRole('button', { name: 'Make another transfer' }).click()

  let droppedTransferId = ''
  let dropFirstResponse = true
  await page.route('**/api/transfers', async (route) => {
    if (dropFirstResponse) {
      dropFirstResponse = false
      const response = await route.fetch()
      if (response.status() !== 200) throw new Error(`Expected committed transfer, got ${response.status()}`)
      droppedTransferId = (await response.json()).transferId
      await route.abort('internetdisconnected')
    } else {
      await route.continue()
    }
  })
  await page.getByLabel('Amount').fill('25.00')
  await page.getByRole('button', { name: 'Review transfer' }).click()
  await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
  await page.getByRole('alert').getByText('Transfer result unknown').waitFor()
  await capture('transfer-unknown')
  await page.reload()
  await page.getByRole('alert').getByText('Transfer result unknown').waitFor()
  await capture('transfer-unknown-recovery')
  await page.getByRole('button', { name: 'Check the saved transfer' }).click()
  await page.getByRole('heading', { name: 'Transfer complete' }).waitFor()
  await page.getByText(droppedTransferId).waitFor()
  await page.unroute('**/api/transfers')
  await page.getByRole('button', { name: 'Make another transfer' }).click()

  for (const amount of ['1.00', '2.00']) {
    await page.getByLabel('Amount').fill(amount)
    await page.getByRole('button', { name: 'Review transfer' }).click()
    await page.getByRole('dialog').getByRole('button', { name: /Confirm transfer/ }).click()
    await page.getByRole('heading', { name: 'Transfer complete' }).waitFor()
    if (amount === '1.00') await page.getByRole('button', { name: 'Make another transfer' }).click()
  }

  await page.getByRole('link', { name: 'Overview', exact: true }).click()
  await page.getByTestId('total-balance').getByText('NZ$2,000.00').waitFor().catch(async () => {
    await page.getByTestId('total-balance').waitFor()
  })
  await capture('dashboard-desktop')
  await page.getByRole('link', { name: /Everyday account/ }).click()
  await page.getByText('Ledger and projection match').waitFor()
  await capture('account-details')
  await page.getByRole('link', { name: 'History', exact: true }).click()
  await page.getByLabel('Choose account for transaction history').selectOption({ label: 'Everyday account' })
  await page.getByRole('button', { name: /Outgoing 25.00 NZD with Savings account/ }).waitFor()
  await capture('history-list')
  const older = page.getByRole('button', { name: /Load older activity/ })
  if (await older.count()) await older.click()
  const row = page.getByRole('button', { name: /Outgoing 25.00 NZD with Savings account/ })
  await row.click()
  await page.getByRole('dialog').getByText('Balanced journal entry').waitFor()
  await capture('history-detail')

  await page.goto('http://127.0.0.1:5173/')
  await page.getByRole('heading', { name: 'Your money, in view.' }).waitFor()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('navigation', { name: 'Mobile navigation' }).waitFor()
  const width = await page.evaluate(() => document.documentElement.scrollWidth)
  if (width > 390) throw new Error(`Mobile page overflows: ${width}px`)
  await page.screenshot({ path: resolve(outDir, 'dashboard-mobile.png'), fullPage: false })

  const manifest = {
    sourceCommit: '484b9ad1c70ba052849ee1a40e15e35b6d888c12',
    captureDate: new Date().toISOString(),
    application: 'LedgerCore local packaged JAR + Vite frontend',
    database: 'Disposable PostgreSQL 18.7 ledgercore_book database; synthetic example.test identity',
    browser: 'Playwright Chromium 1.65 headless',
    scenarios: ['registration and login view', 'two simulated accounts', 'confirmation', 'insufficient funds', 'posted transfer', 'committed response dropped', 'same-tab reload and same-key recovery', 'account reconciliation', 'history and journal detail', '390px mobile layout'],
    note: 'All captures are genuine local application screens; the lost response was injected after the backend returned HTTP 200 to the proxy.'
  }
  await writeFile(resolve(outDir, 'capture-manifest.json'), JSON.stringify(manifest, null, 2), 'utf8')
  console.log("Captured 12 screenshot states into manual/assets/screenshots")
} finally {
  await browser.close()
}
