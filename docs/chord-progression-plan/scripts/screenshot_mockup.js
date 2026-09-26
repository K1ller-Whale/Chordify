// Screenshots of mockup/analysis-view.html used in the docs.
//   NODE_PATH=$(npm root -g) node screenshot_mockup.js   (needs the `playwright` package)
const path = require("path");
const { chromium } = require("playwright");

const root = path.join(__dirname, "..");
const shots = [
  ["desktop-dark", 1440, "dark", 1.5, null],
  ["desktop-light", 1440, "light", 1.5, null],
  ["mobile-dark", 390, "dark", 2, 1490],
];

(async () => {
  const browser = await chromium.launch();
  for (const [name, width, theme, scale, maxHeight] of shots) {
    const page = await browser.newPage({ viewport: { width, height: 1000 }, deviceScaleFactor: scale });
    await page.goto(`file://${root}/mockup/analysis-view.html?theme=${theme}#t=99.2`);
    await page.waitForTimeout(600);
    const full = await page.evaluate(() => document.documentElement.scrollHeight);
    await page.setViewportSize({ width, height: maxHeight ? Math.min(full, maxHeight) : full });
    await page.waitForTimeout(300);
    await page.screenshot({ path: `${root}/assets/mockup-${name}.png` });
    await page.close();
  }
  await browser.close();
})();
