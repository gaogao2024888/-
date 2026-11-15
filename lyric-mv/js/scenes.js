// 场景模板。每个场景 = function(ctx, s)，s 见 engine.sceneState：
//   s.t 全局时间  s.lt 镜头内时间  s.p 镜头进度  s.P 配色  s.env 节拍  s.cue("关键词") 该词被唱到的时刻
// 画面事件都用 s.cue() 挂在歌词上 —— 改了歌词时间，画面会自动跟着走
(function () {
  const MV = window.MV, D = MV.draw;
  const { clamp, lerp, ease, bump, hash, noise, rgba, prog } = MV;
  const S = (MV.scenes = MV.scenes || {});
  const TAU = Math.PI * 2;

  // ============ 序：木轮入画 ============
  S.title = (ctx, s) => {
    const { W, H, t, P, env } = s;
    D.background(ctx, s, P);
    // 木纹铺开
    D.woodGrain(ctx, 0, H * 0.62, W, H * 0.3, P.wood, t, ease.out(prog(t, 0.3, 6)), 11, 0.35);
    D.woodGrain(ctx, 0, H * 0.04, W, H * 0.16, P.wood, t, ease.out(prog(t, 1.5, 6)), 5, 0.18);
    // 木轮从左滚入
    const r = 190, arrive = ease.out(prog(t, 1.8, 4.6));
    const x = lerp(-r * 1.4, W / 2, arrive), y = H * 0.44;
    const rot = (x - W / 2) / r + t * 0.15;
    D.glow(ctx, x, y, r * 2.2, P.glow, 0.45 + env.beatPulse * 0.25 * arrive);
    D.wheel(ctx, x, y, r * (1 + env.beatPulse * 0.03 * arrive), rot, P, { pulse: env.strongPulse * arrive });
    // 标题逐字晕开（主歌开始前收起）
    const title = s.E.P.meta.title || "木生遇淑柔";
    const hide = ease.inOut(prog(t, 12.2, 1.8));
    ctx.save();
    ctx.font = `150px "${s.E.P.theme.fonts.title}", serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
    [...title].forEach((c, i) => {
      const ct = 5.2 + i * 0.55, a = ease.out(prog(t, ct, 0.9));
      if (a <= 0) return;
      ctx.save();
      ctx.globalAlpha = a * (1 - hide);
      const cx = W / 2 + (i - (title.length - 1) / 2) * 170;
      ctx.translate(cx, H * 0.79 - (1 - a) * 30 - hide * 40); ctx.scale(0.9 + 0.1 * a, 0.9 + 0.1 * a);
      ctx.shadowColor = rgba(P.bg1, 1); ctx.shadowBlur = 20;
      ctx.fillStyle = c === "遇" ? P.accent : P.ink; ctx.fillText(c, 0, 0);
      ctx.restore();
    });
    ctx.restore();
    // 两个小人在木轮两侧出现
    const fa = ease.out(prog(t, 8.2, 1.6)) * (1 - hide * 0.3);
    D.figure(ctx, W / 2 - 420, H * 0.62, 190, P.ink, { kind: "he", face: 1, alpha: fa, accent: P.wood });
    D.figure(ctx, W / 2 + 420, H * 0.62, 180, P.ink, { kind: "she", face: -1, alpha: fa, accent: P.accent });
  };

  // ============ 人海一瞥 ============
  S.crowd = (ctx, s) => {
    const { W, H, t, lt, P, env } = s;
    D.background(ctx, s, P);
    const tHit = s.cue("相撞"), tBird = s.cue("惊鸿"), tRush = s.cue("匆忙"), tEye = s.cue("眉目好看");
    const rush = ease.inOut(prog(t, tRush - 0.4, 0.8)) * (1 - ease.inOut(prog(t, tEye - 1.6, 1)));
    const eyeP = ease.inOut(prog(t, tEye - 1.4, 1.4));
    // 大钟似的木轮在背景（匆忙时飞转）
    const spin = t * 0.3 + rush * (t - tRush) * 3;
    D.wheel(ctx, W / 2, H * 0.42, 430, spin, P, { alpha: 0.08 + rush * 0.12, simple: true, spokes: 12 });
    // 三层人群，左右流动
    const layers = [{ y: 0.66, h: 150, n: 22, sp: 30, a: 0.35 }, { y: 0.78, h: 210, n: 16, sp: 55, a: 0.55 }, { y: 0.96, h: 300, n: 10, sp: 85, a: 0.75 }];
    ctx.save(); ctx.globalAlpha = 1 - eyeP * 0.85;
    layers.forEach((L, li) => {
      for (let i = 0; i < L.n; i++) {
        const k = li * 100 + i, dir = hash(k) > 0.5 ? 1 : -1, span = W + 400;
        let x = (hash(k + 1) * span + dir * (t * L.sp * (0.6 + hash(k + 2)) + rush * (t - tRush) * L.sp * 6)) % span;
        if (x < 0) x += span; x -= 200;
        const bob = Math.abs(Math.sin(t * 4 + k)) * 6;
        if (rush > 0.05) { ctx.fillStyle = rgba(P.crowd, 0.12 * rush); ctx.fillRect(x - dir * 160, H * L.y - L.h * 0.7, dir * 160, L.h * 0.5); }
        D.figure(ctx, x, H * L.y - bob, L.h * (0.85 + hash(k + 3) * 0.3), P.crowd, { kind: hash(k + 4) > 0.5 ? "she" : "crowd", face: dir, alpha: L.a });
      }
    });
    ctx.restore();
    // 主角两人：站在人流中
    const hx = W * 0.27, sx = W * 0.73, fy = H * 0.86, fa = (1 - eyeP);
    D.figure(ctx, hx, fy, 330, P.ink, { kind: "he", face: 1, accent: P.wood, alpha: fa });
    D.figure(ctx, sx, fy, 315, P.ink, { kind: "she", face: -1, accent: P.accent, alpha: fa, blush: ease.out(prog(t, tHit, 1)) });
    // 目光相撞
    const g = ease.out(prog(t, tHit - 0.6, 0.6));
    if (g > 0 && fa > 0) {
      const y1 = fy - 330 * 0.87, y2 = fy - 315 * 0.87, mx = W / 2, my = (y1 + y2) / 2 - 40;
      ctx.save(); ctx.globalAlpha = fa * (1 - prog(t, tBird + 1.5, 1.2)); ctx.strokeStyle = P.accent; ctx.lineWidth = 3; ctx.setLineDash([14, 12]); ctx.lineDashOffset = -t * 40;
      ctx.beginPath(); ctx.moveTo(hx + 40, y1); ctx.lineTo(lerp(hx + 40, mx, g), lerp(y1, my, g)); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(sx - 40, y2); ctx.lineTo(lerp(sx - 40, mx, g), lerp(y2, my, g)); ctx.stroke();
      ctx.restore();
      const spark = bump(t, tHit, 1.2);
      if (spark > 0) { D.glow(ctx, mx, my, 160 * spark + 30, P.glow, spark); D.wheel(ctx, mx, my, 40 + 30 * spark, t * 6, P, { alpha: spark, simple: true, glow: 1 }); }
    }
    // 惊鸿：白鸟掠过
    const bp = prog(t, tBird - 0.3, 2.8);
    if (bp > 0 && bp < 1) {
      for (let k = 0; k < 3; k++) {
        const q = clamp(bp - k * 0.06);
        D.bird(ctx, lerp(-120, W + 120, ease.inOut(q)), H * 0.25 - Math.sin(q * Math.PI) * 120 + k * 40, 46 - k * 10, t * 12 + k, k === 0 ? "#FFFFFF" : rgba(P.ink, 0.5));
      }
    }
    // 眉目好看：眼睛 + 木轮瞳孔
    if (eyeP > 0) {
      const cx = W / 2, cy = H * 0.42, ew = 520 * eyeP, open = ease.out(prog(t, tEye - 0.4, 0.9));
      ctx.save(); ctx.strokeStyle = P.ink; ctx.lineWidth = 9; ctx.lineCap = "round"; ctx.globalAlpha = eyeP;
      const blink = 1 - bump(t, tEye + 2.2, 0.35);
      ctx.beginPath(); ctx.moveTo(cx - ew, cy); ctx.quadraticCurveTo(cx, cy - 300 * open * blink, cx + ew, cy); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(cx - ew, cy); ctx.quadraticCurveTo(cx, cy + 240 * open * blink, cx + ew, cy); ctx.stroke();
      // 睫毛
      for (let i = -3; i <= 3; i++) { const u = i / 4, bx = cx + u * ew * 0.8, by = cy - (1 - u * u) * 150 * open * blink; ctx.lineWidth = 5; ctx.beginPath(); ctx.moveTo(bx, by); ctx.lineTo(bx + u * 40, by - 50 * open); ctx.stroke(); }
      ctx.restore();
      ctx.save(); ctx.beginPath(); ctx.moveTo(cx - ew, cy); ctx.quadraticCurveTo(cx, cy - 300 * open * blink, cx + ew, cy); ctx.quadraticCurveTo(cx, cy + 240 * open * blink, cx + ew * -1, cy); ctx.clip();
      D.wheel(ctx, cx, cy, 120, t * 0.6, P, { glow: 0.5 + env.beatPulse * 0.5, pulse: env.beatPulse * 0.5 });
      ctx.restore();
    }
  };

  // ============ 名字 ============
  S.names = (ctx, s) => {
    const { W, H, t, P, env } = s;
    D.background(ctx, s, P);
    const tMu = s.cue("木头"), tSheng = s.cue("生命"), tRou = s.cue("温柔"), tSwim = s.cue("慢慢游"), tTurn = s.cue("转身");
    const drift = ease.inOut(prog(t, tSwim - 0.3, 3.2));
    const fadeBig = 1 - ease.inOut(prog(t, tTurn - 0.6, 1.2)) * 0.75;
    const lx = lerp(W * 0.26, W * 0.4, drift), rx = lerp(W * 0.74, W * 0.6, drift), cy = H * 0.42;
    const font = s.E.P.theme.fonts.title;
    // 巨大的“木”：木纹填充
    const muA = ease.out(prog(t, tMu - 0.5, 1));
    if (muA > 0) {
      ctx.save(); ctx.globalAlpha = muA * fadeBig; ctx.font = `520px "${font}", serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.fillStyle = rgba(P.wood, 0.22); ctx.fillText("木", lx, cy + Math.sin(t) * 6);
      ctx.save(); ctx.beginPath(); ctx.rect(lx - 300, cy - 300, 600, 600); ctx.clip();
      ctx.globalCompositeOperation = "source-atop"; ctx.restore();
      D.woodGrain(ctx, lx - 260, cy - 260, 520, 520, P.wood, t, ease.out(prog(t, tMu - 0.3, 2)), 14, 0.35 * muA * fadeBig);
      ctx.restore();
    }
    // “生”：从地面长出嫩芽
    const sh = ease.out(prog(t, tSheng - 0.2, 1.6));
    if (sh > 0) {
      ctx.save(); ctx.globalAlpha = fadeBig; ctx.strokeStyle = "#6E9A57"; ctx.lineWidth = 8; ctx.lineCap = "round";
      const bx = lx, by = H * 0.86, top = by - 260 * sh;
      ctx.beginPath(); ctx.moveTo(bx, by); ctx.quadraticCurveTo(bx + 30 * Math.sin(t * 1.5), (by + top) / 2, bx, top); ctx.stroke();
      ctx.fillStyle = "#86B26A";
      [[-1, 0.55], [1, 0.75], [-1, 0.95]].forEach(([d, f], i) => {
        const lp = clamp(sh * 1.3 - i * 0.2); if (lp <= 0) return;
        ctx.save(); ctx.translate(bx, by - 260 * sh * f); ctx.rotate(d * (0.7 + Math.sin(t * 2 + i) * 0.1)); ctx.scale(lp, lp);
        ctx.beginPath(); ctx.ellipse(d * 38, 0, 40, 15, 0, 0, TAU); ctx.fill(); ctx.restore();
      });
      ctx.restore();
    }
    // “柔”：流水
    const ra = ease.out(prog(t, tRou - 0.6, 1.2));
    if (ra > 0) {
      ctx.save(); ctx.globalAlpha = ra * fadeBig; ctx.font = `520px "${font}", serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.fillStyle = rgba(P.water, 0.22); ctx.fillText("柔", rx, cy + Math.sin(t + 1) * 6);
      ctx.strokeStyle = P.water; ctx.lineCap = "round";
      for (let i = 0; i < 7; i++) {
        ctx.lineWidth = 3 + (i % 3); ctx.globalAlpha = ra * fadeBig * (0.25 + 0.1 * (i % 3));
        ctx.beginPath();
        for (let x = 0; x <= 700; x += 10) { const xx = rx - 350 + x, yy = cy + 120 + i * 32 + Math.sin(x * 0.015 + t * 2 + i) * 14; x === 0 ? ctx.moveTo(xx, yy) : ctx.lineTo(xx, yy); }
        ctx.stroke();
      }
      ctx.restore();
    }
    // 慢慢游：两个名字之间系一根丝带
    if (drift > 0) {
      ctx.save(); ctx.strokeStyle = P.accent; ctx.lineWidth = 4; ctx.globalAlpha = drift * fadeBig * 0.8; ctx.lineCap = "round";
      ctx.beginPath(); ctx.moveTo(lx + 120, cy);
      ctx.bezierCurveTo((lx + rx) / 2 - 80, cy - 140 + Math.sin(t * 2) * 30, (lx + rx) / 2 + 80, cy + 140 - Math.sin(t * 2) * 30, lerp(lx + 120, rx - 120, ease.out(drift)), cy);
      ctx.stroke(); ctx.restore();
    }
    // 转身：两人背对，再缓缓回头
    const tp = ease.out(prog(t, tTurn - 0.8, 1.2));
    if (tp > 0) {
      const flip = Math.cos(clamp((t - tTurn - 1.0) / 1.4) * Math.PI); // 1 → -1：回头
      D.figure(ctx, W / 2 - 150, H * 0.7, 270, P.ink, { kind: "he", face: -1 * Math.sign(flip || 1), sx: Math.max(0.15, Math.abs(flip)), alpha: tp, accent: P.wood });
      D.figure(ctx, W / 2 + 150, H * 0.7, 258, P.ink, { kind: "she", face: 1, alpha: tp, accent: P.accent });
      D.wheel(ctx, lerp(-100, W + 100, prog(t, tTurn, 3)), H * 0.7 - 50, 50, t * 5, P, { alpha: tp * 0.8 });
    }
    // 底部浮动的小木轮点缀（节拍）
    D.wheel(ctx, W * 0.5, H * 0.1, 26, t, P, { alpha: 0.5, pulse: env.beatPulse, simple: true });
  };

  // ============ 溪水 ============
  S.stream = (ctx, s) => {
    const { W, H, t, lt, P, env } = s;
    const horizon = H * 0.6;
    D.background(ctx, s, P, { paperAlpha: 0.6 });
    const tDay = s.cue("后天"), tFall = s.cue("溪水"), tSmile = s.cue("相视一笑");
    // 木轮太阳：“后天”时划过天空（日升日落）
    const sunP = prog(t, tDay - 0.5, 3.2);
    const sx = lerp(W * 0.15, W * 0.85, ease.inOut(sunP)), sy = horizon - 120 - Math.sin(sunP * Math.PI) * 300;
    D.glow(ctx, sx, sy, 260, P.glow, 0.7);
    D.wheel(ctx, sx, sy, 80, t * 0.5, P, { simple: true, glow: 0.6, alpha: 0.9 });
    D.hills(ctx, s, horizon, 160, rgba(P.water, 0.35), 3, t * 6);
    D.hills(ctx, s, horizon + 10, 90, rgba(P.ink, 0.25), 7, t * 12);
    // 水面
    const wg = ctx.createLinearGradient(0, horizon, 0, H);
    wg.addColorStop(0, rgba(P.water, 0.85)); wg.addColorStop(1, rgba(P.ink, 0.75));
    ctx.fillStyle = wg; ctx.fillRect(0, horizon, W, H - horizon);
    // 倒影 & 人物
    const hx = W * 0.4, shx = W * 0.6, bank = horizon;
    const fallP = prog(t, tFall - 0.2, 0.7);
    const heY = bank + ease.in(fallP) * 160, heRot = ease.out(fallP) * 0.5;
    const blush = ease.out(prog(t, tSmile, 1));
    [[hx, heY, "he", 1, heRot, P.wood, fallP < 1], [shx, bank, "she", -1, 0, P.accent, true]].forEach(([x, y, kind, face, rot, ac, showRef]) => {
      if (showRef) { ctx.save(); ctx.translate(0, bank * 2); ctx.scale(1, -1); D.figure(ctx, x, y, 260, rgba(P.ink, 0.25), { kind, face, rot }); ctx.restore(); }
    });
    // 水纹
    ctx.save(); ctx.strokeStyle = rgba("#FFFFFF", 0.4); ctx.lineWidth = 2;
    for (let i = 0; i < 26; i++) {
      const y = horizon + 20 + hash(i) * (H - horizon - 30), x = ((hash(i + 3) * W + t * 40 * (0.5 + hash(i))) % (W + 200)) - 100, w = 40 + hash(i + 5) * 120;
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + w, y); ctx.stroke();
    }
    ctx.restore();
    D.figure(ctx, shx, bank, 260, P.ink, { kind: "she", face: -1, accent: P.accent, blush });
    if (fallP < 1) D.figure(ctx, hx, heY, 270, P.ink, { kind: "he", face: 1, rot: heRot, accent: P.wood });
    else { // 落水后：半身在水里
      ctx.save(); ctx.beginPath(); ctx.rect(0, 0, W, bank + 110); ctx.clip();
      D.figure(ctx, hx, bank + 170 + Math.sin(t * 2) * 5, 270, P.ink, { kind: "he", face: 1, accent: P.wood, blush }); ctx.restore();
    }
    // 涟漪（隐约的辐条 = 木轮）
    D.ripples(ctx, hx, bank + 110, t - tFall - 0.3, "#FFFFFF", { spokes: true, maxR: 560, rings: 5 });
    D.ripples(ctx, hx, bank + 110, t - tFall - 1.6, "#FFFFFF", { maxR: 420, rings: 3 });
    // 水花
    const sp = t - tFall - 0.3;
    if (sp > 0 && sp < 1.4) for (let j = 0; j < 26; j++) {
      const a = -Math.PI * (0.15 + hash(j) * 0.7), v = 380 + hash(j + 3) * 420;
      const x = hx + Math.cos(a) * v * sp, y = bank + 110 + Math.sin(a) * v * sp + 700 * sp * sp;
      ctx.fillStyle = rgba("#FFFFFF", 1 - sp / 1.4); ctx.beginPath(); ctx.arc(x, y, 5 + hash(j) * 6, 0, TAU); ctx.fill();
    }
    // 相视一笑：暖光
    if (blush > 0) {
      ctx.save(); ctx.globalCompositeOperation = "lighter"; D.glow(ctx, W / 2, bank - 60, 700 * blush, P.glow, 0.35 * blush); ctx.restore();
      D.petalField(ctx, s, P, { n: Math.floor(24 * blush), seed: 4, alpha: 0.7 * blush });
    }
  };

  // ============ 副歌：春风 ============
  S.spring = (ctx, s) => {
    const { W, H, t, lt, P, env, params } = s;
    const I = params.intensity ?? 1;
    D.background(ctx, s, P, { vignette: 0.12 });
    const cx = W / 2, cy = H * 0.5;
    if (params.sun) { const rise = ease.out(prog(lt, 0, 6)); D.glow(ctx, cx, lerp(H * 0.9, cy, rise), 760, P.glow, 0.9); D.glow(ctx, cx, lerp(H * 0.9, cy, rise), 300, "#FFFFFF", 0.5); }
    const tWind = s.cue("春风"), tBreath = s.cue("呼吸"), tLove = s.cue("喜欢你");
    const breathe = isFinite(tBreath) && t > tBreath - 0.3 ? Math.sin((t - tBreath) * 2.2) * 0.025 * clamp((t - tBreath + 0.3) * 2) : 0;
    ctx.save(); ctx.translate(cx, cy); ctx.scale(1 + breathe, 1 + breathe); ctx.translate(-cx, -cy);
    // 光芒
    ctx.save(); ctx.translate(cx, cy); ctx.rotate(t * 0.05);
    for (let i = 0; i < 16; i++) { ctx.rotate(TAU / 16); ctx.fillStyle = rgba(P.glow, 0.13 + env.beatPulse * 0.06 * I); ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(1400, -60); ctx.lineTo(1400, 60); ctx.closePath(); ctx.fill(); }
    ctx.restore();
    D.hills(ctx, s, H * 0.92, 120, rgba(P.wood, 0.25), 11, t * 8);
    D.hills(ctx, s, H * 1.0, 110, rgba(P.woodDark, 0.25), 13, t * 16);
    // 中心大木轮：鼓点发光
    const rot = env.beatPos * (TAU / 24);
    D.wheel(ctx, cx, cy, 300 * (1 + env.beatPulse * 0.025 * I), rot, P, { alpha: 0.32, pulse: env.strongPulse * I, glow: 0.4 });
    // 两个小人在山坡上，中间开出一朵花
    D.figure(ctx, W * 0.36, H * 0.93, 170, P.ink, { kind: "he", face: 1, accent: P.wood, blush: 0.6, alpha: 0.85 });
    D.figure(ctx, W * 0.64, H * 0.93, 162, P.ink, { kind: "she", face: -1, accent: P.accent, blush: 0.6, alpha: 0.85 });
    const bloom = ease.back(clamp(prog(t, tLove, 1.2)));
    if (bloom > 0) for (let k = 0; k < 6; k++) D.petal(ctx, W / 2 + Math.cos(k * TAU / 6 + t * 0.3) * 26 * bloom, H * 0.88 + Math.sin(k * TAU / 6 + t * 0.3) * 26 * bloom, 26 * bloom, k * TAU / 6 + Math.PI / 2 + t * 0.3, P.accent, 0.9);
    ctx.restore();
    // 花瓣：常驻 + 强拍迸发 + 春风一阵
    const gust = isFinite(tWind) ? ease.inOut(prog(t, tWind - 0.2, 2.6)) : 0;
    D.petalField(ctx, s, P, { n: Math.round(45 * I), seed: 2, wind: 40 + gust * 120, gust, fall: 70 });
    D.burst(ctx, s, P, env.recentStrong.filter((x) => x >= s.shot.start), { x: cx, y: cy, n: Math.round(14 * I), speed: 620 * I, life: 2 });
    if (params.echo) { // 余韵：回声字
      ctx.save(); ctx.font = `140px "${s.E.P.theme.fonts.lyric}", serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      const a = Math.sin(clamp(lt / (s.dur - 1)) * Math.PI) * 0.3;
      for (let k = 0; k < 3; k++) { ctx.globalAlpha = a * (1 - k * 0.3); ctx.fillStyle = P.accent; ctx.fillText(params.echo, cx, cy + k * 18 - lt * 4); }
      ctx.restore();
    }
  };

  // ============ 间奏：削木成轮 ============
  S.workshop = (ctx, s) => {
    const { W, H, t, lt, dur, P, env } = s;
    D.background(ctx, s, P);
    // 工作台
    ctx.fillStyle = rgba(P.woodDark, 0.85); ctx.fillRect(0, H * 0.8, W, H * 0.2);
    D.woodGrain(ctx, 0, H * 0.8, W, H * 0.2, P.wood, 0, 1, 6, 0.5);
    // 每一拍装一根辐条
    const b0 = s.E.env(s.shot.start).beatIndex, built = clamp((env.beatIndex - b0) / 22 + clamp(env.beatPhase / 0.44) / 22);
    const cx = W / 2, cy = H * 0.45, r = 260;
    const rollOut = ease.in(prog(lt, dur - 1.6, 1.6));
    D.glow(ctx, cx, cy, 520, P.glow, 0.35 + built * 0.3);
    D.wheel(ctx, cx + rollOut * 300, cy, r, rollOut * 2.5 + Math.sin(t * 0.5) * 0.05, P, { rim: ease.out(prog(lt, 0.3, 2.5)), build: built, pulse: env.beatPulse * 0.8 });
    // 刻刀
    const ang = env.beatIndex * 0.52;
    const kx = cx + Math.cos(ang) * (r + 40), ky = cy + Math.sin(ang) * (r + 40);
    ctx.save(); ctx.globalAlpha = 1 - rollOut; ctx.translate(kx, ky); ctx.rotate(ang + Math.PI / 2 + Math.sin(env.beatPhase * 14) * 0.2);
    ctx.fillStyle = P.woodDark; ctx.fillRect(-10, -120, 20, 90); ctx.fillStyle = "#CFC6B8"; ctx.beginPath(); ctx.moveTo(-8, -30); ctx.lineTo(8, -30); ctx.lineTo(0, 10); ctx.closePath(); ctx.fill();
    ctx.restore();
    // 刨花：随鼓点卷起
    ctx.save(); ctx.strokeStyle = P.wood; ctx.lineCap = "round";
    s.E.A.beats.forEach((bt, bi) => {
      const age = t - bt; if (age < 0 || age > 2.4 || bt < s.shot.start) return;
      for (let j = 0; j < 3; j++) {
        const a = hash(bi * 3 + j) * TAU, v = 160 + hash(bi + j * 9) * 220;
        const x = kx + Math.cos(a) * v * age, y = ky + Math.sin(a) * v * age + 260 * age * age;
        ctx.globalAlpha = (1 - age / 2.4) * 0.9; ctx.lineWidth = 4;
        ctx.beginPath(); for (let u = 0; u < 1; u += 0.05) { const rr = 14 * (1 - u * 0.6), aa = u * TAU * 1.6 + age * 6; ctx.lineTo(x + Math.cos(aa) * rr, y + Math.sin(aa) * rr); } ctx.stroke();
      }
    });
    ctx.restore();
  };

  // ============ 木轮单车 ============
  S.bicycle = (ctx, s) => {
    const { W, H, t, lt, P, env } = s;
    D.background(ctx, s, P);
    const tBuild = s.cue("拙手削木"), tGo = s.cue("木轮悠悠"), tHome = s.cue("朴素人家"), tHand = s.cue("握住双手"), tHer = s.cue("你不嫌弃");
    const go = ease.inOut(prog(t, tGo - 0.4, 1.4)), travel = Math.max(0, t - tGo) * 220 * go;
    // 远景
    D.hills(ctx, s, H * 0.62, 160, rgba(P.water, 0.25), 21, travel * 0.15);
    D.hills(ctx, s, H * 0.72, 110, rgba(P.wood, 0.3), 23, travel * 0.4);
    // 朴素人家：小屋 + 炊烟
    const hp = ease.out(prog(t, tHome - 0.6, 1.2));
    if (hp > 0) {
      const x = W * 0.8 - Math.max(0, t - tHome) * 40, y = H * 0.7;
      ctx.save(); ctx.globalAlpha = hp; ctx.fillStyle = P.woodDark;
      ctx.fillRect(x - 80, y - 90, 160, 90); ctx.beginPath(); ctx.moveTo(x - 110, y - 90); ctx.lineTo(x, y - 160); ctx.lineTo(x + 110, y - 90); ctx.closePath(); ctx.fill();
      ctx.fillStyle = P.glow; ctx.fillRect(x - 30, y - 60, 30, 30);
      ctx.strokeStyle = rgba(P.ink, 0.3); ctx.lineWidth = 6; ctx.lineCap = "round"; ctx.beginPath();
      for (let k = 0; k < 30; k++) { const u = k / 29; ctx.lineTo(x + 50 + Math.sin(u * 8 + t * 2) * 14 * u, y - 140 - u * 180 * hp); } ctx.stroke(); ctx.restore();
    }
    // 地面
    ctx.fillStyle = rgba(P.woodDark, 0.2); ctx.fillRect(0, H * 0.8, W, H * 0.2);
    ctx.strokeStyle = rgba(P.woodDark, 0.4); ctx.lineWidth = 3;
    for (let i = 0; i < 14; i++) { const x = ((i * 160 - travel) % (W + 160) + W + 160) % (W + 160) - 80; ctx.beginPath(); ctx.moveTo(x, H * 0.84); ctx.lineTo(x + 50, H * 0.84); ctx.stroke(); }
    // 单车逐笔画出
    const bp = ease.inOut(prog(t, Math.min(tBuild, s.shot.start + 1.5) - 1.2, 3.4));
    const bx = W * 0.45, by = H * 0.86, sc = 380;
    const rot = travel / (sc * 0.42);
    const pts = D.bicycle(ctx, bx, by, sc, rot, P, { progress: bp, pulse: env.beatPulse * 0.6 });
    // 骑车人
    const he = ease.out(prog(t, tGo - 1, 1));
    const she = ease.out(prog(t, tHer - 0.3, 1));
    D.riders(ctx, pts, sc, P, { he, she, blush: ease.out(prog(t, tHand, 1)) });
    // 握住双手：两人之间亮起一点暖光
    const hand = ease.out(prog(t, tHand - 0.2, 0.8));
    if (hand > 0) { D.glow(ctx, pts.seat.x - 40, pts.seat.y - 60, 140 + env.beatPulse * 40, P.glow, hand); D.petalField(ctx, s, P, { n: Math.floor(18 * hand), seed: 9, alpha: 0.6 * hand, wind: -60 }); }
  };

  // ============ 后副歌：后座笑靥 ============
  S.ride = (ctx, s) => {
    const { W, H, t, lt, P, env } = s;
    D.background(ctx, s, P, { vignette: 0.12 });
    const tYear = s.cue("年华"), tSmile = s.cue("笑靥如花");
    const travel = lt * 300 + 4000;
    D.glow(ctx, W * 0.75, H * 0.3, 420, P.glow, 0.6);
    D.hills(ctx, s, H * 0.65, 170, rgba(P.water, 0.3), 31, travel * 0.12);
    D.hills(ctx, s, H * 0.76, 120, rgba(P.wood, 0.35), 33, travel * 0.35);
    ctx.fillStyle = rgba(P.woodDark, 0.25); ctx.fillRect(0, H * 0.82, W, H * 0.18);
    // 路边的花：经过时绽放
    for (let i = 0; i < 20; i++) {
      const x = ((i * 137 - travel * 0.9) % (W + 200) + W + 200) % (W + 200) - 100, y = H * 0.86 + hash(i) * 60;
      const open = clamp((W * 0.7 - x) / 300);
      for (let k = 0; k < 5; k++) D.petal(ctx, x + Math.cos(k * TAU / 5) * 12 * open, y + Math.sin(k * TAU / 5) * 12 * open, 12 * open + 2, k * TAU / 5 + Math.PI / 2, hash(i + 2) > 0.5 ? P.petal : P.accent, 0.9);
    }
    // 车轮转速锁定拍子：每拍 1/6 圈
    const rot = env.beatPos * (TAU / 6);
    const bx = W * 0.45, by = H * 0.9, bob = Math.abs(Math.sin(env.beatPos * Math.PI)) * 6;
    const pts = D.bicycle(ctx, bx, by - bob, 370, rot, P, { pulse: env.beatPulse });
    D.riders(ctx, pts, 370, P, { blush: 0.8 });
    // 年华：落叶 / 花瓣向后飘
    const yp = clamp(t - tYear + 0.3);
    if (yp > 0) D.petalField(ctx, s, P, { n: 30, seed: 5, wind: -420, fall: 30, color: P.wood, color2: P.petal, alpha: 0.8 * yp });
    // 笑靥如花：她身边绽放
    D.burst(ctx, s, P, isFinite(tSmile) ? [tSmile, tSmile + 0.45, tSmile + 0.9] : [], { x: pts.rear.x - 20, y: pts.rear.y - 200, n: 20, speed: 420, life: 2.2 });
    // Woo~ 音符
    ctx.save(); ctx.fillStyle = rgba(P.ink, 0.5); ctx.font = `48px "${s.E.P.theme.fonts.ui}", serif`;
    for (let i = 0; i < 6; i++) { const a = (lt * 0.5 + i / 6) % 1; ctx.globalAlpha = Math.sin(a * Math.PI) * 0.6; ctx.fillText(i % 2 ? "♪" : "♫", pts.seat.x - 100 + i * 50 + Math.sin(a * 6 + i) * 20, pts.seat.y - 260 - a * 220); }
    ctx.restore();
  };

  // ============ 桥段：星夜辙痕 ============
  S.night = (ctx, s) => {
    const { W, H, t, lt, dur, P, env } = s;
    const dawnP = ease.inOut(prog(lt, dur - 4.5, 4.5));
    const dawn = s.E.palette("dawn"), Q = MV.mixPalette(P, dawn, dawnP * 0.55);
    D.background(ctx, s, Q, { paper: true, paperAlpha: 0.25, vignette: 0.35, bottom: MV.mix(P.bg2, dawn.bg2, dawnP) });
    D.stars(ctx, s, P.ink, 160);
    const tRut = s.cue("辙痕"), tStar = s.cue("星子");
    // 明月里藏着木轮
    const mx = W * 0.74, my = H * 0.26;
    D.glow(ctx, mx, my, 300, Q.glow, 0.45);
    ctx.fillStyle = "#F8EFD6"; ctx.beginPath(); ctx.arc(mx, my, 110, 0, TAU); ctx.fill();
    D.wheel(ctx, mx, my, 100, t * 0.05, P, { simple: true, alpha: 0.18, wood: "#B9A47A", dark: "#B9A47A" });
    // 流星
    for (let k = 0; k < 6; k++) {
      const st = tStar - 0.2 + k * 0.55, a = prog(t, st, 1.1); if (a <= 0 || a >= 1) continue;
      const x0 = W * (0.15 + hash(k) * 0.5), y0 = H * (0.05 + hash(k + 4) * 0.2), x = x0 + a * 380, y = y0 + a * 220;
      const g = ctx.createLinearGradient(x - 160, y - 92, x, y); g.addColorStop(0, rgba(P.accent, 0)); g.addColorStop(1, rgba("#FFFFFF", 1 - a));
      ctx.strokeStyle = g; ctx.lineWidth = 3; ctx.beginPath(); ctx.moveTo(x - 160, y - 92); ctx.lineTo(x, y); ctx.stroke();
    }
    D.hills(ctx, s, H * 0.7, 150, rgba(Q.crowd, 0.9), 41, lt * 6);
    // 地面 + 车辙
    ctx.fillStyle = MV.mix("#0D1426", dawn.woodDark, dawnP * 0.5); ctx.fillRect(0, H * 0.74, W, H * 0.26);
    const rp = ease.inOut(prog(t, tRut - 0.6, 2.4));
    ctx.save(); ctx.strokeStyle = rgba(P.accent, 0.55); ctx.lineCap = "round";
    [0, 1].forEach((k) => {
      ctx.lineWidth = 5 + k * 3; ctx.beginPath();
      for (let x = 0; x <= W * rp; x += 12) { const y = H * (0.8 + k * 0.08) + Math.sin(x * 0.004 + k) * 26 * (1 + k); x === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y); }
      ctx.stroke();
    });
    ctx.restore();
    // 远处骑行剪影
    const bx = lerp(W * 0.1, W * 0.5, ease.inOut(prog(lt, 0, dur))), by = H * 0.74;
    const pts = D.bicycle(ctx, bx, by, 150, lt * 2.5, P, { frame: "#0A0F1E" });
    D.riders(ctx, pts, 150, P, { color: "#0A0F1E", plain: true });
    // 萤火虫
    for (let i = 0; i < 26; i++) {
      const x = hash(i) * W + noise(t * 0.3 + i) * 80, y = H * (0.55 + hash(i + 1) * 0.4) + noise(t * 0.4 + i * 3) * 50;
      D.glow(ctx, x, y, 14, P.accent, 0.5 + 0.5 * Math.sin(t * 3 + i));
    }
  };

  // ============ 尾声：一枚印 ============
  S.seal = (ctx, s) => {
    const { W, H, t, lt, P, env } = s;
    D.background(ctx, s, P);
    // 留白山水
    D.hills(ctx, s, H * 0.62, 260, rgba(P.ink, 0.1), 51, lt * 4);
    D.hills(ctx, s, H * 0.74, 180, rgba(P.ink, 0.16), 53, lt * 8);
    D.hills(ctx, s, H * 0.86, 120, rgba(P.ink, 0.24), 55, lt * 12);
    D.glow(ctx, W * 0.3, H * 0.3, 260, P.glow, 0.5);
    // 两人一车远行
    const bx = lerp(W * 0.12, W * 0.42, ease.inOut(clamp(lt / s.dur))), by = H * 0.86;
    const pts = D.bicycle(ctx, bx, by, 110, lt * 2, P, { frame: rgba(P.ink, 0.6) });
    D.riders(ctx, pts, 110, P, { color: rgba(P.ink, 0.7), plain: true });
    D.petalField(ctx, s, P, { n: 14, seed: 7, alpha: 0.5, size: 0.8 });
    // 朱红印章：木轮 + 木·柔
    const ts = s.cue("木生遇淑柔");
    const sp = prog(t, ts + 0.9, 0.5), press = ease.out(sp);
    if (sp > 0) {
      const x = W * 0.62, y = H * 0.36, sc = lerp(1.5, 1, press), a = press;
      ctx.save(); ctx.translate(x, y); ctx.scale(sc, sc); ctx.globalAlpha = a * 0.92;
      ctx.fillStyle = P.accent; ctx.beginPath(); ctx.arc(0, 0, 110, 0, TAU); ctx.fill();
      ctx.globalCompositeOperation = "destination-out"; ctx.strokeStyle = "#000"; ctx.fillStyle = "#000";
      ctx.lineWidth = 7; ctx.beginPath(); ctx.arc(0, 0, 94, 0, TAU); ctx.stroke();          // 木轮轮圈
      for (let k = 0; k < 12; k++) { const an = (k / 12) * TAU + 0.13; ctx.beginPath(); ctx.arc(Math.cos(an) * 94, Math.sin(an) * 94, 6, 0, TAU); ctx.fill(); } // 辐条头
      ctx.font = `78px "${s.E.P.theme.fonts.title}", serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle";
      ctx.fillText("木", 0, -34); ctx.fillText("柔", 0, 40);
      ctx.restore();
      if (sp < 1) D.glow(ctx, x, y, 220, P.accent, 0.25 * (1 - sp));
    }
  };

  // ============ 尾奏：远行 + 片名 ============
  S.ending = (ctx, s) => {
    const { W, H, t, lt, dur, P, env } = s;
    D.background(ctx, s, P);
    const sunY = lerp(H * 0.45, H * 0.62, ease.inOut(clamp(lt / dur)));
    D.glow(ctx, W / 2, sunY, 700, P.glow, 0.85); D.glow(ctx, W / 2, sunY, 170, "#FFF5DF", 0.95);
    D.hills(ctx, s, H * 0.7, 150, rgba(P.wood, 0.4), 61, lt * 10);
    ctx.fillStyle = rgba(P.woodDark, 0.5); ctx.fillRect(0, H * 0.78, W, H * 0.22);
    // 能量高处（2:54 左右的人声）车子骑得更快，花瓣更多
    const e = env.energy;
    const card = ease.inOut(prog(lt, 15, 3)); // 片名卡出现
    const ride = 1 - card;
    const bx = lerp(W * 0.2, W * 0.62, ease.inOut(clamp(lt / 16))), by = H * 0.8;
    ctx.save(); ctx.globalAlpha = ride;
    const pts = D.bicycle(ctx, bx, by, 260, env.beatPos * (TAU / 6), P, { frame: P.ink, pulse: env.beatPulse * e });
    D.riders(ctx, pts, 260, P, {});
    ctx.restore();
    D.petalField(ctx, s, P, { n: Math.round(10 + 50 * e), seed: 8, wind: -140, alpha: 0.8 });
    D.burst(ctx, s, P, env.recentStrong.filter((x) => e > 0.4 && x > s.shot.start), { x: W / 2, y: sunY, n: 10, speed: 520, life: 1.8 });
    // 片名卡：木轮停下，片名浮现
    if (card > 0) {
      ctx.save(); ctx.globalAlpha = card;
      ctx.fillStyle = rgba(P.bg1, 0.55 * card); ctx.fillRect(0, 0, W, H);
      const stop = 1 - ease.out(prog(lt, 15, 5));
      D.wheel(ctx, W / 2, H * 0.4, 150, lt * 2 * stop + 1, P, { glow: 0.4 });
      ctx.font = `120px "${s.E.P.theme.fonts.title}", serif`; ctx.textAlign = "center"; ctx.textBaseline = "middle"; ctx.fillStyle = P.ink;
      ctx.fillText(s.E.P.meta.title || "木生遇淑柔", W / 2, H * 0.68);
      ctx.font = `36px "${s.E.P.theme.fonts.ui}", serif`; ctx.fillStyle = rgba(P.ink, 0.7);
      ctx.fillText(s.E.P.meta.credits || "此生复何求", W / 2, H * 0.78);
      ctx.restore();
    }
    // 最后淡出到纸色
    const fin = prog(lt, dur - 2.5, 2.5);
    if (fin > 0) { ctx.fillStyle = rgba(P.bg1, fin); ctx.fillRect(0, 0, W, H); }
  };
})();
