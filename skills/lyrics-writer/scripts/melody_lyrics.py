#!/usr/bin/env python3
# /// script
# requires-python = ">=3.9"
# dependencies = [
#     "librosa>=0.10",
#     "soundfile>=0.12",
#     "mido>=1.3",
#     "pypinyin>=0.50",
#     "essentia>=2.1b6.dev1110; sys_platform == 'linux'",
# ]
# ///
"""按旋律填词：音频 / MIDI → 填词模板；歌词 → 字数、押韵、倒字校验。

  uv run melody_lyrics.py info 歌.mp3                  读文件标签（Suno 的歌名、风格提示词、歌词）
  uv run melody_lyrics.py extract 清唱.wav             清唱、哼唱、分离后的人声：pYIN 逐帧测音高
  uv run melody_lyrics.py extract 整首歌.mp3           带伴奏会自动识别，改用 Melodia 提主旋律
  uv run melody_lyrics.py extract 旋律.mid --track 2   MIDI：Basic Pitch / SOME / 扒谱软件导出
  uv run melody_lyrics.py sections 歌.melody.json      按音区分出人声段和器乐段
  uv run melody_lyrics.py lines 歌.mp3 歌.melody.json --section 主歌1=15.8 --section 副歌=45.5
                                                       按拍子把人声切成两小节一句，数每句几个字
  uv run melody_lyrics.py template 简谱.txt -o 模板.json  手工整理的简谱 → 校验模板
  uv run melody_lyrics.py check 模板.json 歌词.txt     字数、韵脚、倒字、闭口音、人称和虚词
  uv run melody_lyrics.py rhyme 歌词.txt               句尾单押 / 双押 / 三押、宽韵

没有 uv 时：pip install librosa soundfile mido pypinyin（带伴奏还要 essentia，Windows 装不了），
再用 python3 运行。只做 check 只需要 pypinyin；只处理 MIDI 只需要 mido。
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import tempfile
import warnings
from dataclasses import dataclass
from pathlib import Path
from statistics import median

PC_NAMES = ["C", "♭D", "D", "♭E", "E", "F", "♯F", "G", "♭A", "A", "♭B", "B"]
MINOR_NAMES = ["C", "♯C", "D", "♭E", "E", "F", "♯F", "G", "♯G", "A", "♭B", "B"]
DEGREES = ["1", "♯1", "2", "♭3", "3", "4", "♯4", "5", "♯5", "6", "♭7", "7"]
OCTAVE_MARKS = {-2: "̤", -1: "̣", 0: "", 1: "̇", 2: "̈"}  # 简谱高低音点
# Krumhansl-Kessler 调性轮廓
MAJOR_PROFILE = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
MINOR_PROFILE = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
BIG_LEAP = 5  # 半音数；纯四度及以上算大跳
MELODY_TRACK = re.compile(r"vocal|voice|vox|sing|melody|lead|人声|主唱|旋律", re.I)

RHYME_GROUPS = {  # 十三辙（pypinyin strict 韵母写法，ü 记作 v）
    "发花": ["a", "ia", "ua"],
    "梭波": ["o", "e", "uo"],
    "乜斜": ["ie", "ve", "üe", "ê"],
    "一七": ["i", "v", "ü", "er"],
    "姑苏": ["u"],
    "怀来": ["ai", "uai"],
    "灰堆": ["ei", "uei", "ui"],
    "遥条": ["ao", "iao"],
    "油求": ["ou", "iou", "iu"],
    "言前": ["an", "ian", "uan", "van", "üan"],
    "人辰": ["en", "in", "uen", "un", "vn", "ün"],
    "江阳": ["ang", "iang", "uang"],
    "中东": ["eng", "ing", "ong", "iong", "ueng"],
}
FINAL_TO_GROUP = {f: g for g, finals in RHYME_GROUPS.items() for f in finals}
CLOSED_FINALS = {"i", "u", "v", "ü"}  # 闭口音，长高音上难唱开
WIDE_RHYME = {"言前": "an/ang", "江阳": "an/ang", "人辰": "en/eng/ong", "中东": "en/eng/ong", "一七": "i/ü"}
VOICE_RANGES = {"male": (46, 72), "female": (53, 79)}  # 单个音：男声 ♭B2–C5，女声 F3–G5
VOICE_MEDIANS = {"male": (45, 67), "female": (52, 74)}  # 一句的中位音：男声 A2–G4，女声 E3–D5
ASCII_DEGREES = ["1", "#1", "2", "b3", "3", "4", "#4", "5", "#5", "6", "b7", "7"]
STEPS = {"1": 0, "2": 2, "3": 4, "4": 5, "5": 7, "6": 9, "7": 11}
PRONOUNS, FILLERS = "你我他她", "的了吗吧"

CJK = re.compile(r"[㐀-䶿一-鿿豈-﫿]")
TAG_LINE = re.compile(r"^\s*[\[【(（].*[\]】)）]\s*$")  # 整行是段落标签或和声
INLINE_TAG = re.compile(r"\[[^\]]*\]|【[^】]*】")
BACKING = re.compile(r"\([^)]*\)|（[^）]*）")  # 括号里是和声，不占主旋律的音
MELISMA = set("~～-—")


@dataclass
class Note:
    start: float  # 秒
    end: float
    pitch: int  # MIDI 音高
    beats: float  # 时值（拍）


# ---------------- 乐理小工具 ----------------


def note_name(pitch: int) -> str:
    return f"{PC_NAMES[pitch % 12]}{pitch // 12 - 1}"


def pearson(a: list[float], b: list[float]) -> float:
    ma, mb = sum(a) / len(a), sum(b) / len(b)
    cov = sum((x - ma) * (y - mb) for x, y in zip(a, b))
    va = sum((x - ma) ** 2 for x in a) ** 0.5
    vb = sum((y - mb) ** 2 for y in b) ** 0.5
    return cov / (va * vb) if va and vb else 0.0


def detect_key(notes: list[Note]) -> dict:
    hist = [0.0] * 12
    for n in notes:
        hist[n.pitch % 12] += n.end - n.start
    _, tonic, mode = max(
        (pearson(hist, [profile[(pc - tonic) % 12] for pc in range(12)]), tonic, mode)
        for tonic in range(12)
        for mode, profile in (("major", MAJOR_PROFILE), ("minor", MINOR_PROFILE))
    )
    do = tonic if mode == "major" else (tonic + 3) % 12  # 小调按关系大调记 1，主音是 6
    if mode == "major":
        label = f"1={PC_NAMES[do]}（{PC_NAMES[tonic]} 大调）"
    else:
        label = f"1={PC_NAMES[do]}（{MINOR_NAMES[tonic]} 小调，以 6 为主音）"
    return {"tonic": tonic, "mode": mode, "do": do, "label": label}


def choose_do(notes: list[Note], do_pc: int) -> int:
    """选中音区的 1，让加点的音最少。"""
    pitches = [n.pitch for n in notes]
    candidates = [do_pc + 12 * k for k in range(-1, 11)]
    return min(candidates, key=lambda d: (sum(abs((p - d) // 12) for p in pitches), -d))


def jianpu(pitch: int, do_pitch: int) -> str:
    octave, step = divmod(pitch - do_pitch, 12)
    mark = OCTAVE_MARKS.get(octave, ("'" * octave) if octave > 0 else ("," * -octave))
    return DEGREES[step] + mark


def ascii_jianpu(pitch: int, do_pitch: int, long: bool = False) -> str:
    """简谱的纯文本写法：, 低八度，' 高八度，# / b 升降，结尾 - 表示长音。"""
    octave, step = divmod(pitch - do_pitch, 12)
    mark = "'" * octave if octave > 0 else "," * -octave
    return ASCII_DEGREES[step] + mark + ("-" if long else "")


