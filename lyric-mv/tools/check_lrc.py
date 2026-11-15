# 核对 LRC 时间与人声起点、节拍的偏差：python3 tools/check_lrc.py out/analysis/analysis.json data/lyrics.lrc
import json,re,sys,numpy as np
a=json.load(open(sys.argv[1])); lrc=open(sys.argv[2],encoding='utf-8').read().splitlines()
v=np.array(a['vocalEnv']['v']); hop=a['vocalEnv']['hop']
sm=np.convolve(v,np.ones(9)/9,'same')
on=np.array(a['vocalOnsets']); beats=np.array(a['beats'])
print("beat interval median",np.median(np.diff(beats)))
for l in lrc:
  m=re.match(r'\[(\d+):(\d+\.\d+)\](.*)',l); 
  t=int(m[1])*60+float(m[2]); txt=m[3]
  i=int(t/hop); seg=sm[max(0,i-20):i+20]
  # vocal level just before vs after
  pre=sm[max(0,i-15):i].mean(); post=sm[i:i+15].mean()
  near=on[np.argmin(abs(on-t))]; nb=beats[np.argmin(abs(beats-t))]
  print(f"{t:7.2f} onset{near-t:+.2f} beat{nb-t:+.2f} pre{pre:.2f} post{post:.2f} {txt}")
