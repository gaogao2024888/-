// 渲染引擎：时间 → 画面。负责分镜查找、节拍包络、转场合成、歌词排版
(function () {
  const MV = window.MV;
  const { clamp, lerp, ease, bump, rgba, lastIndex } = MV;
  MV.scenes = MV.scenes || {};

  const isCJK = (c) => /[㐀-鿿豈-﫿]/.test(c);

  // 把歌词变成“逐字”时间表
  function buildLines(project) {
    const off = project.meta.lyricOffset || 0;
    const L = [...project.lyrics].sort((a, b) => a.t - b.t);
    const secs = (project.sections || []).map((s) => s.t + off);
    return L.map((l, i) => {
      const start = l.t + off;
      const next = i + 1 < L.length ? L[i + 1].t + off : Infinity;
      const nextSec = secs.find((x) => x > start + 1.0) ?? Infinity;
      const glyphs = [...l.text];
      const nChars = glyphs.filter((c) => c.trim() && !/[，,。、~()（）]/.test(c)).length || 1;
      const end = Math.min(next - 0.12, nextSec, start + nChars * 0.6 + 2.2);
      const singDur = Math.min((end - start) * 0.8, nChars * 0.3);
      // 关键词位置
      const kwMask = new Array(glyphs.length).fill(-1);
      (l.kw || []).forEach((k, ki) => { const idx = l.text.indexOf(k); if (idx >= 0) for (let j = 0; j < [...k].length; j++) kwMask[idx + j] = ki; });
      let ci = 0;
      const chars = glyphs.map((c, gi) => {
        const counts = c.trim() && !/[，,。、~()（）]/.test(c);
        const ct = start + (ci / nChars) * singDur;
        if (counts) ci++;
        return { c, t: ct, kw: kwMask[gi] };
      });
      const kwTimes = {};
      (l.kw || []).forEach((k) => { const idx = l.text.indexOf(k); if (idx >= 0) kwTimes[k] = chars[idx].t; });
      return { i, start, end, text: l.text, chars, kwTimes, style: l.style || "", src: l };
    });
  }

  class Engine {
    constructor(project, analysis) {
      this.A = analysis;
      this.W = project.meta.width || 1920; this.H = project.meta.height || 1080;
      this.off = [document.createElement("canvas"), document.createElement("canvas")];
      this.setProject(project);
    }
    setProject(p) {
      this.P = p;
      this.lines = buildLines(p);
      const shots = [...p.shots].sort((a, b) => a.start - b.start);
      const dur = this.A.duration;
      shots.forEach((s, i) => (s._end = i + 1 < shots.length ? shots[i + 1].start : dur));
      this.shots = shots;
    }
    shotIndexAt(t) { return Math.max(0, lastIndex(this.shots, t, "start")); }
    palette(name) { return this.P.theme.palettes[name] || this.P.theme.palettes.paper; }

    // 关键词出现的时刻（某句里第一次出现该词的第一个字）
    cue(text, from = -Infinity, to = Infinity) {
      for (const l of this.lines) {
        if (l.start < from - 1 || l.start > to) continue;
        const idx = l.text.indexOf(text);
        if (idx >= 0) return l.chars[idx].t;
      }
      return Infinity;
    }

    env(t) {
      const A = this.A;
      const bi = lastIndex(A.beats, t), si = lastIndex(A.strong, t);
      const lastB = bi >= 0 ? A.beats[bi] : -9, lastS = si >= 0 ? A.strong[si] : -9;
      const eh = A.energy.hop, ev = A.energy.v, ei = t / eh, e0 = ev[Math.floor(ei)] ?? 0, e1 = ev[Math.ceil(ei)] ?? e0;
      const energy = lerp(e0, e1, ei - Math.floor(ei));
      const recentStrong = [];
      for (let k = si; k >= 0 && t - A.strong[k] < 2.2; k--) recentStrong.push(A.strong[k]);
      return {
        beatIndex: bi, beatPhase: t - lastB, beatPulse: Math.exp(-(t - lastB) * 7),
        strongPulse: Math.exp(-(t - lastS) * 5), recentStrong, energy,
        // 连续“节拍位置”（带小数），用于把转速锁到拍子上
        beatPos: bi < 0 ? t / 0.441 : bi + clamp((t - lastB) / ((A.beats[bi + 1] ?? lastB + 0.441) - lastB))
      };
    }

    sceneState(shot, t) {
      const E = this;
      return {
        t, lt: t - shot.start, dur: shot._end - shot.start, p: clamp((t - shot.start) / (shot._end - shot.start)),
        W: this.W, H: this.H, shot, params: shot.params || {}, P: this.palette(shot.palette), env: this.env(t),
        cue: (text) => E.cue(text, shot.start, shot._end), E
      };
    }

    drawScene(ctx, shot, t) {
      const fn = MV.scenes[shot.scene] || MV.scenes.title;
      ctx.save();
      try { fn(ctx, this.sceneState(shot, t)); } catch (e) { console.error("scene error", shot.scene, e); }
      ctx.restore();
    }

    ensureOff(w, h) { for (const c of this.off) if (c.width !== w || c.height !== h) { c.width = w; c.height = h; } }

    render(ctx, t, opts = {}) {
      const W = this.W, H = this.H, cw = ctx.canvas.width, ch = ctx.canvas.height, k = cw / W;
      const i = this.shotIndexAt(t), shot = this.shots[i], prev = this.shots[i - 1];
      const tr = shot.transition || { type: "cut", dur: 0 };
      const lt = t - shot.start;
      ctx.setTransform(k, 0, 0, k, 0, 0);
      if (prev && tr.type !== "cut" && lt < tr.dur) {
        this.ensureOff(cw, ch);
        const [A, B] = this.off.map((c) => c.getContext("2d"));
        A.setTransform(k, 0, 0, k, 0, 0); B.setTransform(k, 0, 0, k, 0, 0);
        this.drawScene(A, prev, t); this.drawScene(B, shot, t);
        try { this.composite(ctx, tr, clamp(lt / tr.dur), shot, t); } catch (err) { console.error("transition error", err); ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.drawImage(this.off[1], 0, 0); }
      } else {
        this.drawScene(ctx, shot, t);
      }
      ctx.setTransform(k, 0, 0, k, 0, 0);
      if (opts.lyrics !== false) this.drawLyrics(ctx, t, shot);
      if (opts.safeArea) this.drawSafe(ctx);
    }

    composite(ctx, tr, p, shot, t) {
      const W = this.W, H = this.H, [A, B] = this.off, P = this.palette(shot.palette);
      const full = (c) => { ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.drawImage(c, 0, 0); ctx.restore(); };
      full(A);
      if (tr.type === "wheel" || tr.type === "ripple") {
        const e = ease.inOut(p), diag = Math.hypot(W, H) * 0.6;
        const cx = tr.x != null ? tr.x * W : W / 2, cy = tr.y != null ? tr.y * H : tr.type === "ripple" ? H * 0.72 : H / 2;
        const R = e * diag * (tr.type === "ripple" ? 1.25 : 1.05);
        ctx.save(); ctx.beginPath();
        if (tr.type === "ripple") ctx.ellipse(cx, cy, R, R * 0.75, 0, 0, Math.PI * 2); else ctx.arc(cx, cy, R, 0, Math.PI * 2);
        ctx.clip(); full(B); ctx.restore();
        // 擦除边缘画一圈木轮 / 水纹 —— 贯穿元素
        if (p > 0 && p < 1) {
          ctx.save(); ctx.globalAlpha = Math.sin(Math.PI * p);
          if (tr.type === "wheel") { // 只画轮圈 + 辐条头，像一只木轮从画面中心滚开
            ctx.strokeStyle = P.wood; ctx.lineWidth = 16; ctx.shadowColor = P.glow; ctx.shadowBlur = 24;
            ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.stroke(); ctx.shadowBlur = 0;
            ctx.strokeStyle = P.woodDark; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(cx, cy, R + 8, 0, Math.PI * 2); ctx.stroke(); ctx.beginPath(); ctx.arc(cx, cy, Math.max(0, R - 8), 0, Math.PI * 2); ctx.stroke();
            ctx.strokeStyle = P.wood; ctx.lineWidth = 6;
            for (let k = 0; k < 16; k++) { const an = t * 1.5 + (k / 16) * Math.PI * 2; ctx.beginPath(); ctx.moveTo(cx + Math.cos(an) * Math.max(0, R - 8), cy + Math.sin(an) * Math.max(0, R - 8)); ctx.lineTo(cx + Math.cos(an) * Math.max(0, R - 60), cy + Math.sin(an) * Math.max(0, R - 60)); ctx.stroke(); }
          }
          else for (let r = 0; r < 3; r++) { ctx.strokeStyle = rgba(P.water, 0.6 - r * 0.15); ctx.lineWidth = 4 - r; ctx.beginPath(); ctx.ellipse(cx, cy, R + r * 26, (R + r * 26) * 0.75, 0, 0, Math.PI * 2); ctx.stroke(); }
          ctx.restore();
        }
      } else { // ink：交叉淡化 + 轻推
        const e = ease.sine(p), sc = lerp(1.05, 1, ease.out(p));
        ctx.save(); ctx.globalAlpha = e; ctx.setTransform(sc, 0, 0, sc, (ctx.canvas.width * (1 - sc)) / 2, (ctx.canvas.height * (1 - sc)) / 2);
        ctx.drawImage(B, 0, 0); ctx.restore();
      }
    }

    // ---------------- 歌词 ----------------
    drawLyrics(ctx, t, shot) {
      const ly = shot.lyric || { mode: "lower", size: 72 };
      if (ly.mode === "none") return;
      const P = this.palette(shot.palette), theme = this.P.theme;
      if (ly.mode === "vertical") {
        // 竖排：本镜头内的句子留在画面上成诗，镜头结束前统一淡出
        const inShot = this.lines.filter((l) => l.start >= shot.start - 0.3 && l.start < shot._end && t >= l.start - 0.05);
        const fade = 1 - clamp((t - (shot._end - 1.4)) / 1.2);
        inShot.forEach((l, k) => this.drawVertical(ctx, l, t, ly, P, theme, inShot.length - 1 - k, fade));
        return;
      }
      const vis = this.lines.filter((l) => t >= l.start - 0.05 && t <= l.end + 0.7);
      for (const l of vis) {
        // 上一镜头里开始的句子，沿用它自己镜头的版式淡出，避免换镜头时歌词跳位
        const own = this.shots[this.shotIndexAt(l.start)];
        const oly = own && own !== shot && own.lyric && own.lyric.mode !== "vertical" && own.lyric.mode !== "none" ? own.lyric : ly;
        this.drawHorizontal(ctx, l, t, oly, P, theme);
      }
    }

    charStyle(ctx, ch, t, size, P, l) {
      const age = t - ch.t, a = ease.out(clamp(age / 0.42));
      let sc = 0.82 + 0.18 * ease.back(clamp(age / 0.42)), color = P.text;
      if (ch.kw >= 0) { sc *= 1 + 0.3 * bump(t, ch.t, 0.5); color = P.accent; }
      return { a, sc, color, dy: (1 - a) * size * 0.4 };
    }

    drawHorizontal(ctx, l, t, ly, P, theme) {
      const W = this.W, H = this.H;
      const adlib = l.style === "adlib";
      const size = (ly.size || 72) * (adlib ? 0.62 : 1);
      const font = adlib ? theme.fonts.ui : theme.fonts.lyric;
      ctx.save();
      ctx.font = `${adlib ? "italic 500 " : ""}${size}px "${font}", "Noto Serif SC", "WenQuanYi Zen Hei", serif`;
      ctx.textBaseline = "middle"; ctx.textAlign = "left";
      const gap = size * 0.06;
      const widths = l.chars.map((c) => (c.c === " " ? size * 0.5 : ctx.measureText(c.c).width + gap));
      const total = widths.reduce((a, b) => a + b, 0);
      const baseY = { center: H * 0.5, lower: H * 0.82, upper: H * 0.17 }[ly.mode] ?? H * 0.82;
      const yy = (ly.y != null ? ly.y * H : baseY) + (adlib ? size * 1.2 : 0);
      // 离场：向上推并淡出
      const out = clamp((t - l.end) / 0.6), outE = ease.inOut(out);
      const lineA = 1 - outE, lineDy = -outE * size * 1.15;
      let x = (ly.x != null ? ly.x * W : W / 2) - total / 2;
      // 字后的柔光底，保证可读
      if (lineA > 0) {
        const g = ctx.createRadialGradient(W / 2, yy + lineDy, 0, W / 2, yy + lineDy, total * 0.65 + size);
        g.addColorStop(0, rgba(P.bg1, 0.55 * lineA * clamp((t - l.start) / 0.4))); g.addColorStop(1, rgba(P.bg1, 0));
        ctx.save(); ctx.translate(W / 2, yy + lineDy); ctx.scale(1, 0.32); ctx.translate(-W / 2, -(yy + lineDy));
        ctx.fillStyle = g; ctx.fillRect(0, yy + lineDy - total, W, total * 2); ctx.restore();
      }
      l.chars.forEach((ch, i) => {
        const w = widths[i];
        if (ch.c !== " " && t >= ch.t - 0.02) {
          const st = this.charStyle(ctx, ch, t, size, P, l);
          ctx.save();
          ctx.globalAlpha = st.a * lineA * (adlib ? 0.8 : 1);
          ctx.translate(x + w / 2, yy + st.dy + lineDy); ctx.scale(st.sc, st.sc);
          ctx.shadowColor = rgba(P.bg1, 0.9); ctx.shadowBlur = size * 0.18;
          ctx.fillStyle = st.color; ctx.textAlign = "center";
          ctx.fillText(ch.c, 0, 0);
          ctx.restore();
        }
        x += w;
      });
      // 关键词下的毛笔横线
      ctx.strokeStyle = P.accent; ctx.lineCap = "round";
      let cx = (ly.x != null ? ly.x * W : W / 2) - total / 2;
      const kwSpans = {};
      l.chars.forEach((ch, i) => { if (ch.kw >= 0) { const sp = (kwSpans[ch.kw] ||= { x0: cx, x1: cx, t: ch.t }); sp.x1 = cx + widths[i]; } cx += widths[i]; });
      Object.values(kwSpans).forEach((sp) => {
        const q = ease.out(clamp((t - sp.t - 0.2) / 0.5));
        if (q <= 0) return;
        ctx.globalAlpha = 0.45 * lineA; ctx.lineWidth = size * 0.07;
        const y = yy + size * 0.58 + lineDy;
        ctx.beginPath(); ctx.moveTo(sp.x0 + 4, y); ctx.quadraticCurveTo((sp.x0 + sp.x1) / 2, y + size * 0.06, lerp(sp.x0 + 4, sp.x1 - 4, q), y - size * 0.03); ctx.stroke();
      });
      ctx.restore();
    }

    drawVertical(ctx, l, t, ly, P, theme, later, fade) {
      const W = this.W, H = this.H, size = ly.size || 90;
      const colX = (ly.x != null ? ly.x * W : W * 0.82) - later * size * 1.55;
      const fadeOut = later > 3 ? 0 : fade;
      const seqOut = 0;
      ctx.save();
      ctx.font = `${size}px "${theme.fonts.lyric}", "Noto Serif SC", serif`;
      ctx.textAlign = "center"; ctx.textBaseline = "middle";
      let y = H * 0.2;
      l.chars.forEach((ch) => {
        if (ch.c === " " || /[，,]/.test(ch.c)) { y += size * 0.55; return; }
        if (t >= ch.t - 0.02) {
          const st = this.charStyle(ctx, ch, t, size, P, l);
          ctx.save(); ctx.globalAlpha = st.a * fadeOut * (1 - seqOut);
          ctx.translate(colX, y + st.dy * 0.5); ctx.scale(st.sc, st.sc);
          ctx.shadowColor = rgba(P.bg1, 0.9); ctx.shadowBlur = size * 0.15;
          ctx.fillStyle = st.color; ctx.fillText(ch.c, 0, 0); ctx.restore();
        }
        y += size * 1.08;
      });
      ctx.restore();
    }

    drawSafe(ctx) {
      const W = this.W, H = this.H;
      ctx.save(); ctx.strokeStyle = "rgba(255,0,120,.5)"; ctx.setLineDash([10, 8]); ctx.lineWidth = 2;
      ctx.strokeRect(W * 0.05, H * 0.05, W * 0.9, H * 0.9); ctx.strokeRect(W * 0.1, H * 0.1, W * 0.8, H * 0.8); ctx.restore();
    }
  }
  MV.Engine = Engine;
  MV.buildLines = buildLines;
})();