def parse_ascii_note(token: str, do_pitch: int) -> tuple[int, bool]:
    m = re.fullmatch(r"([#b]?)([1-7])([,']*)(-?)", token)
    if not m:
        raise SystemExit(f"看不懂这个音：{token}（例：5, 是低八度的 5，6' 是高八度的 6，#4 是升 4，结尾加 - 表示长音）")
    accidental = {"#": 1, "b": -1, "": 0}[m.group(1)]
    octave = m.group(3).count("'") - m.group(3).count(",")
    return do_pitch + STEPS[m.group(2)] + accidental + 12 * octave, bool(m.group(4))


def parse_note_name(name: str) -> int:
    """B3、♭E4、F#2 这样的音名 → MIDI 音高。"""
    m = re.fullmatch(r"([A-Ga-g])([#b♯♭]?)(-?\d)", name.strip())
    if not m:
        raise SystemExit(f"看不懂这个音名：{name}（例：B3、♭E4、F#2）")
    base = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}[m.group(1).upper()]
    accidental = {"#": 1, "♯": 1, "b": -1, "♭": -1, "": 0}[m.group(2)]
    return base + accidental + 12 * (int(m.group(3)) + 1)


def voice_range(args) -> tuple[int, int]:
    if getattr(args, "range", None):
        lo, _, hi = args.range.partition(":")
        return parse_note_name(lo), parse_note_name(hi)
    return VOICE_RANGES[args.voice]


def move_arrow(prev: int | None, cur: int) -> str:
    if prev is None:
        return "·"
    d = cur - prev
    if d == 0:
        return "→"
    if d > 0:
        return "⇑" if d >= BIG_LEAP else "↑"
    return "⇓" if -d >= BIG_LEAP else "↓"


def fmt_beats(beats: float) -> str:
    quarters = max(1, round(beats * 4))
    whole, frac = divmod(quarters, 4)
    return (str(whole) if whole else "") + ["", "¼", "½", "¾"][frac]


def mmss(t: float) -> str:
    m, s = divmod(t, 60)
    return f"{int(m)}:{s:04.1f}"


# ---------------- 音频 → 音符 ----------------


def load_audio(path: Path, sr: int, start: float | None, end: float | None):
    import librosa

    offset = start or 0.0
    duration = (end - offset) if end else None
    native = {".wav", ".flac", ".ogg", ".mp3", ".aif", ".aiff"}
    ffmpeg = shutil.which("ffmpeg")
    with warnings.catch_warnings(), tempfile.TemporaryDirectory() as tmp:
        warnings.simplefilter("ignore")
        if path.suffix.lower() not in native and ffmpeg:
            wav = Path(tmp) / "in.wav"
            subprocess.run(
                [ffmpeg, "-v", "error", "-y", "-i", str(path), "-ac", "1", "-ar", str(sr), str(wav)],
                check=True,
            )
            path = wav
        try:
            y, _ = librosa.load(str(path), sr=sr, mono=True, offset=offset, duration=duration)
        except Exception as exc:  # noqa: BLE001
            raise SystemExit(f"读不了这个音频：{exc}\n装好 ffmpeg，或先转成 wav 再试。") from exc
    return y


def segment_frames(midi, ok, rms, min_len: int) -> list[tuple[int, int, int]]:
    """把逐帧音高切成音符（起始帧, 结束帧, 音高）：按音高变化切，碎片并回邻居，
    再按“音量掉下去又起来”（换字时的辅音）切开同音反复。"""
    import numpy as np
    from scipy.ndimage import median_filter

    n = len(midi)
    midi, ok = midi.copy(), ok.copy()

    # pYIN 偶尔丢 1–2 帧，音高前后一致就补上
    i = 0
    while i < n:
        if ok[i]:
            i += 1
            continue
        j = i
        while j < n and not ok[j]:
            j += 1
        if i > 0 and j < n and j - i <= 2 and abs(midi[i - 1] - midi[j]) < 1.0:
            ok[i:j] = True
            midi[i:j] = (midi[i - 1] + midi[j]) / 2
        i = j

    def pitch_of(seg: list[int]) -> float:
        return float(np.median(midi[seg[0] : seg[1]]))

    notes: list[tuple[int, int, int]] = []
    i = 0
    while i < n:
        if not ok[i]:
            i += 1
            continue
        j = i
        while j < n and ok[j]:
            j += 1
        if j - i >= min_len:
            size = min(7, j - i if (j - i) % 2 else j - i - 1)  # 奇数窗口，不超过这段长度
            q = np.rint(median_filter(midi[i:j], size=size, mode="nearest")).astype(int)
            segs: list[list[int]] = []
            s = i
            for k in range(i + 1, j):
                if q[k - i] != q[k - i - 1]:
                    segs.append([s, k])
                    s = k
            segs.append([s, j])
            # 过短的碎片（滑音、颤音）并进音高最近的邻居
            while len(segs) > 1:
                k = min(range(len(segs)), key=lambda t: segs[t][1] - segs[t][0])
                if segs[k][1] - segs[k][0] >= min_len:
                    break
                p = pitch_of(segs[k])
                nbrs = [t for t in (k - 1, k + 1) if 0 <= t < len(segs)]
                t = min(nbrs, key=lambda t: (abs(pitch_of(segs[t]) - p), -(segs[t][1] - segs[t][0])))
                a, b = min(k, t), max(k, t)
                segs[a : b + 1] = [[segs[a][0], segs[b][1]]]
            merged: list[list[int]] = []
            for seg in segs:
                if merged and round(pitch_of(merged[-1])) == round(pitch_of(seg)):
                    merged[-1][1] = seg[1]
                else:
                    merged.append(seg)
            # 同一个音里音量明显掉下去又起来，按两个字算
            for s, e in merged:
                pitch = int(round(pitch_of([s, e])))
                cut = s
                for m in range(s + min_len, e - min_len + 1):
                    if m - cut < min_len or rms[m] > rms[m - 1] or rms[m] > rms[m + 1]:
                        continue
                    left = rms[max(cut, m - 12) : m].max()
                    right = rms[m + 1 : min(e, m + 13)].max()
                    if rms[m] < 0.6 * min(left, right):
                        notes.append((cut, m, pitch))
                        cut = m
                notes.append((cut, e, pitch))
        i = j
    return notes


