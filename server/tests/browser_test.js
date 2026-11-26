const { chromium } = require('playwright');
const url = process.argv[2];
const t0 = Date.now(); const ts = () => ((Date.now() - t0) / 1000).toFixed(2);
const recv = []; const errors = []; let binRecv = 0, binSent = 0;
(async () => {
  const browser = await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',
    '--use-file-for-fake-audio-capture=/work/question.wav', '--autoplay-policy=no-user-gesture-required'] });
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: 'dark',
    permissions: ['microphone'], ignoreHTTPSErrors: true });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('websocket', (ws) => {
    console.log(`[${ts()}] ws ${ws.url().replace(/k=[^&]+/, 'k=***')}`);
    ws.on('framesent', (f) => { if (typeof f.payload !== 'string') binSent++; });
    ws.on('framereceived', (f) => {
      if (typeof f.payload !== 'string') { binRecv++; return; }
      const m = JSON.parse(f.payload); recv.push(m);
      if (['user_turn', 'assistant_done', 'audio_start', 'audio_end', 'error', 'state'].includes(m.type))
        console.log(`[${ts()}] ${m.type} ${m.text || m.value || m.sample_rate || ''}`);
    });
  });
  await page.goto(url);
  await page.waitForSelector('#conn[data-state=online]', { timeout: 20000 });
  await page.click('#orb');
  const end = Date.now() + 60000;
  while (Date.now() < end && !recv.some((m) => m.type === 'audio_end')) await page.waitForTimeout(200);
  await page.waitForTimeout(1500);
  await page.screenshot({ path: '/work/real-390.png' });
  console.log(`done: audio frames sent ${binSent}, reply audio chunks ${binRecv}, console/page errors ${errors.length}`, errors.slice(0, 3));
  await browser.close();
})().catch((e) => { console.log('FAIL', e.message); process.exit(1); });
