---
name: melody-to-lyrics
description: 按旋律填词和打磨歌词的完整流程：读音频标签（Suno 的歌名、风格提示词）→ 从音频或 MIDI 扒主旋律 → 分出人声段和器乐段 → 按拍子算出每句几个字 → 按作词方法写词（结构、意象库、视角、钩子金句、不直白不说教）→ 校验字数、倒字、押韵（十三辙、宽韵、双押三押）→ 交付可直接贴进 Suno 的歌词。Use this skill whenever the user gives a song, audio file, Suno track or MIDI and wants lyrics written, rewritten, continued, checked or polished to fit the tune — 填词、改词、按曲写词、给伴奏写歌词、写第二段/副歌/主歌、双押三押宽韵、倒字、歌词字数对不上 — and whenever they ask how to make Chinese lyrics better, even if they don't mention the melody.
---

# 按旋律填词

从一段音频到能唱、好听的歌词，分 7 步。脚本是本技能目录下的 `scripts/melody_lyrics.py`，下文的 `ml` 就是 `uv run <本技能目录>/scripts/melody_lyrics.py`。

- 用 `uv run` 运行会自动装好依赖。
- 没有 uv 就先 `pip install librosa soundfile mido pypinyin`。带伴奏的整首歌还要 essentia，它只能装在 Linux 或 macOS 上。
- 环境装不了依赖时，第 2–4 步跑不了。这时请用户给每句字数，第 5 步照常写，第 6 步按 `references/rhyme-and-tone.md` 手工核对。

要接着写《是你还是你》，先读 `references/case-shi-ni-hai-shi-ni.md`。旋律已经存在 `references/case-shi-ni-hai-shi-ni.spec.txt`，不需要原音频。

## 1. 读文件信息

```bash
ml info 歌.mp3
```

得到歌名、作者、Suno 的风格提示词和歌词标签（标签可能被截断）。据此定基调：情绪、速度、男声还是女声，以及编曲里的特色乐器（比如二胡，可以写进歌词）。

## 2. 扒旋律

```bash
ml extract 歌.mp3 -o 歌.melody.json
```

- 会自动判断有没有伴奏：清唱和人声分轨用 pYIN，带伴奏的整首歌用 Melodia。判断错了用 `--mode vocal` 或 `--mode mix` 指定。
- MIDI 最准（`--track N` 选轨）。用户能导出人声分轨（比如 Suno 的 stems），就用分轨。
- 整首歌里 Melodia 跟的是最响的那条旋律：人声段是人声，前奏和间奏是乐器，偶尔会跳到贝斯上。

## 3. 分段

```bash
ml sections 歌.melody.json            # 女声加 --voice female；音区特殊用 --range A2:C5
```

按音区判断每句是人声还是乐器，合成一张段落时间表。核对一遍：前奏、间奏、尾奏里的乐器旋律不填词。乐器和人声在同一音区时，这一步只能粗分。

## 4. 定每句字数

```bash
ml lines 歌.mp3 歌.melody.json --section 主歌1=15.9 --section 副歌=45.7 -o 歌.spec.txt
ml template 歌.spec.txt -o 模板.json
```

- `--section` 填 sections 里每个人声段的起点（秒），会自动对齐到最近的拍子。默认两小节一句、每段 8 句，可以用 `--lines`、`--bars` 改。
- `lines` 输出每句的字数和简谱，同时存一份可编辑的简谱规格：`,` 表示低八度，`'` 表示高八度，`-` 表示长音。
- 对着结果人工核对：
  - 按换气重新断句；
  - 去掉被乐器盖住的音（输出里会提示）；
  - 同一段旋律出现两次都一样才算可靠；
  - 拿不准就请用户对着原曲哼一遍，或者给分轨。
- 改好规格文件后用 `template` 生成校验模板。
- 动笔前把三样东西告诉用户：段落时间表、每句字数、旋律上的坑（大跳在哪、最高的长音在哪）。

## 5. 写词

先读 `references/writing-method.md`，里面讲结构、意象库、视角、直白和说教、钩子和金句、手法、打磨清单。押韵和声调的细节在 `references/rhyme-and-tone.md`。要点：

1. **先设计后动笔**：定好歌名、一句话主题、情绪线，列出 15–30 个属于这首歌的意象。
2. **字数跟旋律走**：一个音一个字，拖腔用 `~`；断句和气口跟着旋律的长音。
3. **画面代替情绪词**：用画面和动作，不直接说「孤单」「想你」。钩子放在副歌旋律最突出的地方，争取写出一两句能单独拿出来的金句。
4. **韵为意思服务**：可以宽韵、斜押、换韵。要双押、三押时用 `rhyme` 核对，但不硬凑。
5. **关键字防倒字**：上行大跳的高音别放第三声，下行大跳别放第二声；长高音放开口音。
6. **和用户协作**：方向没定时给 2–3 版不同方向；保留用户认可的句子；每处改动说明依据。

## 6. 校验

```bash
ml check 模板.json 歌词.txt    # 字数、韵脚、倒字、闭口音、人称和虚词统计、简谱对照
ml rhyme 歌词.txt --ref 1      # 每句和第 1 句是单押、双押还是三押，宽韵是否同韵
```

- 有 ✗ 的句子改到 ✓。
- 提示类问题自己判断取舍；保留时要向用户说明理由，比如歌名钩子、和别处呼应的字。
- 不要凭感觉报字数：人工数很容易错，每改一版都跑 `check`。

## 7. 交付

- **歌词**：按段落分好，带 Suno 的段落标签（`[Verse 1]`、`[Chorus]`……）；器乐段写 `[Intro]`、`[Interlude]`、`[Outro]`。去掉 `~`，不用圆括号，因为 Suno 会把括号里的字唱出来。
- **说明**：附上段落时间表、逐句简谱对照（就是 `check` 的输出），以及押韵和关键取舍的说明。内容多就存成 Markdown 文件发给用户。
- **Suno**：如果要拿去生成、而且有 `suno-audit` 技能，先按它检查一遍。歌曲本来就是 Suno 做的话，新词每句的字数贴近原句，重新生成的旋律会更接近原曲。
- **版权**：给现成歌曲填新词，自己唱没问题；公开发布或商用前，旋律需要版权方授权。

## 踩过的坑

- **带伴奏的歌**：用 pYIN 会跟着贝斯走，扒出一串和弦根音，必须用 mix 模式（Melodia）。
- **只留中间声道**：去不掉乐器，实测二胡也混在中间。
- **云端会话的网络限制**：常常拦截模型下载（Demucs 之类的分轨模型）和 skills.sh。Melodia 不用下载模型，能正常用。
- **用户的 `music` 技能包**：在云端会话里只同步了目录，里面的子技能用不了。
- **多音字**：pypinyin 会读错，比如「长堤」读成 zhǎng，以实际唱法为准。
- **闭口音提示**：「高音」是相对整份模板的音域算的，换一份模板，提示的结果会变。