def melodia_f0(y, sr: int, hop: int, fmin: float, fmax: float):
    """带伴奏的整首歌：用 Melodia 提主旋律（通常就是人声）。返回每帧频率，0 表示这帧没有旋律。"""
    try:
        import essentia

        essentia.log.infoActive = False
        essentia.log.warningActive = False
        import essentia.standard as es
    except ImportError as exc:
        raise SystemExit(
            "这段音频带伴奏，要用 essentia 从整首歌里提主旋律。Linux 上 uv run 会自动安装；"
            "其他系统试试 pip install essentia（Windows 装不了，请先分离出人声）。\n"
            "如果其实是清唱或分离好的人声，加 --mode vocal。"
        ) from exc
    import numpy as np

    audio = es.EqualLoudness(sampleRate=sr)(y.astype(np.float32))
    pitch, _ = es.PredominantPitchMelodia(
        sampleRate=sr, frameSize=2048, hopSize=hop, minFrequency=max(80.0, fmin), maxFrequency=fmax
    )(audio)
    return np.asarray(pitch, dtype=float)


def harmonic_energy(y, sr: int, hop: int, f0):
    """每帧只量旋律音的前 5 个泛音上的能量。伴奏一直在响，整体音量看不出换字，
    但人声泛音在辅音处会掉下去。"""
    import numpy as np
    import librosa

    spec = np.abs(librosa.stft(y, n_fft=2048, hop_length=hop))
    energy = np.zeros(len(f0))
    for t in range(min(spec.shape[1], len(f0))):
        if f0[t] > 0:
            bins = np.rint(np.arange(1, 6) * f0[t] * 2048 / sr).astype(int)
            energy[t] = sum(spec[b - 1 : b + 2, t].max() for b in bins if b < spec.shape[0] - 1)
    return energy


