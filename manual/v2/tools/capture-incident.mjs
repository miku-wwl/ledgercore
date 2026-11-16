// Browser half of capture-incident.ps1: signs in through the real login form and saves genuine screenshots.
// Usage: node capture-incident.mjs <input.json>   (input written by the PowerShell script; it holds run-scoped
// synthetic credentials and is deleted afterwards). Prints one JSON line listing the files and their SHA-256.
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const require = createRequire(resolve(repo, 'frontend/package.json'))
const { chromium } = require('playwright')
const input = JSON.parse(readFileSync(process.argv[2], 'utf8').replace(/^﻿/, ''))
const files = []

async function signIn(browser, who) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
  const page = await context.newPage()
  await page.goto(input.baseUrl + '/')
  await page.getByLabel('Email').fill(who.email)
  await page.getByLabel('Password').fill(who.password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('heading', { name: 'Your money, in view.' }).waitFor()
  return { context, page }
}

async function shot(target, name, description) {
  const path = resolve(input.outDir, name)
  await target.screenshot({ path })
  files.push({ file: name, phase: input.phase, description,
    sha256: createHash('sha256').update(readFileSync(path)).digest('hex') })
}

const browser = await chromium.launch()
try {
  const ops = await signIn(browser, input.operations)
  await ops.page.goto(input.baseUrl + '/operations')
  await ops.page.getByRole('heading', { level: 1, name: 'Operations console' }).waitFor()
  await ops.page.waitForLoadState('networkidle')
  await ops.page.waitForTimeout(800)
  await shot(ops.page, `operations-${input.phase}.png`, input.phase === 'incident'
    ? 'Operations console during a genuine reserve shortage: owed settlement, reserve-blocked settlement, unresolved payable, dead-letter delivery'
    : 'Operations console after the audited replenishment, one governed retry and resolution after recheck')
  const exceptions = ops.page.getByText('Reconciliation exceptions', { exact: false }).first()
  if (await exceptions.count()) {
    const card = exceptions.locator('xpath=ancestor::*[@data-slot="card"][1]')
    await card.scrollIntoViewIfNeeded()
    await shot(await card.count() ? card : ops.page, `operations-exceptions-${input.phase}.png`,
      `Reconciliation exceptions card (${input.phase})`)
  }
  await ops.context.close()

  const approver = await signIn(browser, input.approver)
  await approver.page.goto(input.baseUrl + '/payments')
  await approver.page.getByRole('heading', { level: 1, name: 'Payments' }).waitFor()
  await approver.page.waitForLoadState('networkidle')
  await approver.page.waitForTimeout(800)
  await shot(approver.page, `payments-${input.phase}.png`, input.phase === 'incident'
    ? 'Payments page: an externally settled payment whose settlement journal is still owed, and an UNKNOWN outcome'
    : 'Payments page after recovery: the payment is settled and posted to the ledger')
  await approver.context.close()
}
finally {
  await browser.close()
}
console.log(JSON.stringify({ phase: input.phase, files }))
