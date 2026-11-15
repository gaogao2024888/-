# 音频分析：近似人声分离 + 节拍 + 强拍(鼓点) + 能量曲线
# 用法：python3 tools/analyze.py assets/song.mp3 out/analysis   （依赖 pip install librosa soundfile）
# 产出：vocals_est.wav / inst_est.wav（试听核对用）、analysis.json，并更新 data/analysis.js
# 说明：环境里拿不到 Demucs 模型权重时，用 REPET-SIM + 中置声道做近似人声分离，足够核对歌词起点
import sys, os, json, numpy as np, librosa, soundfile as sf
src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
y, sr = librosa.load(src, sr=22050, mono=False)
mono = librosa.to_mono(y)
# --- rough vocal stem: center channel * REPET-SIM soft mask
center = (y[0]+y[1])/2; side=(y[0]-y[1])/2
S_full, phase = librosa.magphase(librosa.stft(center))
S_filter = librosa.decompose.nn_filter(S_full, aggregate=np.median, metric='cosine', width=int(librosa.time_to_frames(2, sr=sr)))
S_filter = np.minimum(S_full, S_filter)
mask_v = librosa.util.softmask(S_full - S_filter, 10*S_filter, power=2)
voc = librosa.istft(mask_v*S_full*phase, length=len(center))
inst = mono - voc
sf.write(out+'/vocals_est.wav', voc, sr); sf.write(out+'/inst_est.wav', inst, sr)
# --- beats
tempo, beats = librosa.beat.beat_track(y=mono, sr=sr, units='time')
# drum-ish onsets from percussive component
perc = librosa.effects.percussive(mono)
on_env = librosa.onset.onset_strength(y=perc, sr=sr)
onsets = librosa.onset.onset_detect(onset_envelope=on_env, sr=sr, units='time', backtrack=False)
strength = on_env[librosa.time_to_frames(onsets, sr=sr)]
# strong hits = top 25%
thr = np.percentile(strength, 75)
strong = [round(float(t),3) for t,s in zip(onsets,strength) if s>=thr]
# vocal activity envelope (RMS of est vocals in vocal band)
vb = librosa.feature.rms(y=voc, frame_length=2048, hop_length=512)[0]
vt = librosa.frames_to_time(np.arange(len(vb)), sr=sr, hop_length=512)
vocal_on = librosa.onset.onset_detect(y=voc, sr=sr, units='time')
# overall energy per 0.25 s for visual intensity
rms = librosa.feature.rms(y=mono, hop_length=int(sr*0.25))[0]
rms = (rms/rms.max()).round(3).tolist()
json.dump({"tempo": float(np.atleast_1d(tempo)[0]), "beats":[round(float(b),3) for b in beats],
  "strong":strong, "vocalOnsets":[round(float(t),3) for t in vocal_on],
  "vocalEnv":{"hop":512/sr,"v":(vb/vb.max()).round(3).tolist()},
  "energy":{"hop":0.25,"v":rms}, "duration": len(mono)/sr}, open(out+'/analysis.json','w'))
print("tempo", tempo, "beats", len(beats), "strong", len(strong), "dur", len(mono)/sr)

here = os.path.dirname(os.path.abspath(__file__))
d = json.load(open(out+'/analysis.json'))
slim = {k: d[k] for k in ("beats", "strong", "energy")}
slim.update(tempo=round(d["tempo"], 2), duration=round(d["duration"], 3))
open(os.path.join(here, "..", "data", "analysis.js"), "w").write("// 由 tools/analyze.py 生成：节拍、强拍（鼓点）、整体能量曲线\nwindow.MV_ANALYSIS = " + json.dumps(slim, separators=(",", ":")) + ";\n")
print("已更新 data/analysis.js")