def notes_from_audio(path: Path, args) -> tuple[list[Note], dict]:
    import numpy as np
    import librosa

    sr, hop = 44100, 512  # 每帧约 11.6 毫秒
    y = load_audio(path, sr, args.start, args.end)
    if len(y) < sr:
        raise SystemExit("音频不到 1 秒，没法分析。")
    fmin, fmax = librosa.note_to_hz(args.fmin), librosa.note_to_hz(args.fmax)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        rms = librosa.feature.rms(y=y, frame_length=1024, hop_length=hop)[0]
        # 清唱在换气处几乎没声音；有伴奏时最安静的时候也很响
        backing = float(np.percentile(rms, 10) / max(float(np.percentile(rms, 90)), 1e-9))
        mode = args.mode if args.mode != "auto" else ("mix" if backing >= 0.1 else "vocal")
        if mode == "mix":
            f0 = melodia_f0(y, sr, hop, fmin, fmax)
            n = min(len(f0), len(rms))
            f0 = f0[:n]
            ok = f0 > 0
            level = np.convolve(harmonic_energy(y, sr, hop, f0), np.ones(3) / 3, "same")
        else:
            # 46 毫秒的分析窗，字与字之间短短的辅音停顿也能听出来
            f0, voiced, _ = librosa.pyin(
                librosa.resample(y, orig_sr=sr, target_sr=sr // 2),
                fmin=fmin,
                fmax=fmax,
                sr=sr // 2,
                frame_length=1024,
                hop_length=hop // 2,
            )
            n = min(len(f0), len(rms))
            f0, voiced = f0[:n], voiced[:n]
            level = np.convolve(rms[:n], np.ones(3) / 3, "same")
            ok = voiced & np.isfinite(f0)
            if ok.sum() >= 20:
                ok &= level >= 0.03 * np.percentile(level[ok], 95)  # 比最响处低 30 dB 以上的当噪声
        if ok.sum() < 20:
            raise SystemExit("没听出清晰的旋律。换清唱、哼唱或分离后的人声，或者改用 MIDI。")
        tuning = float(librosa.pitch_tuning(f0[ok], bins_per_octave=12))  # 跑调/非标准音高校正
        midi = np.full(n, np.nan)
        midi[ok] = librosa.hz_to_midi(f0[ok]) - tuning
        if args.bpm:
            bpm, estimated = float(args.bpm), False
        else:
            tempo, _ = librosa.beat.beat_track(y=y, sr=sr, hop_length=hop)
            bpm, estimated = float(np.atleast_1d(tempo)[0]) or 100.0, True

    fps = sr / hop
    offset = args.start or 0.0
    notes = []
    for s, e, pitch in segment_frames(midi, ok, level, max(2, round(args.min_note * fps))):
        start, end = offset + s / fps, offset + e / fps
        notes.append(Note(start, end, pitch, (end - start) * bpm / 60))
    meta = {
        "source_type": "audio",
        "mode": mode,
        "mode_auto": args.mode == "auto",
        "backing": round(backing, 3),
        "bpm": round(bpm, 1),
        "bpm_estimated": estimated,
        "tuning_cents": round(tuning * 100),
    }
    return notes, meta


# ---------------- MIDI → 音符 ----------------


def notes_from_midi(path: Path, args) -> tuple[list[Note], dict]:
    try:
        import mido
    except ImportError as exc:
        raise SystemExit("缺少 mido：pip install mido（或用 uv run 运行）") from exc

    mid = mido.MidiFile(str(path))
    tpb = mid.ticks_per_beat
    tempos = []
    for track in mid.tracks:
        tick = 0
        for msg in track:
            tick += msg.time
            if msg.type == "set_tempo":
                tempos.append((tick, msg.tempo))
    tempos.sort(key=lambda x: x[0])
    if not tempos or tempos[0][0] > 0:
        tempos.insert(0, (0, 500000))
    tempo_map = []  # (起始 tick, 该处秒数, 速度)
    sec, last_tick, last_tempo = 0.0, 0, tempos[0][1]
    for tick, tempo in tempos:
        sec += (tick - last_tick) * last_tempo / 1e6 / tpb
        tempo_map.append((tick, sec, tempo))
        last_tick, last_tempo = tick, tempo

    def to_sec(tick: int) -> float:
        base = tempo_map[0]
        for item in tempo_map:
            if item[0] > tick:
                break
            base = item
        return base[1] + (tick - base[0]) * base[2] / 1e6 / tpb

    # 按（轨道, 通道）收集音符，跳过鼓（通道 10）
    groups: dict[tuple[int, int], list[tuple[int, int, int]]] = {}
    names: dict[int, str] = {}
    for idx, track in enumerate(mid.tracks):
        tick = 0
        active: dict[tuple[int, int], list[int]] = {}
        for msg in track:
            tick += msg.time
            if msg.type == "track_name":
                names[idx] = msg.name.strip()
            if msg.type not in ("note_on", "note_off") or msg.channel == 9:
                continue
            key = (msg.channel, msg.note)
            if msg.type == "note_on" and msg.velocity > 0:
                active.setdefault(key, []).append(tick)
            elif active.get(key):
                start = active[key].pop(0)
                if tick > start:
                    groups.setdefault((idx, msg.channel), []).append((start, tick, msg.note))
    if not groups:
        raise SystemExit("这个 MIDI 里没有音符。")

    multi_channel = {i for i, _ in groups if sum(1 for j, _ in groups if j == i) > 1}
    candidates = []
    for (idx, ch), raw in sorted(groups.items()):
        name = names.get(idx) or f"轨道{idx}"
        if idx in multi_channel:
            name += f" ch{ch + 1}"
        mean = sum(p for _, _, p in raw) / len(raw)
        candidates.append({"name": name, "count": len(raw), "mean": mean, "raw": raw})

    if args.track:
        if not 1 <= args.track <= len(candidates):
            raise SystemExit(f"--track 要在 1–{len(candidates)} 之间。")
        chosen = args.track - 1
    else:
        named = [i for i, c in enumerate(candidates) if MELODY_TRACK.search(c["name"])]
        pool = named or [i for i, c in enumerate(candidates) if c["count"] >= 8] or list(range(len(candidates)))
        chosen = max(pool, key=lambda i: candidates[i]["mean"])

    # 有和弦就取最高音（skyline），变成单旋律
    melody: list[list[int]] = []
    for st, en, p in sorted(candidates[chosen]["raw"], key=lambda x: (x[0], -x[2])):
        if melody and st < melody[-1][1]:
            if p > melody[-1][2]:
                melody[-1][1] = st
                if melody[-1][1] <= melody[-1][0]:
                    melody.pop()
                melody.append([st, en, p])
            continue
        melody.append([st, en, p])

    notes = [Note(to_sec(st), to_sec(en), p, (en - st) / tpb) for st, en, p in melody]
    if args.start is not None or args.end is not None:
        lo, hi = args.start or 0.0, args.end or float("inf")
        notes = [n for n in notes if lo <= n.start < hi]
    first_tick = melody[0][0] if melody else 0
    tempo_at = [t for tick, _, t in tempo_map if tick <= first_tick][-1]
    meta = {
        "source_type": "midi",
        "bpm": round(float(args.bpm) if args.bpm else 60e6 / tempo_at, 1),
        "bpm_estimated": False,
        "track": candidates[chosen]["name"],
        "tracks": [
            f"{i + 1} {c['name']}·{c['count']} 音" + (" ←" if i == chosen else "")
            for i, c in enumerate(candidates)
        ],
    }
    return notes, meta


# ---------------- 音符 → 模板 ----------------


def split_phrases(notes: list[Note], gap: float, max_notes: int) -> list[list[Note]]:
    phrases: list[list[Note]] = []
    cur: list[Note] = []
    for n in notes:
        if cur and n.start - cur[-1].end >= gap:
            phrases.append(cur)
            cur = []
        cur.append(n)
    if cur:
        phrases.append(cur)

    def split_long(p: list[Note]) -> list[list[Note]]:
        if len(p) <= max_notes or len(p) < 8:
            return [p]
        # 从中段“音长 + 后面停顿”最大的地方断开，两边至少各 4 个音
        i = max(range(3, len(p) - 4), key=lambda i: p[i + 1].start - p[i].start)
        return split_long(p[: i + 1]) + split_long(p[i + 1 :])

    return [part for p in phrases for part in split_long(p)]


def build_template(notes: list[Note], meta: dict, source: str, gap: float, max_notes: int) -> dict:
    if not notes:
        raise SystemExit("没有扒到任何音。")
    key = detect_key(notes)
    do_pitch = choose_do(notes, key["do"])
    lo, hi = min(n.pitch for n in notes), max(n.pitch for n in notes)
    long_sec = max(0.4, 1.8 * median(n.end - n.start for n in notes))
    high_from = lo + 0.7 * (hi - lo) if hi - lo >= BIG_LEAP else float("inf")

    phrases = []
    for i, ph in enumerate(split_phrases(notes, gap, max_notes), 1):
        rec = {
            "index": i,
            "start": round(ph[0].start, 2),
            "end": round(ph[-1].end, 2),
            "repeat_of": None,
            "similar_to": None,
            "notes": [],
        }
        prev = None
        for n in ph:
            rec["notes"].append(
                {
                    "start": round(n.start, 3),
                    "end": round(n.end, 3),
                    "pitch": n.pitch,
                    "name": note_name(n.pitch),
                    "jianpu": jianpu(n.pitch, do_pitch),
                    "beats": round(n.beats, 3),
                    "long": n.end - n.start >= long_sec,
                    "high": n.pitch >= high_from,
                    "move": move_arrow(prev, n.pitch),
                }
            )
            prev = n.pitch
        seq = [n.pitch for n in ph]
        for other in phrases:
            oseq = [x["pitch"] for x in other["notes"]]
            if len(oseq) != len(seq):
                continue
            diff = sum(a != b for a, b in zip(seq, oseq))
            if diff == 0:
                rec["repeat_of"], rec["similar_to"] = other["index"], None
                break
            if rec["similar_to"] is None and diff <= max(1, len(seq) // 5):
                rec["similar_to"] = other["index"]
        phrases.append(rec)

    return {
        "version": 1,
        "source": source,
        **meta,
        "key": key,
        "do_pitch": do_pitch,
        "range": [note_name(lo), note_name(hi)],
        "long_note_sec": round(long_sec, 2),
        "phrases": phrases,
    }


def render_template(t: dict) -> str:
    ph = t["phrases"]
    counts = [len(p["notes"]) for p in ph]
    if t["source_type"] == "audio":
        how = "带伴奏，用 Melodia 提主旋律" if t["mode"] == "mix" else "清唱/人声，用 pYIN 测音高"
        if t["mode_auto"]:
            how += "（自动判断，不对就用 --mode vocal 或 --mode mix）"
        src = f"音频，{how}；音准偏差 {t['tuning_cents']:+d} 音分已校正"
    elif t["source_type"] == "spec":
        src = "手工整理的简谱"
    else:
        src = f"MIDI 轨「{t['track']}」"
        if len(t["tracks"]) > 1:
            src += "（可选：" + "，".join(t["tracks"]) + "；换轨用 --track N）"
    bpm = f"≈{t['bpm']:g} BPM" if t.get("bpm") else "未知"
    if t.get("bpm_estimated"):
        bpm += "（自动估计，常差一倍，可用 --bpm 指定）"
    lines = [
        f"# 填词模板 · {t['source']}",
        "",
        f"- 旋律来源：{src}",
        f"- 调性：{t['key']['label']}　速度：{bpm}",
        f"- 音域：{t['range'][0]}–{t['range'][1]}",
        f"- 共 {len(ph)} 句、{sum(counts)} 个音 → 每句字数：{' / '.join(map(str, counts))}",
    ]
    repeats = [f"第 {p['index']} 句 = 第 {p['repeat_of']} 句" for p in ph if p["repeat_of"]]
    similar = [f"第 {p['index']} 句 ≈ 第 {p['similar_to']} 句" for p in ph if p["similar_to"]]
    if repeats or similar:
        lines.append("- 旋律重复（这些句子字数、断句保持一致）：" + "；".join(repeats + similar))
    tiny = sum(1 for c in counts if c <= 2)
    if tiny >= 3:
        lines.append(f"- 注意：有 {tiny} 句只有 1–2 个音，可能被拆碎了，试试调大 --gap（现在按停顿断句）")
    lines += [
        "",
        f"图例：□ 一个音填一个字　■ 长音（≥{t['long_note_sec']:g} 秒，放开口音 a/o/e/ai/ao/ang）"
        "　◆ 句尾韵脚　↑↓ 级进　⇑⇓ 大跳（≥纯四度）",
    ]
    for p in ph:
        notes = p["notes"]
        title = f"### 第 {p['index']} 句 · {len(notes)} 个音 · {mmss(p['start'])} 起"
        if p["repeat_of"]:
            title += f" · 旋律同第 {p['repeat_of']} 句"
        elif p["similar_to"]:
            title += f" · 近似第 {p['similar_to']} 句"
        slots = ["◆" if i == len(notes) - 1 else ("■" if n["long"] else "□") for i, n in enumerate(notes)]
        lines += [
            "",
            title,
            "",
            "| 简谱 | " + " | ".join(n["jianpu"] for n in notes) + " |",
            "|---" * (len(notes) + 1) + "|",
            "| 拍 | " + " | ".join(fmt_beats(n["beats"]) for n in notes) + " |",
            "| 走向 | " + " | ".join(n["move"] for n in notes) + " |",
            "| 填词 | " + " | ".join(slots) + " |",
        ]
    return "\n".join(lines)


def cmd_extract(args) -> None:
    path = Path(args.input)
    if not path.exists():
        raise SystemExit(f"找不到文件：{path}")
    if path.suffix.lower() in (".mid", ".midi"):
        notes, meta = notes_from_midi(path, args)
    else:
        notes, meta = notes_from_audio(path, args)
    tpl = build_template(notes, meta, path.name, args.gap, args.max_notes)
    out = Path(args.out) if args.out else path.with_suffix(".melody.json")
    out.write_text(json.dumps(tpl, ensure_ascii=False, indent=1), encoding="utf-8")
    print(render_template(tpl))
    print(f"\n模板已保存：{out}　写好歌词后运行：check {out} 歌词.txt")


# ---------------- 歌词校验 ----------------


def parse_lyrics(text: str) -> list[dict]:
    lines = []
    for raw in text.splitlines():
        if not raw.strip() or TAG_LINE.match(raw):
            continue
        body = BACKING.sub("", INLINE_TAG.sub("", raw)).replace("——", "~")
        units: list[dict] = []
        word = ""
        for i, ch in enumerate(body):
            nxt = body[i + 1] if i + 1 < len(body) else ""
            if ch.isascii() and (ch.isalnum() or ch == "'" or (ch == "-" and word and nxt.isalpha())):
                word += ch
                continue
            if word:
                units.append({"text": word, "kind": "latin", "extra": 0})
                word = ""
            if CJK.match(ch):
                units.append({"text": ch, "kind": "zh", "extra": 0})
            elif ch in MELISMA and units:
                units[-1]["extra"] += 1  # 拖腔：多占一个音
        if word:
            units.append({"text": word, "kind": "latin", "extra": 0})
        if units:
            lines.append({"raw": raw.strip(), "units": units})
    return lines


def annotate_pinyin(lines: list[dict]) -> None:
    try:
        from pypinyin import Style, pinyin
    except ImportError as exc:
        raise SystemExit("缺少 pypinyin：pip install pypinyin（或用 uv run 运行）") from exc

    for line in lines:
        zh = [u for u in line["units"] if u["kind"] == "zh"]
        if not zh:
            continue
        text = "".join(u["text"] for u in zh)  # 整句一起转，多音字按词判断
        finals = pinyin(text, style=Style.FINALS, strict=True)
        tones = pinyin(text, style=Style.TONE3, neutral_tone_with_five=True)
        marks = pinyin(text, style=Style.TONE)
        if not len(finals) == len(tones) == len(marks) == len(zh):
            finals = [pinyin(u["text"], style=Style.FINALS, strict=True)[0] for u in zh]
            tones = [pinyin(u["text"], style=Style.TONE3, neutral_tone_with_five=True)[0] for u in zh]
            marks = [pinyin(u["text"], style=Style.TONE)[0] for u in zh]
        for u, f, t, m in zip(zh, finals, tones, marks):
            u["final"] = f[0]
            u["tone"] = int(t[0][-1]) if t[0][-1:].isdigit() else 5
            u["pinyin"] = m[0]


def fit_hints(units: list[dict], notes: list[dict]) -> list[str]:
    hints = []
    k = 0
    for pos, u in enumerate(units, 1):
        span = notes[k : k + 1 + u["extra"]]
        prev = notes[k - 1] if k > 0 else None
        k += 1 + u["extra"]
        if u["kind"] != "zh":
            continue
        ch = f"第 {pos} 字「{u['text']}」({u['pinyin']})"
        if prev is not None:
            step = span[0]["pitch"] - prev["pitch"]
            if u["tone"] == 3 and step >= BIG_LEAP:
                hints.append(f"{ch}是第三声，却在上行大跳（+{step} 半音）的高音上，容易听成一声/二声")
            if u["tone"] == 2 and step <= -BIG_LEAP:
                hints.append(f"{ch}是第二声，却落在下行大跳（{step} 半音）上，容易听成四声")
        if u["final"] in CLOSED_FINALS and any(n["long"] and n["high"] for n in span):
            hints.append(f"{ch}是闭口音，落在长高音上不好唱开，可换 a/o/e/ai/ao/ang 类韵母的字")
    return hints


def rhyme_of(units: list[dict]) -> str:
    zh = [u for u in units if u["kind"] == "zh"]
    if not zh:
        return "–"
    last = zh[-1]
    group = FINAL_TO_GROUP.get(last["final"], "?")
    return f"{last['text']} {last['pinyin']}·{group}"


def render_sheet(index: int, units: list[dict], notes: list[dict]) -> list[str]:
    cells: list[str] = []
    for u in units:
        cells.append(u["text"])
        cells.extend(["~"] * u["extra"])
    width = max(len(cells), len(notes))
    head = [notes[i]["jianpu"] if i < len(notes) else "（多）" for i in range(width)]
    words = [cells[i] if i < len(cells) else "□" for i in range(width)]
    return [
        f"**第 {index} 句**",
        "",
        "| " + " | ".join(head) + " |",
        "|---" * width + "|",
        "| " + " | ".join(words) + " |",
        "",
    ]


def cmd_check(args) -> None:
    tpl = json.loads(Path(args.template).read_text(encoding="utf-8"))
    text = sys.stdin.read() if args.lyrics == "-" else Path(args.lyrics).read_text(encoding="utf-8")
    lines = parse_lyrics(text)
    if not lines:
        raise SystemExit("歌词文件里没有可校验的歌词行。")
    annotate_pinyin(lines)
    phrases = tpl["phrases"]

    rows, sheets, hints = [], [], []
    ok = 0
    groups: dict[str, int] = {}
    for i, line in enumerate(lines, 1):
        units = line["units"]
        used = sum(1 + u["extra"] for u in units)
        rhyme = rhyme_of(units)
        if rhyme != "–":
            g = rhyme.rsplit("·", 1)[-1]
            groups[g] = groups.get(g, 0) + 1
        if i > len(phrases):
            rows.append(f"| {i} | {line['raw']} | {used}/– ✗ | {rhyme} | 模板只有 {len(phrases)} 句 |")
            continue
        notes = phrases[i - 1]["notes"]
        need = len(notes)
        if used == need:
            ok += 1
            status = f"{used}/{need} ✓"
        else:
            status = f"{used}/{need} ✗ {'多' if used > need else '少'} {abs(used - need)}"
        notes_col = []
        if any(u["kind"] == "latin" for u in units):
            notes_col.append("英文/数字按 1 词 1 音算，请核对音节")
        if used == need:
            for h in fit_hints(units, notes):
                hints.append(f"- 第 {i} 句 {h}")
        rows.append(f"| {i} | {line['raw']} | {status} | {rhyme} | {'；'.join(notes_col)} |")
        sheets += render_sheet(i, units, notes)

    out = [
        f"# 歌词校验 · {Path(args.lyrics).name if args.lyrics != '-' else '（标准输入）'} ↔ {tpl['source']}",
        "",
        f"- 字数：{ok}/{len(phrases)} 句对上（歌词 {len(lines)} 行，模板 {len(phrases)} 句）",
    ]
    if len(lines) < len(phrases):
        out.append(f"- 还差第 {len(lines) + 1}–{len(phrases)} 句没写")
    if groups:
        out.append("- 韵脚：" + "、".join(f"{g}辙 ×{c}" for g, c in sorted(groups.items(), key=lambda x: -x[1])))
    body = "".join(line["raw"] for line in lines)
    counts = [f"{c}×{body.count(c)}" for c in PRONOUNS if c in body]
    fills = [f"{c}×{body.count(c)}" for c in FILLERS if c in body]
    out.append(f"- 人称：{' '.join(counts) or '无'}　虚词：{' '.join(fills) or '无'}")
    out += ["", "| 句 | 歌词 | 字/音 | 韵脚 | 备注 |", "|---|---|---|---|---|", *rows]
    if hints:
        out += ["", "## 提示（不是硬错，唱一遍再决定改不改）", "", *hints]
    out += ["", "## 简谱对照", "", *sheets]
    print("\n".join(out).rstrip())


# ---------------- 文件信息 / 分段 / 按拍子定字数 / 手工模板 / 押韵 ----------------


def cmd_info(args) -> None:
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        raise SystemExit("需要 ffprobe（装 ffmpeg 就有）。")
    path = Path(args.input)
    res = subprocess.run(
        [ffprobe, "-v", "error", "-show_entries", "format=duration,bit_rate:format_tags", "-of", "json", str(path)],
        capture_output=True,
        text=True,
    )
    if res.returncode:
        raise SystemExit(f"读不了文件信息：{res.stderr.strip()}")
    fmt = json.loads(res.stdout).get("format", {})
    tags = {k.lower(): v for k, v in fmt.get("tags", {}).items()}
    labels = {"title": "歌名", "artist": "作者", "album": "专辑", "genre": "风格", "date": "日期", "comment": "备注"}
    lines = [f"# 文件信息 · {path.name}", ""]
    if fmt.get("duration"):
        lines.append(f"- 时长：{mmss(float(fmt['duration']))}")
    for key, label in labels.items():
        if tags.get(key):
            lines.append(f"- {label}：{tags[key]}")
    lyric_keys = [k for k in tags if "lyric" in k]
    for k in lyric_keys:
        text = "\n".join(s for s in tags[k].splitlines() if s.strip())
        lines += ["", f"## 标签里的歌词（{k}）", "", "```", text, "```"]
    if "suno" in tags.get("comment", "").lower():
        lines += ["", "这是 Suno 生成的歌：歌词标签里常带着风格提示词（括号里的英文），也可能被截断，以实际内容为准。"]
    if len(lines) <= 3 and not lyric_keys:
        lines.append("- 没有歌名、歌词之类的标签")
    print("\n".join(lines))


def phrase_stats(p: dict) -> dict:
    pitches = [n["pitch"] for n in p["notes"]]
    span = p["notes"][-1]["end"] - p["notes"][0]["start"]
    return {
        "lo": min(pitches),
        "hi": max(pitches),
        "med": median(pitches),
        "rate": len(pitches) / span if span > 0 else 0.0,
    }


def cmd_sections(args) -> None:
    tpl = json.loads(Path(args.template).read_text(encoding="utf-8"))
    lo, hi = voice_range(args)
    mlo, mhi = (lo, hi) if args.range else VOICE_MEDIANS[args.voice]
    rows = []
    for p in tpl["phrases"]:
        st = phrase_stats(p)
        above = sum(1 for n in p["notes"] if n["pitch"] > hi)
        if st["med"] > mhi or above > 1:
            kind = "高音区乐器"
        elif st["med"] < mlo:
            kind = "低音区乐器"
        else:
            kind = "人声"
        rows.append((p, st, kind))

    out = [
        f"# 分段 · {tpl['source']}",
        "",
        f"判断规则：一句的中位音在 {note_name(mlo)}–{note_name(mhi)}、而且高过 {note_name(hi)} 的音不超过 1 个，算人声；"
        "否则算乐器（--voice male/female 或 --range 低:高 可改）。",
        "",
        "| 句 | 时间 | 音数 | 音域 | 中位音 | 判断 | 简谱 |",
        "|---|---|---|---|---|---|---|",
    ]
    for p, st, kind in rows:
        jp = " ".join(n["jianpu"] for n in p["notes"])
        out.append(
            f"| {p['index']} | {mmss(p['start'])}–{mmss(p['end'])} | {len(p['notes'])} | "
            f"{note_name(st['lo'])}–{note_name(st['hi'])} | {note_name(round(st['med']))} | {kind} | {jp} |"
        )

    # 相邻同类合并成段；夹在两段同类中间的单句当作夹杂，并进去
    blocks: list[list] = []
    for p, _, kind in rows:
        if blocks and blocks[-1][0] == kind:
            blocks[-1][2] = p
        else:
            blocks.append([kind, p, p])
    i = 1
    while i < len(blocks) - 1:
        single = blocks[i][1] is blocks[i][2]
        if single and blocks[i - 1][0] == blocks[i + 1][0]:
            blocks[i - 1][2] = blocks[i + 1][2]
            del blocks[i : i + 2]
        else:
            i += 1
    out += ["", "## 段落推测", ""]
    for kind, first, last in blocks:
        n = last["index"] - first["index"] + 1
        out.append(f"- {mmss(first['start'])}–{mmss(last['end'])} · {kind}（第 {first['index']}–{last['index']} 句，共 {n} 句）")
    out += [
        "",
        "人声段的开头时间可以直接给 lines 命令的 --section 用（会自动对齐到最近的拍子）。"
        "乐器旋律太响时会盖住人声：人声段里夹着的高音句多半是乐器，不要往里填词。",
    ]
    print("\n".join(out))


def cmd_lines(args) -> None:
    import bisect

    import numpy as np
    import librosa

    tpl = json.loads(Path(args.template).read_text(encoding="utf-8"))
    notes = [n for p in tpl["phrases"] for n in p["notes"]]
    if not notes:
        raise SystemExit("模板里没有音符。")
    do_pitch = tpl.get("do_pitch") or choose_do([Note(n["start"], n["end"], n["pitch"], 0) for n in notes], tpl["key"]["do"])
    long_sec = tpl.get("long_note_sec", 0.4)
    lo, hi = voice_range(args)

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        y = load_audio(Path(args.audio), 22050, None, None)
        tempo, beats = librosa.beat.beat_track(y=y, sr=22050, hop_length=256, units="time")
    beats = [float(b) for b in beats]
    if len(beats) < 8:
        raise SystemExit("拍子检测失败（音频太短或没有明显节拍）。")
    step = float(np.median(np.diff(beats)))
    bpm = float(np.atleast_1d(tempo)[0])

    def beat_pos(t: float) -> float:
        i = bisect.bisect_right(beats, t) - 1
        if i < 0:
            return (t - beats[0]) / step
        if i + 1 >= len(beats):
            return i + (t - beats[i]) / step
        return i + (t - beats[i]) / (beats[i + 1] - beats[i])

    # 只留人声音区；很短又紧贴下一个音的碎音是滑音，并进下一个音
    voc = [n for n in notes if lo <= n["pitch"] <= hi]
    clean = [
        n
        for i, n in enumerate(voc)
        if not (n["end"] - n["start"] < 0.11 and i + 1 < len(voc) and voc[i + 1]["start"] - n["end"] < 0.06)
    ]
    for n in clean:
        n["b"] = beat_pos(n["start"])
    others = [(beat_pos(n["start"]), n) for n in notes if n["pitch"] > hi]

    per = args.bars * args.beats_per_bar
    out = [
        f"# 每句字数 · {tpl['source']}",
        "",
        f"- 速度：≈{bpm:.1f} BPM（拍点自动检测）；每句 {args.bars} 小节、每小节 {args.beats_per_bar} 拍，"
        f"小节线前 {args.pickup:g} 拍内的音算下一句的弱起",
        f"- 1={PC_NAMES[do_pitch % 12]}（中音 1 = {note_name(do_pitch)}）；只数人声音区 {note_name(lo)}–{note_name(hi)} 的音，"
        "不到 0.11 秒的滑音并进后一个音；— 表示长音",
        "",
    ]
    spec = [
        f"# 1={note_name(do_pitch)}",
        f"# 来源：{tpl['source']}，用 lines 自动切分；对着原曲改好再用 template 生成校验模板",
        "",
    ]
    for sec in args.section:
        name, eq, rest = sec.partition("=")
        if not eq:
            raise SystemExit(f"--section 要写成 名字=开始秒数[:句数]，例如 主歌1=15.8:8（收到：{sec}）")
        start, _, count = rest.partition(":")
        n_lines = int(count) if count else args.lines
        b0 = min(range(len(beats)), key=lambda i: abs(beats[i] - float(start)))
        out += [
            f"## {name}（第一个小节线 {mmss(beats[b0])}，{n_lines} 句）",
            "",
            "| 句 | 开始 | 字数 | 简谱 | 备注 |",
            "|---|---|---|---|---|",
        ]
        spec.append(f"# {name}")
        for k in range(n_lines):
            lo_b = b0 + per * k - args.pickup
            hi_b = lo_b + per
            ln = [n for n in clean if lo_b <= n["b"] < hi_b]
            masked = sum(1 for b, _ in others if lo_b <= b < hi_b)
            t0 = beats[max(0, min(len(beats) - 1, int(lo_b)))]
            jp = " ".join(n["jianpu"] + ("—" if n["end"] - n["start"] >= long_sec else "") for n in ln)
            note = f"另有 {masked} 个高音区的音，可能是乐器盖住了人声" if masked else ""
            if not ln:
                note = "没扒到人声，可能是器乐段"
            out.append(f"| {name}-{k + 1} | {mmss(t0)} | {len(ln)} | {jp or '—'} | {note} |")
            tokens = " ".join(ascii_jianpu(n["pitch"], do_pitch, n["end"] - n["start"] >= long_sec) for n in ln)
            spec.append(f"{name}-{k + 1}: {tokens}" if ln else f"# {name}-{k + 1}: （没扒到人声）")
        out.append("")
        spec.append("")
    out.append(
        "字数可靠程度：同一段旋律出现两次（比如两遍副歌）时两边一致，就比较可靠；被乐器盖住的句子只能估，"
        "请用户对着原曲哼一遍确认，或者给人声分轨 / MIDI。"
    )
    print("\n".join(out))
    if args.out:
        Path(args.out).write_text("\n".join(spec), encoding="utf-8")
        print(f"\n简谱规格已保存：{args.out}（核对、修改后运行：template {args.out} -o 模板.json）")


def cmd_template(args) -> None:
    text = Path(args.spec).read_text(encoding="utf-8")
    do_pitch = parse_note_name(args.do) if args.do else None
    lines: list[tuple[str, list[str]]] = []
    for raw in text.splitlines():
        s = raw.strip()
        if not s:
            continue
        if s.startswith("#"):
            m = re.match(r"#\s*1\s*=\s*([A-Ga-g][#b♯♭]?-?\d)", s)
            if m and do_pitch is None:
                do_pitch = parse_note_name(m.group(1))
            continue
        name, sep, body = re.split(r"([:：])", s, maxsplit=1) if re.search(r"[:：]", s) else ("", "", s)
        if not body.split():
            continue
        lines.append((name.strip() or f"第{len(lines) + 1}句", body.split()))
    if do_pitch is None:
        raise SystemExit("不知道 1 是哪个音：在规格文件里写一行 # 1=B3，或者用 --do B3。")
    if not lines:
        raise SystemExit("规格文件里没有旋律行。")

    parsed = [(name, [parse_ascii_note(t, do_pitch) for t in toks]) for name, toks in lines]
    pitches = [p for _, ns in parsed for p, _ in ns]
    lo, hi = min(pitches), max(pitches)
    high_from = lo + 0.7 * (hi - lo) if hi - lo >= BIG_LEAP else float("inf")
    phrases = []
    for i, (name, ns) in enumerate(parsed, 1):
        rec_notes, prev = [], None
        for p, long in ns:
            rec_notes.append(
                {
                    "start": 0,
                    "end": 0,
                    "pitch": p,
                    "name": note_name(p),
                    "jianpu": jianpu(p, do_pitch),
                    "beats": 1,
                    "long": long,
                    "high": p >= high_from,
                    "move": move_arrow(prev, p),
                }
            )
            prev = p
        phrases.append(
            {"index": i, "label": name, "start": 0, "end": 0, "repeat_of": None, "similar_to": None, "notes": rec_notes}
        )
    for rec in phrases:  # 旋律相同的句子
        seq = [n["pitch"] for n in rec["notes"]]
        for other in phrases[: rec["index"] - 1]:
            if [n["pitch"] for n in other["notes"]] == seq:
                rec["repeat_of"] = other["index"]
                break
    tpl = {
        "version": 1,
        "source": Path(args.spec).name,
        "source_type": "spec",
        "bpm": args.bpm,
        "bpm_estimated": False,
        "key": {"tonic": do_pitch % 12, "mode": "major", "do": do_pitch % 12, "label": f"1={PC_NAMES[do_pitch % 12]}"},
        "do_pitch": do_pitch,
        "range": [note_name(lo), note_name(hi)],
        "long_note_sec": 0.4,
        "phrases": phrases,
    }
    out = Path(args.out) if args.out else Path(args.spec).with_suffix(".melody.json")
    out.write_text(json.dumps(tpl, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"模板已保存：{out}（{len(phrases)} 句：" + "、".join(f"{p['label']} {len(p['notes'])} 音" for p in phrases) + "）")


def rhyme_tail(units: list[dict], k: int = 3) -> list[dict]:
    return [u for u in units if u["kind"] == "zh"][-k:]


def cmd_rhyme(args) -> None:
    text = sys.stdin.read() if args.lyrics == "-" else Path(args.lyrics).read_text(encoding="utf-8")
    lines = parse_lyrics(text)
    if not lines:
        raise SystemExit("没有可分析的歌词行。")
    annotate_pinyin(lines)
    if not 1 <= args.ref <= len(lines):
        raise SystemExit(f"--ref 要在 1–{len(lines)} 之间。")
    base = rhyme_tail(lines[args.ref - 1]["units"])

    def group(u: dict) -> str:
        return FINAL_TO_GROUP.get(u["final"], "?")

    out = [
        f"# 押韵分析（对照第 {args.ref} 句「{''.join(u['text'] for u in base)}」）",
        "",
        "单押 = 最后一个字同韵；双押 = 最后两个字分别同韵；三押 = 最后三个字。宽韵把前后鼻音、i/ü 算作一家。",
        "",
        "| 句 | 句尾 | 拼音 | 韵辙 | 和对照句 | 宽韵 |",
        "|---|---|---|---|---|---|",
    ]
    for i, line in enumerate(lines, 1):
        tail = rhyme_tail(line["units"])
        if not tail:
            out.append(f"| {i} | {line['raw']} | – | – | – | – |")
            continue
        n = 0
        for a, b in zip(tail[::-1], base[::-1]):
            if group(a) != group(b):
                break
            n += 1
        strict = "（对照句）" if i == args.ref else {3: "三押", 2: "双押", 1: "单押"}.get(n, "不押")
        last, ref_last = group(tail[-1]), group(base[-1])
        wide = WIDE_RHYME.get(last, last)
        loose = "同韵" if wide == WIDE_RHYME.get(ref_last, ref_last) else "不同"
        out.append(
            f"| {i} | {''.join(u['text'] for u in tail)} | {' '.join(u['pinyin'] for u in tail)} | "
            f"{'·'.join(group(u) for u in tail)} | {strict} | {wide}（{loose}） |"
        )
    out += ["", "多音字可能读错（比如「长堤」被读成 zhǎng），以实际唱法为准。"]
    print("\n".join(out))


# ---------------- 命令行 ----------------


def main() -> None:
    parser = argparse.ArgumentParser(description="按旋律填词：扒旋律出填词模板，校验歌词字数、押韵和倒字。")
    sub = parser.add_subparsers(dest="cmd", required=True)

    ex = sub.add_parser("extract", help="音频或 MIDI → 填词模板（Markdown + JSON）")
    ex.add_argument("input", help="音频（wav/mp3/m4a/flac…）或 MIDI（.mid）")
    ex.add_argument("-o", "--out", help="模板 JSON 保存路径（默认：<文件名>.melody.json）")
    ex.add_argument("--bpm", type=float, help="指定速度；不填则自动估计（音频）或读 MIDI")
    ex.add_argument("--gap", type=float, default=0.3, help="断句的最短停顿，秒（默认 0.3）")
    ex.add_argument("--max-notes", type=int, default=14, help="一句超过这么多音就从长音处拆开（默认 14）")
    ex.add_argument("--track", type=int, help="MIDI 用第几条轨（输出里有编号）")
    ex.add_argument("--start", type=float, help="从第几秒开始分析")
    ex.add_argument("--end", type=float, help="分析到第几秒")
    ex.add_argument(
        "--mode",
        choices=["auto", "vocal", "mix"],
        default="auto",
        help="音频：vocal=清唱/哼唱/人声分轨，mix=带伴奏的整首歌，auto=自动判断（默认）",
    )
    ex.add_argument("--min-note", type=float, default=0.08, help="音频：最短音长，秒（默认 0.08）")
    ex.add_argument("--fmin", default="C2", help="音频：最低音（默认 C2）")
    ex.add_argument("--fmax", default="C6", help="音频：最高音（默认 C6）")
    ex.set_defaults(func=cmd_extract)

    ck = sub.add_parser("check", help="校验歌词：字数、韵脚（十三辙）、倒字、闭口音、人称和虚词")
    ck.add_argument("template", help="extract 或 template 生成的 .melody.json")
    ck.add_argument("lyrics", help="歌词文本，一行对应一句；- 表示从标准输入读")
    ck.set_defaults(func=cmd_check)

    inf = sub.add_parser("info", help="读音频文件的标签：歌名、作者、Suno 风格提示词和歌词")
    inf.add_argument("input")
    inf.set_defaults(func=cmd_info)

    def add_voice(p):
        p.add_argument("--voice", choices=sorted(VOICE_RANGES), default="male", help="人声音区预设（默认 male）")
        p.add_argument("--range", help="自定人声音区，如 A2:C5")

    se = sub.add_parser("sections", help="按音区把 extract 的结果分成人声段和器乐段")
    se.add_argument("template", help="extract 生成的 .melody.json")
    add_voice(se)
    se.set_defaults(func=cmd_sections)

    li = sub.add_parser("lines", help="按拍子把人声切成固定小节数一句，数每句的字数，输出可编辑的简谱规格")
    li.add_argument("audio", help="原音频（用来检测拍点）")
    li.add_argument("template", help="同一首歌 extract 生成的 .melody.json")
    li.add_argument("--section", action="append", required=True, help="段名=第一个小节线的秒数[:句数]，可写多次")
    li.add_argument("--lines", type=int, default=8, help="每段默认几句（默认 8）")
    li.add_argument("--bars", type=int, default=2, help="几小节算一句（默认 2）")
    li.add_argument("--beats-per-bar", type=int, default=4, help="每小节几拍（默认 4）")
    li.add_argument("--pickup", type=float, default=1.25, help="小节线前多少拍内的音算下一句的弱起（默认 1.25）")
    li.add_argument("-o", "--out", help="把简谱规格存成文本，给 template 用")
    add_voice(li)
    li.set_defaults(func=cmd_lines)

    tp = sub.add_parser("template", help="手工整理的简谱规格 → 校验模板 .melody.json")
    tp.add_argument("spec", help="规格文本：每行「句名: 1, 2, 3 5,- …」，, 低八度 ' 高八度，- 长音")
    tp.add_argument("-o", "--out", help="输出路径（默认：<文件名>.melody.json）")
    tp.add_argument("--do", help="中音 1 是哪个音，如 B3（也可在规格里写 # 1=B3）")
    tp.add_argument("--bpm", type=float, help="速度，只用来显示")
    tp.set_defaults(func=cmd_template)

    rh = sub.add_parser("rhyme", help="句尾押韵分析：单押 / 双押 / 三押、宽韵")
    rh.add_argument("lyrics", help="歌词文本；- 表示从标准输入读")
    rh.add_argument("--ref", type=int, default=1, help="拿第几句当对照（默认第 1 句）")
    rh.set_defaults(func=cmd_rhyme)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
