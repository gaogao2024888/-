// 预览器 + 分镜/歌词编辑器 + 导出
(function () {
  const MV = window.MV, A = window.MV_ANALYSIS;
  const $ = (s) => document.querySelector(s);
  const STORE = "mv-mushengyushurou-project-v1";
  const RENDER = new URLSearchParams(location.search).has("render");
  const clone = (o) => JSON.parse(JSON.stringify(o));
  const r2 = (x) => Math.round(x * 100) / 100;

  // ---------- 工程加载 / 保存 ----------
  function loadProject() {
    if (!RENDER) try { const s = localStorage.getItem(STORE); if (s) return JSON.parse(s); } catch (e) {}
    return clone(window.MV_DEFAULT_PROJECT);
  }
  let project = loadProject();
  const save = () => { try { localStorage.setItem(STORE, JSON.stringify(project)); } catch (e) {} };

  const engine = new MV.Engine(project, A);
  const canvas = $("#stage"), ctx = canvas.getContext("2d");
  const audio = $("#audio");
  const fontsReady = Promise.all([
    document.fonts.load('40px "MaShanZheng"', "木生遇淑柔"), document.fonts.load('40px "NotoSerifSC"', "木生"),
    document.fonts.load('40px "ZCOOLXiaoWei"', "木生"), document.fonts.load('italic 500 40px "NotoSerifSC"', "Woo")
  ]).catch(() => {});

  // ---------- 渲染模式：供 tools/render.mjs 调用 ----------
  if (RENDER) {
    document.body.classList.add("render");
    canvas.width = 1920; canvas.height = 1080;
    window.MV_ready = fontsReady.then(() => true);
    window.MV_setProject = (p) => { project = p; engine.setProject(project); };
    window.MV_duration = A.duration;
    window.MV_renderAt = (t) => { engine.render(ctx, t); return true; };
    window.MV_frameJPEG = (t, q = 0.92) => { engine.render(ctx, t); return canvas.toDataURL("image/jpeg", q); };
    return;
  }

  let selShot = 0, selLyric = 0, safe = false, recording = false, dirtyTimeline = true;
  // 音频元数据未就绪时先记住要跳到的时间
  let pendingT = null;
  const now = () => (pendingT != null ? pendingT : audio.currentTime);
  audio.addEventListener("loadedmetadata", () => { if (pendingT != null) { audio.currentTime = pendingT; pendingT = null; draw(); } });
  const apply = (opts = {}) => { engine.setProject(project); save(); if (!opts.keepShotUI) { renderShotList(); renderShotEditor(); } if (opts.lyrics) renderLyrics(); dirtyTimeline = true; draw(); };

  // ---------- 主画面 ----------
  function draw() {
    engine.render(ctx, now(), { safeArea: safe });
    const i = engine.shotIndexAt(now()), sh = engine.shots[i];
    $("#shotBadge").textContent = `${sh.id} · ${sh.name}`;
    $("#timeLabel").textContent = MV.fmtTime(now());
    drawTimelines();
    syncNowMarkers(i);
  }
  let lastNowShot = -1, lastNowLyric = -1;
  function syncNowMarkers(i) {
    if (i !== lastNowShot) { lastNowShot = i; document.querySelectorAll(".shot-list li").forEach((li, k) => li.classList.toggle("now", k === i)); }
    const li = MV.lastIndex(engine.lines, now(), "start");
    if (li !== lastNowLyric) { lastNowLyric = li; document.querySelectorAll("#lyricRows tr").forEach((tr) => tr.classList.toggle("now", +tr.dataset.i === li)); }
  }
  function tick() {
    if (!audio.paused) {
      const sh = engine.shots[selShot];
      if ($("#optLoop").checked && sh && (now() >= sh._end - 0.02 || now() < sh.start - 0.5)) audio.currentTime = sh.start;
      draw();
    }
    requestAnimationFrame(tick);
  }

  // ---------- 时间轴 ----------
  const tlO = $("#tlOverview"), tlZ = $("#tlZoom");
  function fitCanvas(c) { const w = Math.round(c.clientWidth * devicePixelRatio), h = Math.round(c.getAttribute("height") * devicePixelRatio); if (c.width !== w || c.height !== h) { c.width = w; c.height = h; } }
  let overviewCache = null;
  function drawTimelines() {
    fitCanvas(tlO); fitCanvas(tlZ);
    const dpr = devicePixelRatio, dur = A.duration;
    // 全曲（静态部分缓存）
    if (dirtyTimeline || !overviewCache || overviewCache.width !== tlO.width) {
      overviewCache = document.createElement("canvas"); overviewCache.width = tlO.width; overviewCache.height = tlO.height;
      const c = overviewCache.getContext("2d"), W = tlO.width, H = tlO.height, X = (t) => (t / dur) * W;
      c.scale(1, 1);
      engine.shots.forEach((s, i) => {
        const P = engine.palette(s.palette);
        c.fillStyle = P.bg2; c.fillRect(X(s.start), 0, X(s._end) - X(s.start) - 1, 30 * dpr);
        c.fillStyle = P.ink; c.font = `${11 * dpr}px NotoSerifSC, serif`; c.save(); c.beginPath(); c.rect(X(s.start), 0, X(s._end) - X(s.start) - 2, 30 * dpr); c.clip();
        c.fillText(s.name, X(s.start) + 4 * dpr, 19 * dpr); c.restore();
        if (i === selShot) { c.strokeStyle = "#f1e6d6"; c.lineWidth = 2 * dpr; c.strokeRect(X(s.start) + 1, 1, X(s._end) - X(s.start) - 3, 30 * dpr - 2); }
      });
      c.fillStyle = "#b5a38f"; c.font = `${10 * dpr}px NotoSerifSC, serif`;
      (project.sections || []).forEach((s) => { c.fillRect(X(s.t), 32 * dpr, 1, 12 * dpr); c.fillText(s.name, X(s.t) + 3, 42 * dpr); });
      c.fillStyle = "#c08a55";
      engine.lines.forEach((l) => c.fillRect(X(l.start), 46 * dpr, Math.max(2, X(l.end) - X(l.start)), 8 * dpr));
      c.fillStyle = "#d9574a";
      A.strong.forEach((t) => c.fillRect(X(t), 58 * dpr, 1, 8 * dpr));
      c.strokeStyle = "#8fb07a"; c.lineWidth = dpr; c.beginPath();
      A.energy.v.forEach((v, i) => { const x = X(i * A.energy.hop), y = H - 2 - v * 26 * dpr; i ? c.lineTo(x, y) : c.moveTo(x, y); }); c.stroke();
      dirtyTimeline = false;
    }
    const o = tlO.getContext("2d");
    o.clearRect(0, 0, tlO.width, tlO.height); o.drawImage(overviewCache, 0, 0);
    const px = (now() / dur) * tlO.width; o.fillStyle = "#fff"; o.fillRect(px - dpr, 0, 2 * dpr, tlO.height);

    // 放大轨：±4 秒
    const z = tlZ.getContext("2d"), W = tlZ.width, H = tlZ.height, span = 8, t0 = now() - span / 2, X = (t) => ((t - t0) / span) * W;
    z.clearRect(0, 0, W, H);
    z.fillStyle = "#3a2c21"; for (let s = Math.ceil(t0); s < t0 + span; s++) { z.fillRect(X(s), 0, 1, H); z.fillStyle = "#6a5848"; z.font = `${10 * dpr}px sans-serif`; z.fillText(MV.fmtTime(s).slice(0, 5), X(s) + 3, H - 4); z.fillStyle = "#3a2c21"; }
    A.beats.forEach((t) => { if (t > t0 && t < t0 + span) { z.fillStyle = "#6a5848"; z.fillRect(X(t), H - 30 * dpr, 2, 12 * dpr); } });
    A.strong.forEach((t) => { if (t > t0 && t < t0 + span) { z.fillStyle = "#d9574a"; z.fillRect(X(t), H - 44 * dpr, 2, 26 * dpr); } });
    z.font = `${16 * dpr}px MaShanZheng, serif`; z.textBaseline = "top";
    engine.lines.forEach((l) => {
      if (l.end < t0 || l.start > t0 + span) return;
      z.fillStyle = "rgba(192,138,85,.18)"; z.fillRect(X(l.start), 4 * dpr, X(l.end) - X(l.start), 30 * dpr);
      l.chars.forEach((ch) => { if (!ch.c.trim()) return; z.fillStyle = ch.kw >= 0 ? "#d9574a" : "#f1e6d6"; z.fillRect(X(ch.t), 4 * dpr, 1, 30 * dpr); z.fillText(ch.c, X(ch.t) + 2, 10 * dpr); });
    });
    engine.shots.forEach((s) => { if (s.start > t0 && s.start < t0 + span) { z.fillStyle = "#8fb07a"; z.fillRect(X(s.start), 0, 2 * dpr, H); z.font = `${10 * dpr}px NotoSerifSC`; z.fillText(s.id, X(s.start) + 4, 36 * dpr); z.font = `${16 * dpr}px MaShanZheng, serif`; } });
    z.fillStyle = "#fff"; z.fillRect(W / 2 - dpr, 0, 2 * dpr, H);
  }
  tlO.addEventListener("click", (e) => seek((e.offsetX / tlO.clientWidth) * A.duration));
  tlZ.addEventListener("click", (e) => seek(now() + (e.offsetX / tlZ.clientWidth - 0.5) * 8));

  // ---------- 播放控制 ----------
  function seek(t) { t = Math.max(0, Math.min(A.duration - 0.01, t)); if (audio.readyState < 1) pendingT = t; else { pendingT = null; audio.currentTime = t; } const i = engine.shotIndexAt(now()); if (!$("#optLoop").checked && i !== selShot) { selShot = i; renderShotList(); renderShotEditor(); dirtyTimeline = true; } draw(); }
  function togglePlay() { if (audio.paused) audio.play(); else audio.pause(); }
  audio.addEventListener("play", () => { $("#btnPlay").textContent = "❚❚ 暂停"; $("#bigPlay").classList.add("hide"); });
  audio.addEventListener("pause", () => { $("#btnPlay").textContent = "▶ 播放"; draw(); }); // 大播放键只在首次出现，免得挡住歌词
  audio.addEventListener("seeked", draw);
  audio.addEventListener("ended", () => { if (recording) stopRecord(); });
  $("#btnPlay").onclick = togglePlay; $("#bigPlay").onclick = togglePlay;
  canvas.onclick = togglePlay;
  const fps = () => project.meta.fps || 30;
  $("#btnBack").onclick = () => seek(now() - 1); $("#btnFwd").onclick = () => seek(now() + 1);
  $("#btnFrameB").onclick = () => { audio.pause(); seek(now() - 1 / fps()); };
  $("#btnFrameF").onclick = () => { audio.pause(); seek(now() + 1 / fps()); };
  const gotoShot = (d) => { selShot = Math.max(0, Math.min(engine.shots.length - 1, engine.shotIndexAt(now() + 0.05) + d)); seek(engine.shots[selShot].start); renderShotList(); renderShotEditor(); dirtyTimeline = true; };
  $("#btnPrevShot").onclick = () => gotoShot(-1); $("#btnNextShot").onclick = () => gotoShot(1);
  $("#optRate").onchange = (e) => (audio.playbackRate = +e.target.value);
  $("#optSafe").onchange = (e) => { safe = e.target.checked; draw(); };
  $("#optQuality").onchange = (e) => { const w = +e.target.value; canvas.width = w; canvas.height = Math.round((w * 9) / 16); draw(); };

  document.addEventListener("keydown", (e) => {
    if (/INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return;
    const k = e.key;
    if (k === " ") { e.preventDefault(); togglePlay(); }
    else if (k === "ArrowLeft") seek(now() - (e.shiftKey ? 5 : 1));
    else if (k === "ArrowRight") seek(now() + (e.shiftKey ? 5 : 1));
    else if (k === ",") { audio.pause(); seek(now() - 1 / fps()); }
    else if (k === ".") { audio.pause(); seek(now() + 1 / fps()); }
    else if (k === "[") gotoShot(-1);
    else if (k === "]") gotoShot(1);
    else if (k === "l" || k === "L") { $("#optLoop").checked = !$("#optLoop").checked; }
    else if (k === "t" || k === "T") tapLyric();
  });

  // ---------- 标签页 ----------
  document.querySelectorAll(".tabs button").forEach((b) => (b.onclick = () => {
    document.querySelectorAll(".tabs button").forEach((x) => x.classList.toggle("on", x === b));
    document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("hidden", t.id !== "tab-" + b.dataset.tab));
  }));

  // ---------- 分镜面板 ----------
  const sceneTypes = Object.keys(MV.scenes);
  const ask = (msg) => (window.MV_ARTIFACT ? true : confirm(msg));
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  function renderShotList() {
    const ol = $("#shotList"); ol.innerHTML = "";
    engine.shots.forEach((s, i) => {
      const li = document.createElement("li");
      if (i === selShot) li.className = "sel";
      li.innerHTML = `<span class="sw" style="background:${engine.palette(s.palette).bg2}"></span><span class="st">${MV.fmtTime(s.start)}</span><span>${esc(s.name)}</span>`;
      li.onclick = () => { selShot = i; seek(s.start); renderShotList(); renderShotEditor(); dirtyTimeline = true; draw(); };
      ol.appendChild(li);
    });
  }
  function renderShotEditor() {
    const s = engine.shots[selShot]; if (!s) return;
    const pals = Object.keys(project.theme.palettes);
    const opt = (arr, v) => arr.map((x) => `<option ${x === v ? "selected" : ""}>${x}</option>`).join("");
    $("#shotEditor").innerHTML = `
      <div class="note"><b>${esc(s.id)}</b> · ${MV.fmtTime(s.start)} – ${MV.fmtTime(s._end)}（${(s._end - s.start).toFixed(1)} 秒）<br>${esc(s.note || "")}</div>
      <div class="row"><label>镜头名</label><input data-k="name" value="${esc(s.name)}"></div>
      <div class="row"><label>开始(秒)</label><div class="inline"><input data-k="start" type="number" step="0.01" value="${s.start}"><button data-act="setStart" title="设为当前播放时间">⏱ 当前</button></div></div>
      <div class="row"><label>场景模板</label><select data-k="scene">${opt(sceneTypes, s.scene)}</select></div>
      <div class="row"><label>配色</label><select data-k="palette">${opt(pals, s.palette)}</select></div>
      <div class="row"><label>转场</label><div class="inline"><select data-k="transition.type">${opt(["ink", "wheel", "ripple", "cut"], s.transition?.type)}</select><input data-k="transition.dur" type="number" step="0.1" min="0" value="${s.transition?.dur ?? 0}" title="转场时长(秒)"></div></div>
      <div class="row"><label>歌词版式</label><div class="inline"><select data-k="lyric.mode">${opt(["lower", "center", "upper", "vertical", "none"], s.lyric?.mode)}</select><input data-k="lyric.size" type="number" step="2" value="${s.lyric?.size ?? 72}" title="字号"></div></div>
      <div class="row"><label>画面说明</label><input data-k="note" value="${esc(s.note || "")}"></div>
      <div class="row"><label>参数 JSON</label><textarea data-k="params">${esc(JSON.stringify(s.params || {}, null, 1))}</textarea></div>
      <div class="btns"><button data-act="split">在当前时间拆分镜头</button><button data-act="del" class="danger">删除镜头</button></div>
      <p class="hint">改完立即生效并自动保存。转场：ink 淡入 / wheel 木轮圈擦除 / ripple 水波擦除 / cut 硬切。</p>`;
    const src = project.shots.find((x) => x.id === s.id);
    $("#shotEditor").querySelectorAll("[data-k]").forEach((el) => {
      el.onchange = () => {
        const k = el.dataset.k; let v = el.value;
        if (k === "params") { try { v = JSON.parse(v || "{}"); } catch (e) { el.style.outlineColor = "#d9574a"; return; } }
        if (el.type === "number") v = +v;
        const [a, b] = k.split(".");
        if (b) { src[a] = src[a] || {}; src[a][b] = v; } else src[a] = v;
        apply({ keepShotUI: k === "params" || k === "note" });
        if (k === "start") { selShot = engine.shots.findIndex((x) => x.id === s.id); renderShotList(); renderShotEditor(); }
      };
    });
    $("#shotEditor").querySelectorAll("[data-act]").forEach((el) => (el.onclick = () => {
      const act = el.dataset.act;
      if (act === "setStart") { src.start = r2(now()); apply(); }
      if (act === "split") {
        const id = "s" + Date.now().toString(36).slice(-4);
        project.shots.push({ ...clone(src), id, name: src.name + "（拆）", start: r2(now()), transition: { type: "cut", dur: 0 } });
        apply(); selShot = engine.shots.findIndex((x) => x.id === id); renderShotList(); renderShotEditor();
      }
      if (act === "del" && project.shots.length > 1 && ask(`删除镜头「${src.name}」？`)) { project.shots = project.shots.filter((x) => x !== src); selShot = Math.max(0, selShot - 1); apply(); }
    }));
  }

  // ---------- 歌词对齐面板 ----------
  function renderLyrics() {
    $("#lyricOffset").value = project.meta.lyricOffset || 0;
    project.lyrics.sort((a, b) => a.t - b.t);
    const tb = $("#lyricRows"); tb.innerHTML = "";
    project.lyrics.forEach((l, i) => {
      const tr = document.createElement("tr"); tr.dataset.i = i; if (i === selLyric) tr.className = "sel";
      tr.innerHTML = `<td><input type="number" step="0.01" value="${l.t}"></td><td><input type="text" value="${esc(l.text)}"></td><td><input type="text" value="${esc((l.kw || []).join(","))}" placeholder="逗号分隔"></td><td><button title="从这句前 1 秒开始试听">▶</button><button title="删除">×</button></td>`;
      const [ti, tx, kw] = tr.querySelectorAll("input"), [play, del] = tr.querySelectorAll("button");
      tr.onclick = () => { selLyric = i; tb.querySelectorAll("tr").forEach((r) => r.classList.toggle("sel", r === tr)); };
      ti.onchange = () => { l.t = r2(+ti.value); apply({ lyrics: true }); };
      tx.onchange = () => { l.text = tx.value; apply(); };
      kw.onchange = () => { l.kw = kw.value.split(/[,，]/).map((x) => x.trim()).filter(Boolean); apply(); };
      play.onclick = (e) => { e.stopPropagation(); seek(l.t + (project.meta.lyricOffset || 0) - 1); audio.play(); };
      del.onclick = (e) => { e.stopPropagation(); if (ask(`删除「${l.text}」？`)) { project.lyrics.splice(i, 1); apply({ lyrics: true }); } };
      tb.appendChild(tr);
    });
  }
  function tapLyric() {
    const l = project.lyrics[selLyric]; if (!l) return;
    l.t = r2(now() - (project.meta.lyricOffset || 0));
    selLyric = Math.min(project.lyrics.length - 1, selLyric + 1);
    apply({ lyrics: true });
    document.querySelector(`#lyricRows tr[data-i="${selLyric}"]`)?.scrollIntoView({ block: "nearest" });
  }
  $("#lyricOffset").onchange = (e) => { project.meta.lyricOffset = +e.target.value; apply(); };
  $("#btnAddLyric").onclick = () => { project.lyrics.push({ t: r2(now()), text: "新歌词", kw: [] }); apply({ lyrics: true }); };

  // ---------- 风格面板 ----------
  function renderTheme() {
    $("#themeMotif").innerHTML = `<b>主题：${esc(project.theme.name)}</b><br>贯穿元素 —— ${esc(project.theme.motif)}`;
    const keys = ["bg1", "bg2", "ink", "wood", "accent", "water"];
    $("#paletteView").innerHTML = `<div class="pal"><span></span>${keys.map((k) => `<span>${k}</span>`).join("")}</div>` +
      Object.entries(project.theme.palettes).map(([n, p]) => `<div class="pal"><span>${n}</span>${keys.map((k) => `<i style="background:${p[k]}" title="${p[k]}"></i>`).join("")}</div>`).join("");
  }

  // ---------- 工程 / 导出 ----------
  $("#metaTitle").value = project.meta.title || ""; $("#metaCredits").value = project.meta.credits || "";
  $("#metaTitle").onchange = (e) => { project.meta.title = e.target.value; apply(); };
  $("#metaCredits").onchange = (e) => { project.meta.credits = e.target.value; apply(); };
  const ART = !!window.MV_ARTIFACT; // 在线预览版：不能弹窗、不能下载 → 改为页面内确认 / 复制
  const download = (name, text, type) => { if (ART) return showText(name, text); const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([text], { type })); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 2000); };
  function showText(name, text) {
    let box = document.getElementById("textBox");
    if (!box) {
      box = document.createElement("div"); box.id = "textBox"; box.className = "textbox";
      box.innerHTML = `<div class="tb-inner"><div class="tb-head"><b></b><span><button id="tbCopy" class="primary">复制</button> <button id="tbClose">关闭</button></span></div><textarea id="tbText" readonly></textarea><p class="hint">在线预览版不能直接下载文件：复制后保存成同名文件，放进本地工程的 data/ 目录即可。</p></div>`;
      document.body.appendChild(box);
      box.querySelector("#tbClose").onclick = () => (box.hidden = true);
      box.querySelector("#tbCopy").onclick = () => { const ta = box.querySelector("#tbText"); navigator.clipboard.writeText(ta.value).then(() => (box.querySelector("#tbCopy").textContent = "已复制"), () => { ta.focus(); ta.select(); }); };
    }
    box.querySelector("b").textContent = name; box.querySelector("#tbText").value = text; box.querySelector("#tbCopy").textContent = "复制"; box.hidden = false;
  }
  const strip = (p) => { const c = clone(p); c.shots.forEach((s) => delete s._end); return c; };
  $("#btnExportJson").onclick = () => download("project.json", JSON.stringify(strip(project), null, 2), "application/json");
  $("#btnExportJs").onclick = () => download("project.js", "// 由预览页导出的工程文件\nwindow.MV_DEFAULT_PROJECT = " + JSON.stringify(strip(project), null, 2) + ";\n", "text/javascript");
  $("#fileImport").onchange = async (e) => {
    const f = e.target.files[0]; if (!f) return;
    try { const p = JSON.parse(await f.text()); if (!p.shots || !p.lyrics) throw new Error("缺少 shots / lyrics"); project = p; selShot = 0; apply({ lyrics: true }); renderTheme(); }
    catch (err) { alert("导入失败：" + err.message); }
  };
  $("#btnReset").onclick = () => { if (ask("恢复成 data/project.js 里的默认工程？本页的修改会丢失。")) { try { localStorage.removeItem(STORE); } catch (e) {} project = clone(window.MV_DEFAULT_PROJECT); selShot = 0; apply({ lyrics: true }); renderTheme(); } };

  // 浏览器内录制 WebM
  let rec = null, actx = null, dest = null;
  function stopRecord() { if (rec && rec.state !== "inactive") rec.stop(); }
  $("#btnRecord").onclick = () => {
    if (recording) { audio.pause(); stopRecord(); return; }
    if (location.protocol === "file:") { $("#recStatus").textContent = "直接双击打开的页面不能录制音频，请先运行 python3 -m http.server 再用 http://localhost:8000 打开。"; return; }
    try {
      if (!actx) { actx = new AudioContext(); const src = actx.createMediaElementSource(audio); dest = actx.createMediaStreamDestination(); src.connect(actx.destination); src.connect(dest); }
      actx.resume();
      $("#optQuality").value = "1920"; $("#optQuality").onchange({ target: $("#optQuality") });
      const stream = canvas.captureStream(fps());
      dest.stream.getAudioTracks().forEach((tr) => stream.addTrack(tr));
      const mime = ["video/webm;codecs=vp9,opus", "video/webm;codecs=vp8,opus", "video/webm"].find((m) => MediaRecorder.isTypeSupported(m));
      rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 14e6 });
      const chunks = []; rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
      rec.onstop = () => { recording = false; $("#btnRecord").textContent = "● 录制 WebM（1080p）"; $("#recStatus").textContent = "录制完成，已下载。"; const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob(chunks, { type: "video/webm" })); a.download = (project.meta.title || "mv") + ".webm"; a.click(); };
      $("#optLoop").checked = false; audio.playbackRate = 1; $("#optRate").value = "1";
      audio.currentTime = 0; rec.start(1000); recording = true; audio.play();
      $("#btnRecord").textContent = "■ 停止录制"; $("#recStatus").textContent = "录制中……请保持此标签页在前台，播完自动下载。";
    } catch (err) { $("#recStatus").textContent = "录制失败：" + err.message; }
  };

  if (ART) { $("#btnRecord").disabled = true; $("#recStatus").textContent = "在线预览版不能录制下载。成片 MP4 请在本地运行 node tools/render.mjs。"; }

  window.addEventListener("resize", () => { dirtyTimeline = true; draw(); });

  // ---------- 启动 ----------
  fontsReady.then(() => { renderShotList(); renderShotEditor(); renderLyrics(); renderTheme(); dirtyTimeline = true; draw(); requestAnimationFrame(tick); });
  // 方便在控制台调试
  window.MVAPP = { engine, get project() { return project; }, seek, draw };
})();
