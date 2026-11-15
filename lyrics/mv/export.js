// 逐帧导出：无头浏览器调用 renderAt(t)，帧画面送进 ffmpeg，和原音频合成 MP4
// 用法：node export.js 输出.mp4 [起始秒] [结束秒]
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const path = require('path');
(async () => {
  const out = process.argv[2] || 'mv.mp4';
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
  page.on('pageerror', e => console.error('page error:', e.message));
  await page.goto('file://' + path.resolve(__dirname, 'index.html') + '?export');
  await page.evaluate(() => document.fonts.ready);
  const dur = await page.evaluate(() => window.TIMELINE.duration);
  const t0 = parseFloat(process.argv[3] || '0'), t1 = Math.min(dur, parseFloat(process.argv[4] || String(dur)));
  const FPS = 30, n = Math.round((t1 - t0) * FPS);
  const ff = spawn('ffmpeg', ['-v', 'error', '-y', '-f', 'image2pipe', '-c:v', 'mjpeg', '-framerate', String(FPS), '-i', '-',
    '-ss', String(t0), '-t', String(t1 - t0), '-i', path.resolve(__dirname, 'audio.mp3'),
    '-map', '0:v', '-map', '1:a', '-c:v', 'libx264', '-preset', 'medium', '-crf', '19', '-pix_fmt', 'yuv420p',
    '-c:a', 'aac', '-b:a', '192k', '-shortest', '-movflags', '+faststart', out], { stdio: ['pipe', 'inherit', 'inherit'] });
  const start = Date.now();
  for (let i = 0; i < n; i++) {
    const b64 = await page.evaluate(t => { window.renderAt(t); return document.getElementById('c').toDataURL('image/jpeg', 0.93).slice(23); }, t0 + i / FPS);
    if (!ff.stdin.write(Buffer.from(b64, 'base64'))) await new Promise(r => ff.stdin.once('drain', r));
    if (i % 300 === 0) console.log(`${(t0 + i / FPS).toFixed(1)}s / ${t1.toFixed(1)}s  (${((Date.now() - start) / 1000).toFixed(0)}s elapsed)`);
  }
  ff.stdin.end();
  await new Promise(r => ff.on('close', r));
  await browser.close();
  console.log('done:', out);
})();
