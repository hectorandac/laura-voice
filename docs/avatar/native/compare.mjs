// Side-by-side WebView | native | difference montages, in headless chromium (no image libs on the host).
//   node compare.mjs <webDir> <nativeDir> <outDir>     (run by compare.sh in the playwright image)
// Per case: <outDir>/<name>.png, plus sheet.png (every case at 256 px) and stats.json (mean |diff| per channel,
// % of pixels differing by more than 16/255).
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';

const [webDir, natDir, outDir] = process.argv.slice(2).map(p => path.resolve(p));
fs.mkdirSync(outDir, { recursive: true });
const names = fs.readdirSync(natDir).filter(f => f.endsWith('.png') && fs.existsSync(path.join(webDir, f.replace(/@[0-9]+/, '')))).map(f => f.slice(0, -4)).sort();

const PAGE = `<!doctype html><body style="margin:0;background:#222"><script>
const names = ${JSON.stringify(names)};
const load = src => new Promise((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = no; i.src = src; });
const checker = (g, x, y, w, h) => { for (let j = 0; j < h; j += 8) for (let i = 0; i < w; i += 8) { g.fillStyle = ((i + j) / 8) % 2 ? '#bbb' : '#eee'; g.fillRect(x + i, y + j, 8, 8); } };
(async () => {
  const stats = {}, sheetCells = [];
  for (const n of names) {
    const a = await load('/web/' + n.replace(/@[0-9]+/, '') + '.png'), b = await load('/nat/' + encodeURIComponent(n) + '.png');
    const S = a.width, Z = S < 200 ? Math.ceil(256 / S) : 1, W = S * Z, pad = 24;
    const c = document.createElement('canvas'); c.width = W * 3; c.height = W + pad; const g = c.getContext('2d');
    g.imageSmoothingEnabled = false; g.fillStyle = '#222'; g.fillRect(0, 0, c.width, c.height);
    const clear = n.startsWith('bubble_');
    if (clear) { checker(g, 0, pad, W, W); checker(g, W, pad, W, W); }
    g.drawImage(a, 0, pad, W, W); g.drawImage(b, W, pad, W, W);
    const t = document.createElement('canvas'); t.width = S; t.height = S; const tg = t.getContext('2d');
    tg.drawImage(a, 0, 0); const da = tg.getImageData(0, 0, S, S).data; tg.clearRect(0, 0, S, S); tg.drawImage(b, 0, 0); const db = tg.getImageData(0, 0, S, S).data;
    const d = tg.createImageData(S, S); let sum = [0, 0, 0, 0], big = 0;
    for (let i = 0; i < da.length; i += 4) { let m = 0; for (let k = 0; k < 4; k++) { const v = Math.abs(da[i + k] - db[i + k]); sum[k] += v; m = Math.max(m, v); }
      if (m > 16) big++; const v = Math.min(255, m * 4); d.data[i] = v; d.data[i + 1] = v * 0.5; d.data[i + 2] = v * 0.2; d.data[i + 3] = 255; }
    tg.putImageData(d, 0, 0); g.drawImage(t, 2 * W, pad, W, W);
    g.fillStyle = '#fff'; g.font = '14px sans-serif';
    const px = S * S; stats[n] = { size: S, meanAbs: sum.map(s => +(s / px).toFixed(2)), pctOver16: +(100 * big / px).toFixed(2) };
    g.fillText(n + ': WebView', 6, 17); g.fillText('native GLES', W + 6, 17); g.fillText('|diff| x4  (' + stats[n].pctOver16 + '% px > 16)', 2 * W + 6, 17);
    await fetch('/out/' + encodeURIComponent(n) + '.png', { method: 'POST', body: c.toDataURL('image/png') });
    sheetCells.push(c);
  }
  // overview: each case as WebView | native at 256 px, 2 pairs per row
  const cw = 512, ch = 256 + 20, cols = 2, rows = Math.ceil(sheetCells.length / cols);
  const s = document.createElement('canvas'); s.width = cw * cols + (cols - 1) * 8; s.height = rows * ch; const sg = s.getContext('2d');
  sg.fillStyle = '#222'; sg.fillRect(0, 0, s.width, s.height);
  sheetCells.forEach((c, i) => { const x = (i % cols) * (cw + 8), y = Math.floor(i / cols) * ch, W = (c.width / 3);
    sg.drawImage(c, 0, 0, W * 2, c.height, x, y, cw, ch); });
  await fetch('/out/sheet.png', { method: 'POST', body: s.toDataURL('image/png') });
  await fetch('/out/stats.json', { method: 'POST', body: JSON.stringify(stats, null, 1) });
  await fetch('/out/done', { method: 'POST', body: 'ok' });
})().catch(e => fetch('/out/error', { method: 'POST', body: String(e.stack || e) }));
</script>`;

let finish; const done = new Promise(r => { finish = r; });
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (req.method === 'POST') {
    const ch = []; req.on('data', c => ch.push(c)); req.on('end', () => {
      res.end('ok'); const name = decodeURIComponent(u.pathname.slice(5)), body = Buffer.concat(ch).toString();
      if (name === 'done') return finish(0); if (name === 'error') { console.error(body); return finish(1); }
      fs.writeFileSync(path.join(outDir, name), name.endsWith('.png') ? Buffer.from(body.split(',')[1], 'base64') : body);
    }); return;
  }
  if (u.pathname === '/') { res.writeHead(200, { 'Content-Type': 'text/html' }); return res.end(PAGE); }
  const [, dir, f] = decodeURIComponent(u.pathname).split('/'); const base = dir === 'web' ? webDir : dir === 'nat' ? natDir : null;
  const p = base && path.join(base, path.basename(f));
  if (!p || !fs.existsSync(p)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': 'image/png' }); fs.createReadStream(p).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const exe = fs.readdirSync('/ms-playwright').filter(d => d.startsWith('chromium_headless_shell'))
  .map(d => `/ms-playwright/${d}/chrome-headless-shell-linux64/chrome-headless-shell`).find(f => fs.existsSync(f));
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'laura-cmp-'));
const chrome = spawn(exe, ['--no-sandbox', '--force-color-profile=srgb', '--user-data-dir=' + udd, `http://127.0.0.1:${server.address().port}/`], { stdio: 'ignore' });
const code = await done; chrome.kill('SIGKILL'); server.close(); fs.rmSync(udd, { recursive: true, force: true }); process.exit(code);
