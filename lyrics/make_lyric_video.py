#!/usr/bin/env python3
"""LRC + 音频 → 歌词动画 MP4（纯代码逐帧绘制，Pillow 画帧，ffmpeg 合成）。

用法：python3 make_lyric_video.py 歌词.lrc 音频.mp3 输出.mp4 [--title 歌名] [--artist 作者]
"""

import argparse
import math
import random
import re
import subprocess

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 1280, 720, 30
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
GOLD, DIM, WHITE = (255, 214, 140), (225, 232, 240), (250, 250, 250)


def parse_lrc(path):
    tags, lines = {}, []
    for raw in open(path, encoding="utf-8"):
        m = re.match(r"\[(ti|ar):(.*)\]", raw.strip())
        if m:
            tags[m.group(1)] = m.group(2)
            continue
        stamps = re.findall(r"\[(\d+):(\d+(?:\.\d+)?)\]", raw)
        text = re.sub(r"\[[^\]]*\]", "", raw).strip()
        for mm, ss in stamps:
            lines.append((int(mm) * 60 + float(ss), text))
    lines.sort()
    return tags, lines


def audio_length(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
        capture_output=True, text=True, check=True,
    )
    return float(out.stdout.strip())


def gradient(top, bottom):
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(W):
            px[x, y] = c
    return img.convert("RGBA")


def bokeh_sprite(radius, color, alpha):
    size = radius * 4
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(img).ellipse((radius, radius, size - radius, size - radius), fill=(*color, alpha))
    return img.filter(ImageFilter.GaussianBlur(radius * 0.6))


def text_sprite(text, font, color, glow=False):
    l, t, r, b = font.getbbox(text)
    pad = 24
    img = Image.new("RGBA", (r - l + pad * 2, b - t + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if glow:
        shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(shadow).text((pad - l, pad - t), text, font=font, fill=(*color, 170))
        img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(9)))
    d.text((pad - l, pad - t), text, font=font, fill=(*color, 255))
    return img


def with_alpha(img, a):
    if a >= 1:
        return img
    r, g, b, al = img.split()
    return Image.merge("RGBA", (r, g, b, al.point(lambda v: int(v * max(0.0, a)))))


