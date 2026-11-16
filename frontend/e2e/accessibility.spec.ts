import { expect, test, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'

// E12-H02 (Round 4): a dependency-free accessibility subset over the real application and API. For every main screen it
// reads the accessibility tree Playwright computes (roles and accessible names) and a few DOM facts, and asserts:
// every interactive control and image has an accessible name, exactly one main landmark, at least one level-1 heading,
// a document language, a descriptive document title and no duplicate element ids; an open modal dialog is named and
// hides the page behind it. This is NOT an axe-core/WCAG audit:
// colour contrast, focus order beyond the E11 keyboard paths and real screen-reader behaviour are not assessed.

const password = 'LedgerCore-E2E-Password!'
const NAMED_ROLES = new Set(['button', 'link', 'textbox', 'searchbox', 'combobox', 'listbox', 'checkbox', 'radio',
  'spinbutton', 'switch', 'slider', 'tab', 'menuitem', 'img'])

type PageReport = { screen: string; kind: 'page' | 'dialog'; path: string; unnamed: string[]; mainLandmarks: number
  levelOneHeadings: number; namedDialogs: number; lang: string; title: string; duplicateIds: string[]; namedControls: number }

function unnamedNodes(snapshot: string): { unnamed: string[]; named: number } {
  // Snapshot lines look like `- button "Approve"`, `- textbox "Email": value`, or `- button` when the name is empty.
  const unnamed: string[] = []
  let named = 0
  for (const raw of snapshot.split('\n')) {
    const match = /^\s*- ([a-z]+)(?:\s+"((?:[^"\\]|\\.)*)")?/.exec(raw)
    if (!match || !NAMED_ROLES.has(match[1])) continue
    if (match[2] && match[2].trim()) named++
    else unnamed.push(raw.trim())
  }
  return { unnamed, named }
}

async function inspect(page: Page, screen: string, kind: 'page' | 'dialog' = 'page'): Promise<PageReport> {
  await page.waitForLoadState('networkidle')
  const snapshot = await page.locator('body').ariaSnapshot()
  const { unnamed, named } = unnamedNodes(snapshot)
  const facts = await page.evaluate(() => {
    const counts = new Map<string, number>()
    for (const element of Array.from(document.querySelectorAll('[id]'))) counts.set(element.id, (counts.get(element.id) ?? 0) + 1)
    return { lang: document.documentElement.lang, title: document.title,
      duplicateIds: Array.from(counts.entries()).filter(([, count]) => count > 1).map(([id]) => id) }
  })
  const dialogs = page.getByRole('dialog')
  let namedDialogs = 0
  for (let i = 0; i < await dialogs.count(); i++) {
    if ((await dialogs.nth(i).getAttribute('aria-labelledby')) || (await dialogs.nth(i).getAttribute('aria-label'))) namedDialogs++
  }
  return { screen, kind, path: new URL(page.url()).pathname, unnamed, namedControls: named, namedDialogs,
    mainLandmarks: await page.getByRole('main').count(), levelOneHeadings: await page.getByRole('heading', { level: 1 }).count(),
    ...facts }
}

async function register(page: Page, name: string, email: string) {
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Your name').fill(name)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Your money, in view.' })).toBeVisible()
}

