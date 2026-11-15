# 打包成单文件在线预览页（字体、音频、脚本全部内联），用于发布到 claude.ai Artifact
# 用法：python3 tools/build_artifact.py out/preview.html
import base64, os, re, sys
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
R = lambda p: open(os.path.join(root, p), encoding="utf-8").read()
B = lambda p: base64.b64encode(open(os.path.join(root, p), "rb").read()).decode()
html = R("index.html")
css = R("css/app.css")
css = re.sub(r"url\(\.\./(assets/fonts/[^)]+)\)", lambda m: f"url(data:font/woff2;base64,{B(m.group(1))})", css)
body = re.search(r"<body>(.*)</body>", html, re.S).group(1)
body = re.sub(r'<script src="([^"]+)"></script>', lambda m: "<script>\n" + R(m.group(1)).replace("</script", "<\\/script") + "\n</script>", body)
body = body.replace('src="assets/song.mp3"', f'src="data:audio/mpeg;base64,{B("assets/song.mp3")}"')
body = body.replace("<script>\n", "<script>window.MV_ARTIFACT = true;</script>\n<script>\n", 1)
out = f'<title>木生遇淑柔 MV</title>\n<style>\n{css}\n</style>\n{body}'
dst = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "out/preview.html")
os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
open(dst, "w", encoding="utf-8").write(out)
print(dst, round(len(out) / 1e6, 2), "MB")
