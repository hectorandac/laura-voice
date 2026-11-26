// Runs the WebView avatar (clients/android/app/src/main/assets/avatar/index.html) in headless chromium with
// snippet.js appended to its module, and writes what the page posts back. No npm deps; runs in the playwright image
// (see export.sh).
//   node export.mjs <repoRoot> <outDir> <xmode> [casesJson] [bg]
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const [root, outDir, xmode, cases = '[]', bg = '0F0D17'] = process.argv.slice(2);
const AV = path.join(root, 'clients/android/app/src/main/assets/avatar');
const snippet = fs.readFileSync(path.join(HERE, 'snippet.js'), 'utf8');
fs.mkdirSync(outDir, { recursive: true });

const MIME = { '.html': 'text/html', '.js': 'text/javascript' };
let finish;
const done = new Promise(r => { finish = r; });
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (req.method === 'POST' && u.pathname.startsWith('/out/')) {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      const name = u.pathname.slice(5), body = Buffer.concat(chunks).toString();
      res.end('ok');
      if (name === 'done') return finish(0);
      if (name === 'error') { console.error('page error:', body); return finish(1); }
      const data = name.endsWith('.png') ? Buffer.from(body.split(',')[1], 'base64') : body;
      fs.writeFileSync(path.join(outDir, name), data);
      console.log('wrote', name, data.length);
    });
    return;
  }
  let p = path.resolve(AV, '.' + decodeURIComponent(u.pathname));
  if (!p.startsWith(AV) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  let body = fs.readFileSync(p);
  if (p.endsWith('index.html')) {
    const html = body.toString(), at = html.lastIndexOf('</script>');
    body = html.slice(0, at) + '\n' + snippet + '\n' + html.slice(at);
  }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(p)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
  res.end(body);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/index.html?` + new URLSearchParams({ bg, wake: '0', xmode, cases });

const exe = fs.readdirSync('/ms-playwright').filter(d => d.startsWith('chromium_headless_shell'))
  .map(d => `/ms-playwright/${d}/chrome-headless-shell-linux64/chrome-headless-shell`).find(f => fs.existsSync(f));
const udd = fs.mkdtempSync(path.join(os.tmpdir(), 'laura-export-'));
const chrome = spawn(exe, ['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--enable-webgl',
  '--force-color-profile=srgb', '--window-size=512,512', '--user-data-dir=' + udd, '--disable-background-timer-throttling',
  '--disable-renderer-backgrounding', '--enable-logging=stderr', '--v=0', url], { stdio: ['ignore', 'ignore', 'pipe'] });
chrome.stderr.on('data', d => { const s = d.toString(); if (/CONSOLE|Uncaught|ERROR:.*(js|module)/i.test(s)) process.stderr.write(s); });
const timer = setTimeout(() => { console.error('timed out'); finish(2); }, 15 * 60 * 1000);
const code = await done;
clearTimeout(timer);
chrome.kill('SIGKILL');
server.close();
fs.rmSync(udd, { recursive: true, force: true });
process.exit(code);
