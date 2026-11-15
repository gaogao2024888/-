# 木生遇淑柔 · 歌词动画 MV

一个可以运行、可以预览、能逐个修改镜头的歌词动画 MV 工程。纯浏览器 Canvas 实现，不需要构建。

- 方案、时间对齐结论、分镜表：[`docs/storyboard.md`](docs/storyboard.md)
- 成片：运行 `node tools/render.mjs` 生成 `out/mv.mp4`（1080p30，混入原曲）

## 打开预览

```bash
cd lyric-mv
node tools/serve.mjs          # 打开 http://localhost:8000
```

直接双击 `index.html` 也能看。用 `serve.mjs` 打开，音频能正常拖动进度，浏览器录制也能录进声音（`python -m http.server` 不支持 Range 请求，拖进度会跳回开头）。

### 快捷键

| 键 | 作用 |
|---|---|
| 空格 | 播放 / 暂停 |
| ← / → | 后退 / 前进 1 秒（按住 Shift 是 5 秒） |
| , / . | 上一帧 / 下一帧 |
| [ / ] | 上一个 / 下一个镜头 |
| L | 只循环当前镜头（逐个检查镜头时用） |
| T | 在「歌词对齐」页：把选中那行的时间设成当前播放时间，并跳到下一行 |

### 检查流程建议

1. **逐个镜头检查**：在「分镜」里点一个镜头，勾上「循环本镜头」，看完一个再看下一个
2. **音画同步**：看下方的「放大 ±4 秒」轨道：竖线是每个字的出现时间，红色长条是鼓点。字比唱得早或晚，就在「歌词对齐」里改时间，或用 T 键边听边打点。整首都偏的话调「全局偏移」
3. **文字**：打开「安全框」，确认字没有贴边
4. **改镜头**：每个镜头的场景模板、配色、转场、歌词版式、字号、参数都能改，改完立即生效；也可以在当前时间拆分镜头

修改会自动保存在浏览器里。在「导出 / 工程」里可以导出 `project.js`，替换 `data/project.js` 就写回工程；也可以导出或导入 `project.json`。

## 导出视频

| 方式 | 命令 / 操作 | 说明 |
|---|---|---|
| 逐帧 MP4（推荐） | `node tools/render.mjs` | 每帧都精确，H.264 + AAC。`--from 55 --to 72` 只导出一段，`--project my.json` 用导出的工程，`--height 720` 降低分辨率 |
| 浏览器录制 | 「导出 / 工程」→ 录制 WebM | 实时播放一遍并录下来，需用 `serve.mjs` 打开页面 |

依赖：Node 18+、ffmpeg、`npm i playwright && npx playwright install chromium`。

## 目录

```
lyric-mv/
├─ index.html            预览 + 编辑器
├─ css/app.css
├─ js/
│  ├─ util.js            缓动、确定性随机、颜色
│  ├─ draw.js            绘图基元：宣纸、木轮、人物剪影、单车、花瓣、涟漪、星空
│  ├─ engine.js          时间 → 画面：分镜、节拍包络、转场合成、逐字歌词排版
│  ├─ scenes.js          11 个场景模板（title/crowd/names/stream/spring/workshop/bicycle/ride/night/seal/ending）
│  └─ app.js             播放器、时间轴、分镜/歌词编辑、导出
├─ data/
│  ├─ project.js         ★ 工程文件：歌词时间、关键词、分镜、配色、字体
│  ├─ analysis.js        节拍 / 强拍 / 能量（由 analyze.py 生成）
│  └─ lyrics.lrc         原始 LRC
├─ assets/song.mp3, assets/fonts/*.woff2（子集字体）
├─ tools/
│  ├─ analyze.py         近似人声分离 + 节拍 + 鼓点 + 能量 → data/analysis.js
│  ├─ check_lrc.py       LRC 时间与人声起点的偏差报告
│  ├─ fetch_fonts.py     改了歌词、出现新字时重新下载子集字体
│  ├─ render.mjs         逐帧导出 MP4
│  └─ serve.mjs          本地预览服务器
└─ docs/storyboard.md    方案 + 分镜表
```

## 常见改法

- **某句歌词的时间**：「歌词对齐」页直接改，或用 T 键打点
- **画面跟某个词走**：场景代码里用 `s.cue("词")` 拿到这个词被唱到的时刻（见 `js/scenes.js`）
- **换配色**：改 `data/project.js` → `theme.palettes`，或给镜头换一个 palette
- **歌词里有新字**：运行 `python3 tools/fetch_fonts.py`，否则新字会回退到系统字体
- **换一首歌**：替换 `assets/song.mp3` 和 `data/lyrics.lrc` → `python3 tools/analyze.py assets/song.mp3 out/analysis` → 改 `project.js` 里的歌词和分镜
