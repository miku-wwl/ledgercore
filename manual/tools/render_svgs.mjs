import { chromium } from '../../frontend/node_modules/playwright/index.mjs'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const sourceDir = resolve(root, 'manual/figures')
const imageDir = resolve(root, 'work/book-build/figures')
await mkdir(imageDir, { recursive: true })
const browser = await chromium.launch({ headless: true })
const page = await browser.newPage({ viewport: { width: 1040, height: 500 }, deviceScaleFactor: 1 })
try {
  for (const file of (await (await import('node:fs/promises')).readdir(sourceDir)).filter((name) => name.endsWith('.svg'))) {
    await page.goto(pathToFileURL(resolve(sourceDir, file)).href)
    const svg = page.locator('svg')
    await svg.waitFor()
    await svg.screenshot({ path: resolve(imageDir, file.replace(/\.svg$/, '.png')) })
  }
  console.log(`Rendered ${ (await (await import('node:fs/promises')).readdir(imageDir)).length } SVG diagrams for PDF placement`)
} finally {
  await browser.close()
}
