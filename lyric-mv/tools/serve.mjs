// 本地预览服务器（支持 Range 请求，音频才能拖动进度）。用法：node tools/serve.mjs [端口]
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PORT = +(process.argv[2] || 8000);
const types = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css", ".woff2": "font/woff2", ".mp3": "audio/mpeg", ".json": "application/json", ".lrc": "text/plain; charset=utf-8", ".md": "text/plain; charset=utf-8" };
http.createServer((req, res) => {
  let p = path.join(ROOT, decodeURIComponent(req.url.split("?")[0]));
  if (p.endsWith(path.sep)) p = path.join(p, "index.html");
  if (!p.startsWith(ROOT) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end("404"); }
  const size = fs.statSync(p).size, type = types[path.extname(p)] || "application/octet-stream";
  const m = /bytes=(\d*)-(\d*)/.exec(req.headers.range || "");
  if (m) {
    const start = m[1] ? +m[1] : size - +m[2], end = m[1] && m[2] ? +m[2] : size - 1;
    res.writeHead(206, { "Content-Type": type, "Content-Range": `bytes ${start}-${end}/${size}`, "Accept-Ranges": "bytes", "Content-Length": end - start + 1 });
    return fs.createReadStream(p, { start, end }).pipe(res);
  }
  res.writeHead(200, { "Content-Type": type, "Content-Length": size, "Accept-Ranges": "bytes", "Cache-Control": "no-cache" });
  fs.createReadStream(p).pipe(res);
}).listen(PORT, () => console.log(`预览地址：http://localhost:${PORT}/`));
