// 通用工具：缓动、确定性随机、颜色。所有动画都是时间 t 的纯函数 → 可任意拖动、逐帧导出
(function () {
  const MV = (window.MV = window.MV || {});

  MV.clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
  MV.lerp = (a, b, t) => a + (b - a) * t;
  MV.prog = (t, start, dur) => MV.clamp((t - start) / dur); // 0→1 进度
  MV.ease = {
    lin: (t) => t,
    out: (t) => 1 - Math.pow(1 - t, 3),
    in: (t) => t * t * t,
    inOut: (t) => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
    sine: (t) => -(Math.cos(Math.PI * t) - 1) / 2,
    back: (t) => { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); },
    elastic: (t) => (t === 0 || t === 1 ? t : Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * ((2 * Math.PI) / 3)) + 1)
  };
  // 一个短促的“弹一下”：0→1→0
  MV.bump = (t, start, dur = 0.5) => { const p = (t - start) / dur; return p < 0 || p > 1 ? 0 : Math.sin(Math.PI * p); };

  MV.hash = (n) => { const x = Math.sin(n * 127.1 + 311.7) * 43758.5453123; return x - Math.floor(x); };
  MV.h2 = (a, b) => MV.hash(a * 57.13 + b * 13.71 + 0.5);
  MV.noise = (x) => { const i = Math.floor(x), f = x - i, u = f * f * (3 - 2 * f); return MV.lerp(MV.hash(i), MV.hash(i + 1), u) * 2 - 1; };

  // 二分：最后一个 <= t 的下标
  MV.lastIndex = (arr, t, key) => {
    let lo = 0, hi = arr.length - 1, ans = -1;
    while (lo <= hi) { const m = (lo + hi) >> 1; const v = key ? arr[m][key] : arr[m]; if (v <= t) { ans = m; lo = m + 1; } else hi = m - 1; }
    return ans;
  };

  const hexToRgb = (h) => { h = h.replace("#", ""); if (h.length === 3) h = h.split("").map((c) => c + c).join(""); const n = parseInt(h, 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
  MV.rgba = (hex, a = 1) => { const [r, g, b] = hexToRgb(hex); return `rgba(${r},${g},${b},${a})`; };
  MV.mix = (h1, h2, t) => { const a = hexToRgb(h1), b = hexToRgb(h2); const c = a.map((v, i) => Math.round(MV.lerp(v, b[i], t))); return "#" + c.map((v) => v.toString(16).padStart(2, "0")).join(""); };
  MV.mixPalette = (p1, p2, t) => { const o = {}; for (const k in p1) o[k] = p2[k] ? MV.mix(p1[k], p2[k], t) : p1[k]; return o; };

  MV.fmtTime = (t) => { t = Math.max(0, t); const m = Math.floor(t / 60), s = t - m * 60; return `${String(m).padStart(2, "0")}:${s.toFixed(2).padStart(5, "0")}`; };
})();
