// 用无头浏览器调用 renderAt(t)，截出关键帧
const { chromium } = require('playwright');
const path = require('path');
(async () => {
  const times = process.argv.slice(2).map(Number);
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
  page.on('pageerror', e => console.error('page error:', e.message));
  await page.goto('file://' + path.resolve('index.html') + '?export');
  await page.evaluate(() => document.fonts.ready);
  for (const t of times) {
    await page.evaluate(t => window.renderAt(t), t);
    await page.locator('#c').screenshot({ path: `keyframes/${String(t.toFixed(1)).padStart(6, '0')}.png` });
    console.log('frame', t);
  }
  await browser.close();
})();
