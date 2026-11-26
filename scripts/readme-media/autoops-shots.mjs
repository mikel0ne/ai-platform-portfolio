import { chromium } from 'playwright-core';
import path from 'node:path';

const out = process.argv[2];
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const page = await browser.newPage({ viewport: { width: 1100, height: 1500 }, deviceScaleFactor: 1.5 });
await page.goto('http://autoops.local');
await page.getByRole('button', { name: /readiness y liveness/ }).waitFor();
await page.screenshot({ path: path.join(out, 'autoops-00-start.png') });
await page.getByRole('button', { name: /readiness y liveness/ }).click();
await page.waitForTimeout(1500);
await page.waitForTimeout(Number(process.argv[3] ?? 45000));

const text = await page.innerText('body');
if (/no puedo|error|inténtalo/i.test(text.slice(0, 4000)) && text.length < 900) {
  console.log(text.slice(0, 400));
  await browser.close();
  process.exit(2);
}
await page.screenshot({ path: path.join(out, 'autoops-01-answer.png'), fullPage: true });
console.log(text.slice(0, 600));
await browser.close();
