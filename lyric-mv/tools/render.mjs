// 逐帧导出 MP4：用无头浏览器逐帧渲染画布 → ffmpeg 编码 → 混入原曲
// 用法：
//   node tools/render.mjs                        # 全片 1080p30 → out/mv.mp4
//   node tools/render.mjs --from 55 --to 72      # 只导出一段（检查单个镜头）
//   node tools/render.mjs --project my.json      # 用预览页导出的工程文件
//   node tools/render.mjs --fps 24 --out out/x.mp4 --height 720
// 依赖：Node 18+、ffmpeg、playwright（npm i playwright && npx playwright install chromium）
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, arr) => (a.startsWith("--") ? [...acc, [a.slice(2), arr[i + 1]?.startsWith("--") || arr[i + 1] == null ? true : arr[i + 1]]] : acc), []));
const fps = +(args.fps || 30), height = +(args.height || 1080), width = Math.round((height * 16) / 9);
const out = path.resolve(ROOT, args.out || "out/mv.mp4");
fs.mkdirSync(path.dirname(out), { recursive: true });

// 本地静态服务器
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".woff2": "font/woff2", ".mp3": "audio/mpeg", ".json": "application/json" };
const server = http.createServer((req, res) => {
  const p = path.join(ROOT, decodeURIComponent(req.url.split("?")[0]));
  if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "Content-Type": types[path.extname(p)] || "application/octet-stream" }); fs.createReadStream(p).pipe(res);
});
await new Promise((r) => server.listen(0, r));
const port = server.address().port;

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
page.on("pageerror", (e) => console.error("页面错误:", e.message));
await page.goto(`http://localhost:${port}/index.html?render`);
await page.waitForFunction(() => window.MV_ready);
await page.evaluate(() => window.MV_ready);
if (args.project) await page.evaluate((p) => window.MV_setProject(p), JSON.parse(fs.readFileSync(path.resolve(args.project), "utf8")));

const duration = await page.evaluate(() => window.MV_duration);
const from = +(args.from || 0), to = Math.min(+(args.to || duration), duration);
const frames = Math.floor((to - from) * fps);
console.log(`渲染 ${from}s → ${to.toFixed(2)}s，${frames} 帧 @ ${fps}fps，${width}x${height} → ${out}`);

const ff = spawn("ffmpeg", [
  "-y", "-loglevel", "error",
  "-f", "image2pipe", "-framerate", String(fps), "-c:v", "mjpeg", "-i", "-",
  "-ss", String(from), "-t", String(to - from), "-i", path.join(ROOT, "assets/song.mp3"),
  "-map", "0:v", "-map", "1:a",
  "-vf", `scale=${width}:${height}:flags=lanczos`, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
  "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out
], { stdio: ["pipe", "inherit", "inherit"] });

const t0 = Date.now();
for (let f = 0; f < frames; f++) {
  const t = from + f / fps;
  const url = await page.evaluate((t) => window.MV_frameJPEG(t, 0.93), t);
  const buf = Buffer.from(url.slice(url.indexOf(",") + 1), "base64");
  if (!ff.stdin.write(buf)) await new Promise((r) => ff.stdin.once("drain", r));
  if (f % fps === 0) process.stdout.write(`\r  ${(t - from).toFixed(0)}s / ${(to - from).toFixed(0)}s  (${((f / Math.max(1, (Date.now() - t0) / 1000))).toFixed(1)} 帧/秒)   `);
}
ff.stdin.end();
await new Promise((r) => ff.on("close", r));
await browser.close(); server.close();
console.log(`\n完成：${out}`);
