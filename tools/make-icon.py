#!/usr/bin/env python3
# make-icon.py — 生成 app/res/mipmap-*/ic_launcher.png
# v2: 官方图标 = favicon.svg 鲸鱼字形(白) + DeepSeek 品牌蓝圆角底
# 需要 ImageMagick 先把 svg 渲染成 512px 透明 PNG(产出 app/icon512.png,已入仓;
# 换图标才需重跑): cd app && convert -background none -density 1024 favicon.svg -resize 512x512 icon512.png
# 用法: py -3 tools/make-icon.py
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, "..", "app")
RES = os.path.join(APP, "res")
GLYPH = os.path.join(APP, "icon512.png")   # ImageMagick 渲染产物

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BRAND = (0x4d, 0x6b, 0xfe)  # DeepSeek 品牌蓝
RENDER = 512

def rounded_mask(size, radius_ratio):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=int(size * radius_ratio), fill=255)
    return m

def build(size):
    base = Image.new("RGBA", (size, size), BRAND + (255,))
    base.putalpha(rounded_mask(size, 0.22))
    glyph = Image.open(GLYPH).convert("RGBA")
    bbox = glyph.getbbox()
    glyph = glyph.crop(bbox)
    gw, gh = glyph.size
    scale = (size * 0.72) / max(gw, gh)
    glyph = glyph.resize((int(gw * scale), int(gh * scale)), Image.LANCZOS)
    white = Image.new("RGBA", glyph.size, (255, 255, 255, 255))
    white.putalpha(glyph.split()[3])
    base.alpha_composite(white, ((size - white.width) // 2, (size - white.height) // 2))
    return base

def main():
    if not os.path.exists(GLYPH):
        raise SystemExit("缺 app/icon512.png —— 先在 WSL 里用 convert 渲染 favicon.svg(见文件头注释)")
    for name, px in SIZES.items():
        d = os.path.join(RES, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        build(RENDER).resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
        print("wrote", name, px)

if __name__ == "__main__":
    main()
