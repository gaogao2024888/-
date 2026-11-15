"""LRC + 音频 → timeline.json：歌词句、段落、拍点、小节线、鼓点（打击乐成分的起音）。"""
import json, re, sys, warnings
import numpy as np, librosa
warnings.simplefilter("ignore")
lrc, audio = sys.argv[1], sys.argv[2]
lines = []
for raw in open(lrc, encoding="utf-8"):
    for mm, ss in re.findall(r"\[(\d+):(\d+(?:\.\d+)?)\]", raw):
        lines.append({"t": round(int(mm) * 60 + float(ss), 2), "text": re.sub(r"\[[^\]]*\]", "", raw).strip()})
lines.sort(key=lambda l: l["t"])
y, sr = librosa.load(audio, sr=22050, mono=True)
dur = len(y) / sr
_, perc = librosa.effects.hpss(y)
tempo, beats = librosa.beat.beat_track(y=y, sr=sr, hop_length=256, units="time")
env = librosa.onset.onset_strength(y=perc, sr=sr, hop_length=256)
hits = librosa.onset.onset_detect(onset_envelope=env, sr=sr, hop_length=256, units="time")
strength = np.interp(hits, librosa.times_like(env, sr=sr, hop_length=256), env)
keep = hits[strength >= np.percentile(strength, 60)]  # 只留较强的鼓点
beats = [round(float(b), 3) for b in beats]
b0 = min(range(len(beats)), key=lambda i: abs(beats[i] - 14.7))
bars = [beats[i] for i in range(b0 % 4, len(beats), 4)]
sections = [("片头", 0), ("主歌一·相遇", 14.0), ("预副歌·溪水", 41.8), ("副歌", 57.0), ("间奏·木轮", 71.3),
            ("主歌二·单车", 91.6), ("副歌后段", 113.4), ("桥段·晚风", 127.4), ("副歌·高光", 140.8), ("尾声", 156.2)]
out = {"duration": round(dur, 2), "bpm": round(float(np.atleast_1d(tempo)[0]), 1),
       "sections": [{"name": n, "t": t} for n, t in sections], "lines": lines,
       "beats": beats, "bars": bars, "hits": [round(float(h), 3) for h in keep]}
json.dump(out, open("timeline.json", "w"), ensure_ascii=False, indent=1)
print(f"时长 {dur:.1f}s，{out['bpm']} BPM，{len(beats)} 拍，{len(bars)} 小节线，{len(keep)} 个强鼓点，{len([l for l in lines if l['text']])} 句歌词")
