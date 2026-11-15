// 绘图基元：宣纸、木轮（贯穿元素）、人物剪影、花瓣、山、涟漪、星星……
(function () {
  const MV = window.MV;
  const { clamp, lerp, hash, h2, noise, rgba, ease } = MV;
  const D = (MV.draw = {});
  const TAU = Math.PI * 2;

  // ---------- 宣纸底 ----------
  let paperTex = null;
  function getPaperTex(W, H) {
    if (paperTex && paperTex.width === W) return paperTex;
    paperTex = document.createElement("canvas");
    paperTex.width = W; paperTex.height = H;
    const c = paperTex.getContext("2d");
    for (let i = 0; i < 2600; i++) {               // 纸纤维
      const x = hash(i) * W, y = hash(i + 9999) * H, l = 6 + hash(i + 77) * 26, a = hash(i + 33) * TAU;
      c.strokeStyle = `rgba(90,60,30,${0.025 + hash(i + 5) * 0.04})`;
      c.lineWidth = 0.6 + hash(i + 8) * 0.8;
      c.beginPath(); c.moveTo(x, y); c.quadraticCurveTo(x + Math.cos(a) * l * 0.5 + 3, y + Math.sin(a) * l * 0.5 - 3, x + Math.cos(a) * l, y + Math.sin(a) * l); c.stroke();
    }
    for (let i = 0; i < 9000; i++) {               // 细小颗粒
      c.fillStyle = `rgba(80,50,20,${hash(i * 3.1) * 0.05})`;
      c.fillRect(hash(i * 1.7) * W, hash(i * 2.3) * H, 1.4, 1.4);
    }
    return paperTex;
  }
  D.background = (ctx, s, P, opts = {}) => {
    const { W, H } = s;
    const g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0, opts.top || P.bg1); g.addColorStop(1, opts.bottom || P.bg2);
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    // 柔和暗角
    const v = ctx.createRadialGradient(W / 2, H / 2, H * 0.35, W / 2, H / 2, H * 0.95);
    v.addColorStop(0, "rgba(0,0,0,0)"); v.addColorStop(1, `rgba(40,20,10,${opts.vignette ?? 0.18})`);
    ctx.fillStyle = v; ctx.fillRect(0, 0, W, H);
    if (opts.paper !== false) { ctx.globalAlpha = opts.paperAlpha ?? 1; ctx.drawImage(getPaperTex(W, H), 0, 0); ctx.globalAlpha = 1; }
  };

  // ---------- 木纹线（开场 & 木字） ----------
  D.woodGrain = (ctx, x, y, w, h, color, t, progress = 1, lines = 9, alpha = 0.5) => {
    ctx.save(); ctx.strokeStyle = color; ctx.lineCap = "round";
    for (let i = 0; i < lines; i++) {
      const yy = y + (i + 0.5) * (h / lines), p = clamp(progress * 1.4 - i * 0.04);
      if (p <= 0) continue;
      ctx.globalAlpha = alpha * (0.5 + 0.5 * hash(i + 3));
      ctx.lineWidth = 1.2 + hash(i) * 2.2;
      ctx.beginPath();
      const steps = 80;
      for (let k = 0; k <= steps * p; k++) {
        const u = k / steps, xx = x + u * w;
        const knot = Math.exp(-Math.pow((u - 0.3 - hash(i) * 0.4) * 6, 2)) * 22 * (hash(i + 1) - 0.4);
        const yv = yy + noise(u * 3 + i * 1.7 + t * 0.05) * (h / lines) * 0.35 + knot;
        k === 0 ? ctx.moveTo(xx, yv) : ctx.lineTo(xx, yv);
      }
      ctx.stroke();
    }
    ctx.restore();
  };

  // ---------- 木轮：整支 MV 的贯穿元素 ----------
  // o: { spokes, build(0-1 已装辐条比例), rim(0-1 轮圈画出比例), glow, pulse, alpha, wood, dark, glowColor, simple }
  D.wheel = (ctx, x, y, r, rot, P, o = {}) => {
    const spokes = o.spokes || 12, wood = o.wood || P.wood, dark = o.dark || P.woodDark;
    const build = o.build ?? 1, rimP = o.rim ?? 1, pulse = o.pulse || 0;
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot); ctx.globalAlpha *= o.alpha ?? 1;
    if (o.glow) { ctx.shadowColor = o.glowColor || P.glow; ctx.shadowBlur = 40 * o.glow; }
    const rw = Math.max(3, r * 0.11);
    // 轮圈
    if (rimP > 0) {
      ctx.lineCap = "round";
      ctx.strokeStyle = wood; ctx.lineWidth = rw;
      ctx.beginPath(); ctx.arc(0, 0, r - rw / 2, -Math.PI / 2, -Math.PI / 2 + TAU * rimP); ctx.stroke();
      ctx.shadowBlur = 0;
      if (!o.simple) {
        ctx.strokeStyle = dark; ctx.lineWidth = Math.max(1, rw * 0.12);
        ctx.beginPath(); ctx.arc(0, 0, r, -Math.PI / 2, -Math.PI / 2 + TAU * rimP); ctx.stroke();
        ctx.beginPath(); ctx.arc(0, 0, r - rw, -Math.PI / 2, -Math.PI / 2 + TAU * rimP); ctx.stroke();
        // 轮圈上的木纹
        ctx.globalAlpha *= 0.55; ctx.lineWidth = Math.max(0.8, rw * 0.07);
        for (let i = 0; i < 18; i++) {
          const a0 = (i / 18) * TAU + hash(i) * 0.2, len = 0.15 + hash(i + 4) * 0.2;
          if ((a0 + len) / TAU > rimP) continue;
          ctx.beginPath(); ctx.arc(0, 0, r - rw * (0.3 + hash(i + 9) * 0.4), a0 - Math.PI / 2, a0 + len - Math.PI / 2); ctx.stroke();
        }
        ctx.globalAlpha /= 0.55;
      }
    }
    // 辐条（可逐根装上）
    const nShow = build * spokes;
    for (let i = 0; i < spokes; i++) {
      const vis = clamp(nShow - i);
      if (vis <= 0) continue;
      const a = (i / spokes) * TAU;
      const len = (r - rw) * ease.out(vis);
      ctx.strokeStyle = wood; ctx.lineWidth = Math.max(2, r * 0.045) * (1 + pulse * 0.6);
      if (pulse > 0.01) { ctx.shadowColor = o.glowColor || P.glow; ctx.shadowBlur = 30 * pulse; }
      ctx.beginPath(); ctx.moveTo(Math.cos(a) * r * 0.14, Math.sin(a) * r * 0.14); ctx.lineTo(Math.cos(a) * len, Math.sin(a) * len); ctx.stroke();
      ctx.shadowBlur = 0;
    }
    // 轮毂
    ctx.fillStyle = dark; ctx.beginPath(); ctx.arc(0, 0, r * 0.16, 0, TAU); ctx.fill();
    ctx.fillStyle = wood; ctx.beginPath(); ctx.arc(0, 0, r * 0.08, 0, TAU); ctx.fill();
    ctx.restore();
  };

  // ---------- 人物剪影（剪纸风） ----------
  // kind: 'he' | 'she' | 'crowd'; y 是脚底
  D.figure = (ctx, x, y, h, color, o = {}) => {
    const kind = o.kind || "crowd", face = o.face ?? 1;
    ctx.save(); ctx.translate(x, y); ctx.scale(face * (o.sx ?? 1), 1); ctx.rotate(o.rot || 0);
    ctx.globalAlpha *= o.alpha ?? 1; ctx.fillStyle = color;
    const head = h * 0.13, bodyTop = -h + head * 2.1;
    // 长衫
    ctx.beginPath();
    const sw = kind === "she" ? h * 0.2 : h * 0.17, bw = kind === "she" ? h * 0.36 : h * 0.27;
    ctx.moveTo(-sw / 2, bodyTop);
    ctx.quadraticCurveTo(-sw * 0.75, bodyTop + h * 0.03, -sw * 0.7, bodyTop + h * 0.2);
    ctx.lineTo(-bw / 2, 0); ctx.lineTo(bw / 2, 0);
    ctx.lineTo(sw * 0.7, bodyTop + h * 0.2);
    ctx.quadraticCurveTo(sw * 0.75, bodyTop + h * 0.03, sw / 2, bodyTop);
    ctx.closePath(); ctx.fill();
    // 脖子 + 头
    ctx.fillRect(-head * 0.3, bodyTop - head * 0.5, head * 0.6, head * 0.7);
    ctx.beginPath(); ctx.arc(head * 0.1, bodyTop - head * 1.05, head, 0, TAU); ctx.fill();
    if (kind === "she") {
      ctx.beginPath(); ctx.arc(-head * 0.75, bodyTop - head * 1.5, head * 0.55, 0, TAU); ctx.fill(); // 发髻
      ctx.strokeStyle = o.accent || color; ctx.lineWidth = Math.max(1.5, h * 0.012);
      ctx.beginPath(); ctx.moveTo(-head * 1.4, bodyTop - head * 1.9); ctx.lineTo(-head * 0.1, bodyTop - head * 1.2); ctx.stroke(); // 发簪
      if (o.accent) { ctx.fillStyle = o.accent; ctx.beginPath(); ctx.arc(-head * 1.4, bodyTop - head * 1.9, h * 0.014, 0, TAU); ctx.fill(); }
    } else if (kind === "he") {
      ctx.beginPath(); ctx.arc(-head * 0.2, bodyTop - head * 2.05, head * 0.38, 0, TAU); ctx.fill(); // 发髻
    }
    // 腰带
    if (o.accent && kind !== "crowd") { ctx.fillStyle = o.accent; ctx.fillRect(-sw * 0.72, bodyTop + h * 0.2, sw * 1.44, h * 0.025); }
    // 红晕
    if (o.blush) { ctx.fillStyle = rgba("#FF6F6F", 0.55 * o.blush); ctx.beginPath(); ctx.arc(head * 0.6, bodyTop - head * 0.85, head * 0.28, 0, TAU); ctx.fill(); }
    ctx.restore();
  };

  // ---------- 鸟（惊鸿） ----------
  D.bird = (ctx, x, y, s, flap, color) => {
    ctx.save(); ctx.translate(x, y); ctx.strokeStyle = color; ctx.lineWidth = s * 0.09; ctx.lineCap = "round";
    const f = Math.sin(flap) * 0.6;
    ctx.beginPath(); ctx.moveTo(-s, -s * f); ctx.quadraticCurveTo(-s * 0.45, -s * 0.35 - s * f * 0.5, 0, 0); ctx.quadraticCurveTo(s * 0.45, -s * 0.35 - s * f * 0.5, s, -s * f); ctx.stroke();
    ctx.restore();
  };

  // ---------- 花瓣 ----------
  D.petal = (ctx, x, y, s, rot, color, alpha = 1) => {
    ctx.save(); ctx.translate(x, y); ctx.rotate(rot); ctx.globalAlpha *= alpha; ctx.fillStyle = color;
    ctx.beginPath(); ctx.moveTo(0, -s);
    ctx.bezierCurveTo(s * 0.9, -s * 0.6, s * 0.7, s * 0.7, 0, s);
    ctx.bezierCurveTo(-s * 0.7, s * 0.7, -s * 0.9, -s * 0.6, -s * 0.18, -s * 0.85);
    ctx.lineTo(0, -s * 0.6); ctx.closePath(); ctx.fill();
    ctx.restore();
  };
  // 持续飘落的花瓣场（无状态）
  D.petalField = (ctx, s, P, o = {}) => {
    const { W, H, t } = s, n = o.n ?? 40, seed = o.seed ?? 1, wind = o.wind ?? 30, fall = o.fall ?? 60;
    for (let i = 0; i < n; i++) {
      const k = i + seed * 1000, sp = 0.5 + hash(k + 1);
      const span = H + 200, spanX = W + 300;
      const y = ((hash(k + 2) * span + t * fall * sp) % span) - 100;
      const gust = o.gust ? o.gust * 900 * sp : 0;
      let x = (hash(k + 3) * spanX + t * wind * sp + gust + noise(t * 0.4 + k) * 60) % spanX;
      if (x < 0) x += spanX; x -= 150;
      const size = (8 + hash(k + 4) * 14) * (o.size ?? 1);
      const col = hash(k + 5) > 0.7 ? (o.color2 || "#FFFFFF") : o.color || P.petal;
      D.petal(ctx, x, y, size, t * (0.6 + hash(k + 6)) * 2 + k, col, (o.alpha ?? 0.85) * (0.6 + 0.4 * hash(k + 7)));
    }
  };
  // 强拍迸发：times 中每个时刻从 (x,y) 炸开一簇
  D.burst = (ctx, s, P, times, o = {}) => {
    const life = o.life ?? 1.8, n = o.n ?? 18, t = s.t;
    for (let b = 0; b < times.length; b++) {
      const age = t - times[b];
      if (age < 0 || age > life) continue;
      const pAge = age / life;
      for (let j = 0; j < n; j++) {
        const a = h2(times[b] * 31, j) * TAU, sp = (o.speed ?? 520) * (0.4 + h2(times[b], j + 50));
        const d = sp * (1 - Math.pow(1 - pAge, 2)) * 0.9;
        const x = o.x + Math.cos(a) * d, y = o.y + Math.sin(a) * d + 120 * pAge * pAge;
        const col = h2(j, times[b]) > 0.6 ? (o.color2 || P.glow) : o.color || P.petal;
        D.petal(ctx, x, y, (10 + h2(j, 7) * 10) * (o.size ?? 1), a + age * 4, col, (1 - pAge) * (o.alpha ?? 1));
      }
    }
  };

  // ---------- 远山 ----------
  D.hills = (ctx, s, baseY, amp, color, seed, offset = 0, freq = 0.0025, alpha = 1) => {
    const { W, H } = s;
    ctx.save(); ctx.globalAlpha *= alpha; ctx.fillStyle = color; ctx.beginPath(); ctx.moveTo(0, H);
    for (let x = 0; x <= W; x += 12) {
      const u = (x + offset) * freq + seed * 10;
      ctx.lineTo(x, baseY - (noise(u) * 0.6 + noise(u * 2.3 + 5) * 0.3 + 0.5) * amp);
    }
    ctx.lineTo(W, H); ctx.closePath(); ctx.fill(); ctx.restore();
  };

  // ---------- 涟漪（带隐约辐条，呼应木轮） ----------
  D.ripples = (ctx, x, y, age, color, o = {}) => {
    if (age < 0) return;
    const rings = o.rings ?? 4, life = o.life ?? 2.6, maxR = o.maxR ?? 520, flat = o.flat ?? 0.32;
    ctx.save(); ctx.strokeStyle = color; ctx.lineCap = "round";
    for (let i = 0; i < rings; i++) {
      const a = age - i * 0.28; if (a < 0 || a > life) continue;
      const p = a / life, r = maxR * ease.out(p);
      ctx.globalAlpha = (1 - p) * 0.8; ctx.lineWidth = 3.5 * (1 - p) + 1;
      ctx.beginPath(); ctx.ellipse(x, y, r, r * flat, 0, 0, TAU); ctx.stroke();
      if (i === 0 && o.spokes) {
        ctx.globalAlpha = (1 - p) * 0.35; ctx.lineWidth = 1.5;
        for (let k = 0; k < 12; k++) { const an = (k / 12) * TAU; ctx.beginPath(); ctx.moveTo(x + Math.cos(an) * r * 0.15, y + Math.sin(an) * r * 0.15 * flat); ctx.lineTo(x + Math.cos(an) * r, y + Math.sin(an) * r * flat); ctx.stroke(); }
      }
    }
    ctx.restore();
  };

  // ---------- 星星 ----------
  D.stars = (ctx, s, color, n = 140, maxY = 0.62) => {
    const { W, H, t } = s;
    ctx.save(); ctx.fillStyle = color;
    for (let i = 0; i < n; i++) {
      const x = hash(i + 0.3) * W, y = hash(i + 7.7) * H * maxY, tw = 0.5 + 0.5 * Math.sin(t * (1 + hash(i) * 2.5) + i);
      ctx.globalAlpha = 0.25 + 0.75 * tw * hash(i + 2);
      const r = 0.8 + hash(i + 5) * 2.2;
      ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill();
      if (r > 2.5) { ctx.globalAlpha *= 0.5; ctx.fillRect(x - r * 3, y - 0.5, r * 6, 1); ctx.fillRect(x - 0.5, y - r * 3, 1, r * 6); }
    }
    ctx.restore();
  };

  // ---------- 线描单车（progress 0-1 逐笔画出） ----------
  D.bicycle = (ctx, x, y, s, wheelRot, P, o = {}) => {
    const r = s * 0.42, wb = s * 1.25, p = o.progress ?? 1;
    const back = { x: x - wb / 2, y: y - r }, front = { x: x + wb / 2, y: y - r };
    const crank = { x: x - wb * 0.05, y: y - r }, seat = { x: x - wb * 0.18, y: y - r - s * 0.62 }, head = { x: x + wb * 0.36, y: y - r - s * 0.66 };
    D.wheel(ctx, back.x, back.y, r, wheelRot, P, { rim: clamp(p * 3), build: clamp(p * 3 - 0.3), pulse: o.pulse, spokes: 10 });
    D.wheel(ctx, front.x, front.y, r, wheelRot, P, { rim: clamp(p * 3 - 0.5), build: clamp(p * 3 - 0.8), pulse: o.pulse, spokes: 10 });
    const segs = [[back, crank], [crank, seat], [seat, back], [seat, head], [crank, head], [head, front]];
    ctx.save(); ctx.strokeStyle = o.frame || P.woodDark; ctx.lineWidth = s * 0.05; ctx.lineCap = "round"; ctx.lineJoin = "round";
    const fp = clamp((p - 0.35) / 0.65) * segs.length;
    segs.forEach(([a, b], i) => {
      const q = clamp(fp - i); if (q <= 0) return;
      ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(lerp(a.x, b.x, q), lerp(a.y, b.y, q)); ctx.stroke();
    });
    if (fp >= segs.length) {
      ctx.beginPath(); ctx.moveTo(head.x - s * 0.12, head.y - s * 0.06); ctx.lineTo(head.x + s * 0.1, head.y - s * 0.1); ctx.stroke(); // 车把
      ctx.lineWidth = s * 0.08; ctx.beginPath(); ctx.moveTo(seat.x - s * 0.1, seat.y); ctx.lineTo(seat.x + s * 0.1, seat.y); ctx.stroke(); // 车座
      ctx.lineWidth = s * 0.04; ctx.beginPath(); ctx.moveTo(back.x, back.y - r * 0.05); ctx.lineTo(back.x + s * 0.05, seat.y + s * 0.08); ctx.lineTo(seat.x - s * 0.1, seat.y + s * 0.08); ctx.stroke(); // 后座架
    }
    ctx.restore();
    return { back, front, seat, head, rear: { x: back.x + s * 0.02, y: seat.y + s * 0.08 } };
  };

  // 坐在单车上的两人（他在前座，她侧坐后座）
  D.riders = (ctx, pts, s, P, o = {}) => {
    const col = o.color || P.ink, he = o.he ?? 1, she = o.she ?? 1, h = s * 0.62;
    const ac = o.plain ? null : P.accent, acw = o.plain ? null : P.wood;
    if (she > 0) D.figure(ctx, pts.rear.x - s * 0.03, pts.rear.y + h * 0.42 - (1 - she) * s * 0.1, h * 0.94, col, { kind: "she", face: 1, accent: ac, alpha: she, blush: o.blush, rot: -0.04 });
    if (he > 0) D.figure(ctx, pts.seat.x + s * 0.06, pts.seat.y + h * 0.42, h, col, { kind: "he", face: 1, accent: acw, alpha: he, rot: 0.12 });
  };

  // 发光圆
  D.glow = (ctx, x, y, r, color, a = 1) => {
    const g = ctx.createRadialGradient(x, y, 0, x, y, r);
    g.addColorStop(0, rgba(color, a)); g.addColorStop(1, rgba(color, 0));
    ctx.fillStyle = g; ctx.fillRect(x - r, y - r, r * 2, r * 2);
  };
})();
