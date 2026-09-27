// End-to-end smoke test against a running backend (:8000) and frontend (:5173):
// upload a song through the UI, wait for the analysis, play, seek and screenshot.
//
//   uvicorn chordify_backend.app.main:app --port 8000 &   (from the repository root)
//   npm run dev &                                          (in chordify-frontend)
//   NODE_PATH=$(npm root -g) node e2e/smoke.cjs path/to/song.wav [out-dir]
//
// Needs the `playwright` package and a Chromium it can launch.
const { chromium } = require('playwright')

const audio = process.argv[2]
const out = process.argv[3] || '.'
const base = process.env.CHORDIFY_WEB_URL || 'http://localhost:5173'
if (!audio) {
  console.error('usage: node e2e/smoke.cjs song.wav [out-dir]')
  process.exit(2)
}

;(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] })
  const errors = []
  for (const [name, width, height, scheme] of [['desktop-dark', 1440, 900, 'dark'], ['desktop-light', 1440, 900, 'light'], ['mobile-dark', 390, 844, 'dark']]) {
    const page = await browser.newPage({ viewport: { width, height }, colorScheme: scheme, deviceScaleFactor: 1.5 })
    page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`))
    page.on('console', (m) => { if (m.type() === 'error') errors.push(`${name}: ${m.text()}`) })
    await page.goto(`${base}/`)
    await page.setInputFiles('.dropzone input[type=file]', audio)
    await page.waitForSelector('.now-name', { timeout: 120000 })
    const overview = await page.$('.overview')
    const box = await overview.boundingBox()
    await overview.click({ position: { x: box.width / 3, y: box.height / 2 } })
    await page.click('button.play')
    await page.waitForTimeout(1200)
    await page.click('button.play')
    const now = await page.textContent('.now-name')
    const next = await page.$$eval('.pred .name', (els) => els.map((e) => e.textContent))
    await page.screenshot({ path: `${out}/app-${name}.png`, fullPage: true })
    console.log(`${name}: now ${now}, predicted next ${next.join(', ')}`)
    await page.close()
  }
  await browser.close()
  if (errors.length) {
    console.error('browser errors:', errors)
    process.exit(1)
  }
})()