test('main screens expose named controls, landmarks, headings, language and unique ids', async ({ page, browser }) => {
  const reports: PageReport[] = []
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Create an account' })).toBeVisible()
  reports.push(await inspect(page, 'Sign in'))
  const adminEmail = `a11y-admin-${crypto.randomUUID().slice(0, 12)}@example.test`
  await register(page, 'Ari Access', adminEmail)

  await page.getByRole('link', { name: 'Organizations', exact: true }).click()
  await page.getByLabel('Organization name').fill('Accessibility Audit Ltd')
  await page.getByRole('button', { name: 'Create organization' }).click()
  await expect(page.getByText('Accessibility Audit Ltd', { exact: true }).first()).toBeVisible()
  await page.getByLabel('Account name', { exact: true }).fill('Audit operating')
  await page.getByRole('button', { name: 'Open corporate account' }).click()
  await expect(page.getByText('Audit operating', { exact: true })).toBeVisible()
  const organizationId = await page.evaluate(async () => {
    const organizations = await (await fetch('/api/organizations', { credentials: 'include' })).json() as Array<{ id: string; displayName: string }>
    return organizations.find((item) => item.displayName === 'Accessibility Audit Ltd')?.id
  })
  expect(organizationId).toBeTruthy()

  const operatorContext = await browser.newContext()
  const operator = await operatorContext.newPage()
  try {
    const operatorEmail = `a11y-ops-${crypto.randomUUID().slice(0, 12)}@example.test`
    await operator.goto('/')
    await register(operator, 'Oli Operator', operatorEmail)
    const grant = await page.evaluate(async ({ organization, email }) => {
      const csrf = (await (await fetch('/api/auth/csrf', { credentials: 'include' })).json() as { token: string }).token
      const response = await fetch(`/api/organizations/${organization}/members`, {
        method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf },
        body: JSON.stringify({ email, roles: ['OPERATIONS', 'PAYMENT_MAKER', 'PAYROLL_MAKER'] }),
      })
      return response.status
    }, { organization: organizationId as string, email: operatorEmail })
    expect(grant).toBeLessThan(300)

    await operator.getByRole('button', { name: 'Open your first account' }).click()
    await expect(operator.getByRole('dialog')).toBeVisible()
    reports.push(await inspect(operator, 'Open account dialog', 'dialog'))
    await operator.getByRole('dialog').getByLabel('Account name').fill('Everyday account')
    await operator.getByRole('dialog').getByRole('button', { name: 'Open account', exact: true }).click()
    await expect(operator.getByRole('link', { name: /Everyday account/ })).toBeVisible()

    for (const [screen, path, heading] of [
      ['Dashboard', '/', 'Your money, in view.'], ['Transfer', '/transfer', 'Make a transfer'],
      ['History', '/history', 'Transaction history'], ['Organizations', '/organizations', 'Organizations'],
      ['Payroll', '/payroll', 'Payroll'], ['Payments', '/payments', 'Payments'], ['Operations', '/operations', 'Operations console'],
    ] as const) {
      await operator.goto(path)
      await expect(operator.getByRole('heading', { level: 1, name: heading })).toBeVisible()
      reports.push(await inspect(operator, screen))
    }
  }
  finally {
    await operatorContext.close()
  }

  await test.info().attach('accessibility-subset.json', { body: JSON.stringify(reports, null, 2), contentType: 'application/json' })
  if (process.env.E12_A11Y_REPORT) await writeFile(process.env.E12_A11Y_REPORT, JSON.stringify(reports, null, 2))
  for (const report of reports) {
    expect.soft(report.unnamed, `${report.screen}: controls without an accessible name`).toEqual([])
    if (report.kind === 'dialog') {
      // a modal exposes exactly one named dialog and hides the page behind it from assistive technology
      expect.soft(report.namedDialogs, `${report.screen}: named modal dialogs`).toBe(1)
      expect.soft(report.mainLandmarks, `${report.screen}: background hidden while modal`).toBe(0)
    } else {
      expect.soft(report.mainLandmarks, `${report.screen}: main landmarks`).toBe(1)
      expect.soft(report.levelOneHeadings, `${report.screen}: level-1 headings`).toBeGreaterThanOrEqual(1)
    }
    expect.soft(report.lang, `${report.screen}: document language`).not.toBe('')
    expect.soft(report.title, `${report.screen}: document title`).toMatch(/LedgerCore/)
    expect.soft(report.duplicateIds, `${report.screen}: duplicate element ids`).toEqual([])
    expect.soft(report.namedControls, `${report.screen}: named controls found`).toBeGreaterThan(0)
  }
})
