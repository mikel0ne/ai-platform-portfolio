import { chromium } from 'playwright-core';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [, , html, out] = process.argv;
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1000, height: 600 }, deviceScaleFactor: 1.5 });
for (const step of [0, 1, 2, 3]) {
  await page.goto(`${pathToFileURL(html).href}?step=${step}`);
  await page.screenshot({ path: path.join(out, `diagnose-0${step}.png`) });
}
await browser.close();
