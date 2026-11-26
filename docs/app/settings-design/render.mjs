// Renders mock.html into phone-sized stills (1080x2400): node render.mjs <out dir>
import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import path from 'path';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = process.argv[2] || here;
const shots = [
  ['main', 'dark'], ['main-coach', 'dark'],
  ...['home', 'welcome', 'connection', 'car', 'invoke', 'about'].flatMap(s => [[s, 'dark'], [s, 'light']]),
];
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 360, height: 800 }, deviceScaleFactor: 3 });
for (const [s, t] of shots) {
  await page.goto(`file://${here}/mock.html?s=${s}&t=${t}`);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(150);
  const name = s.startsWith('main') ? s : `${s}-${t}`;
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log(name);
}
await browser.close();
