// Renders the car design stills: node render.mjs (inside mcr.microsoft.com/playwright, cwd = this folder).
import { chromium } from 'playwright';
const big = { u: 1, rail: '112px', app: '52px', clock: '26px', pad: '16px', turn: '30px' };
const small = { u: .62, rail: '76px', app: '36px', clock: '18px', pad: '10px', turn: '19px', 'rail-gap': '14px', 'card-r': '18px' };
const her = 'About 25 minutes. Traffic on Olympic-daero is light, so you should be there by 10:10.';
const shots = [
  ['call-split', '', 1280, 720, big, 'a-call-1280x720'],
  ['call-split', '', 800, 480, small, 'a-call-800x480'],
  ['pane', '?state=speak&a1=Hang%20up', 1280, 720, big, 'b-pane-incall-1280x720'],
  ['pane', '?state=idle&a1=Call%20Laura&her=' + encodeURIComponent('Ended the call. Tap Call Laura to talk again.'), 1280, 720, big, 'b-pane-ready-1280x720'],
  ['pane', '?state=speak&a1=Hang%20up&narrow=1', 800, 480, small, 'b-pane-incall-800x480'],
  ['media', '', 1280, 720, big, 'd-media-1280x720'],
  ['media', '', 800, 480, small, 'd-media-800x480'],
];
const browser = await chromium.launch();
for (const [page, q, w, h, vars, out] of shots) {
  const p = await browser.newPage({ viewport: { width: w, height: h } });
  await p.goto(`file://${process.cwd()}/${page}.html${q}`);
  await p.evaluate(v => { for (const [k, x] of Object.entries(v)) document.documentElement.style.setProperty('--' + k, x); }, vars);
  await p.evaluate(() => document.fonts.ready);
  await p.waitForTimeout(300);
  await p.screenshot({ path: `../${out}.png` });
  await p.close();
}
const ph = await browser.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2.625 });
await ph.goto(`file://${process.cwd()}/phone.html`);
await ph.evaluate(() => document.fonts.ready); await ph.waitForTimeout(300);
await ph.screenshot({ path: '../c-phone-1080x2400.png' });
const ic = await browser.newPage({ viewport: { width: 512, height: 512 } });
await ic.goto(`file://${process.cwd()}/call-icon.html`);
await ic.screenshot({ path: '../call-icon-512.png' });
await browser.close();
const b2 = await chromium.launch();
const rp = await b2.newPage({ viewport: { width: 480, height: 480 } });
await rp.goto(`file://${process.cwd()}/portrait-round.html`);
await rp.screenshot({ path: '../portrait-round-480.png', omitBackground: true });
await b2.close();