def ease(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3 - 2 * x)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("lrc")
    ap.add_argument("audio")
    ap.add_argument("out")
    ap.add_argument("--title")
    ap.add_argument("--artist")
    ap.add_argument("--preview", help="只导出这些秒数的截图（逗号分隔），不出视频")
    args = ap.parse_args()

    tags, lrc = parse_lrc(args.lrc)
    title = args.title or tags.get("ti", "")
    artist = args.artist or tags.get("ar", "")
    total = audio_length(args.audio)
    sung = [(t, s) for t, s in lrc if s]
    first_vocal = sung[0][0] if sung else total

    big, small, tiny = (ImageFont.truetype(FONT, s) for s in (58, 32, 26))
    title_font = ImageFont.truetype(FONT, 96)
    base = gradient((10, 42, 52), (18, 24, 54))
    sprites = [bokeh_sprite(r, c, a) for r, c, a in ((60, (90, 170, 160), 50), (90, (120, 120, 200), 35),
                                                    (45, (255, 200, 140), 45), (70, (160, 210, 190), 40))]
    rnd = random.Random(7)
    bokeh = [(rnd.random() * W, rnd.random() * H, rnd.choice(sprites), rnd.uniform(-8, 8), rnd.uniform(-5, 5),
              rnd.uniform(0, 6.28)) for _ in range(14)]
    flies = [(rnd.random() * W, rnd.random() * H, rnd.uniform(8, 25), rnd.uniform(1.5, 3.2), rnd.uniform(0, 6.28))
             for _ in range(60)]

    # 每句预渲染：普通色、金色、上下句的小字
    cache = {}

    def sprites_for(i):
        if i not in cache:
            text = lrc[i][1]
            cache[i] = (text_sprite(text, big, DIM), text_sprite(text, big, GOLD, glow=True), text_sprite(text, small, DIM))
        return cache[i]

    title_spr = text_sprite(title, title_font, WHITE, glow=True)
    artist_spr = text_sprite(artist, tiny, DIM)

    shots = [float(s) for s in args.preview.split(",")] if args.preview else None
    ff = None if shots else subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
         "-i", "-", "-i", args.audio, "-map", "0:v", "-map", "1:a", "-c:v", "libx264", "-preset", "medium",
         "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-shortest", args.out],
        stdin=subprocess.PIPE,
    )
    frames = int(total * FPS)
    for f in ([int(s * FPS) for s in shots] if shots else range(frames)):
        t = f / FPS
        img = base.copy()
        for x, y, spr, vx, vy, ph in bokeh:
            bx = (x + vx * t + 30 * math.sin(t * 0.2 + ph)) % (W + 200) - 100
            by = (y + vy * t + 20 * math.cos(t * 0.15 + ph)) % (H + 200) - 100
            img.alpha_composite(spr, (int(bx - spr.width / 2), int(by - spr.height / 2)))
        d = ImageDraw.Draw(img)
        for x, y, speed, r, ph in flies:
            fy = (y - speed * t) % H
            fx = x + 18 * math.sin(t * 0.7 + ph)
            a = int(110 + 110 * math.sin(t * 2.1 + ph))
            d.ellipse((fx - r, fy - r, fx + r, fy + r), fill=(255, 220, 150, max(0, a)))

        # 当前句
        cur = max((i for i, (ts, _) in enumerate(lrc) if ts <= t), default=None)
        if t < first_vocal - 0.5:  # 标题卡
            a = ease(t / 1.5) * ease((first_vocal - 0.5 - t) / 1.2)
            img.alpha_composite(with_alpha(title_spr, a), ((W - title_spr.width) // 2, H // 2 - 110))
            img.alpha_composite(with_alpha(artist_spr, a), ((W - artist_spr.width) // 2, H // 2 + 20))
        elif cur is not None and lrc[cur][1]:
            start = lrc[cur][0]
            end = lrc[cur + 1][0] if cur + 1 < len(lrc) else total
            dim, gold, _ = sprites_for(cur)
            appear = ease((t - start) / 0.35)
            x0 = (W - dim.width) // 2
            y0 = H // 2 - dim.height // 2 + int(18 * (1 - appear))
            img.alpha_composite(with_alpha(dim, appear * 0.85), (x0, y0))
            # 卡拉 OK 填色：在这一句的前 90% 时间里从左到右填满
            prog = ease((t - start) / max(0.6, (end - start) * 0.9))
            cut = int(gold.width * prog)
            if cut > 0:
                img.alpha_composite(with_alpha(gold.crop((0, 0, cut, gold.height)), appear), (x0, y0))
            # 上一句、下一句
            if cur > 0 and lrc[cur - 1][1]:
                prev = sprites_for(cur - 1)[2]
                img.alpha_composite(with_alpha(prev, 0.35 * appear), ((W - prev.width) // 2, y0 - 70))
            if cur + 1 < len(lrc) and lrc[cur + 1][1]:
                nxt = sprites_for(cur + 1)[2]
                img.alpha_composite(with_alpha(nxt, 0.45), ((W - nxt.width) // 2, y0 + dim.height + 6))
        else:  # 间奏
            note = text_sprite("♪", big, GOLD, glow=True)
            a = 0.5 + 0.3 * math.sin(t * 2)
            img.alpha_composite(with_alpha(note, a), ((W - note.width) // 2, H // 2 - note.height // 2))

        # 底部进度条和歌名
        d = ImageDraw.Draw(img)
        d.rectangle((0, H - 4, W, H), fill=(255, 255, 255, 30))
        d.rectangle((0, H - 4, int(W * t / total), H), fill=(*GOLD, 200))
        if t >= first_vocal - 0.5:
            d.text((28, 22), f"{title}  ·  {artist}", font=tiny, fill=(*DIM, 140))
        if shots:
            img.convert("RGB").save(f"{args.out}.{t:06.1f}s.png")
            continue
        ff.stdin.write(img.convert("RGB").tobytes())
        if f % (FPS * 20) == 0:
            print(f"{t:6.1f}s / {total:.1f}s", flush=True)
    if shots:
        return
    ff.stdin.close()
    ff.wait()
    print("done:", args.out)


if __name__ == "__main__":
    main()
