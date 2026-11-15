# 改了歌词后有新字？运行本脚本重新从 Google Fonts 下载“只含用到的字”的子集字体
# 用法：python3 tools/fetch_fonts.py
import re, json, os, urllib.parse, subprocess
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.join(here, "..")
src = open(os.path.join(root, "data/project.js"), encoding="utf-8").read() + open(os.path.join(root, "index.html"), encoding="utf-8").read()
chars = "".join(sorted(set(c for c in src if ord(c) > 127)))
chars += "0123456789:.ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz -~()"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
for fam, name in [("Ma Shan Zheng", "mashanzheng"), ("Noto Serif SC:wght@500;900", "notoserifsc"), ("ZCOOL XiaoWei", "zcoolxiaowei")]:
    url = "https://fonts.googleapis.com/css2?family=" + urllib.parse.quote(fam) + "&text=" + urllib.parse.quote(chars)
    css = subprocess.run(["curl", "-s", "-A", UA, url], capture_output=True, text=True).stdout
    for w, u in re.findall(r"font-weight:\s*(\d+);.*?url\((https://[^)]+)\)", css, re.S):
        fn = os.path.join(root, f"assets/fonts/{name}-{w}.woff2"); subprocess.run(["curl", "-s", "-o", fn, u]); print("✓", fn)
