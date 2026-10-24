"""Builds the Modrinth/CurseForge gallery images from real screenshots and measured FPS.

Usage: python3 tools/gallery.py <metal.png> <opengl.png> <metal50.png> <metalFps> <openglFps> <fps100> <fps50> <sodiumMetalFps> <sodiumOpenglFps> <vulkanFps>
  metalFps, openglFps, vulkanFps: average FPS without Sodium; sodiumMetalFps, sodiumOpenglFps: with Sodium;
  fps100, fps50: Pure Metal at Render Scale 100% and 50% (the metal.png and metal50.png screenshots).
Writes build/gallery/1-before-after.png and build/gallery/2-benchmarks.png (1920x1080); set PM_GALLERY_OUT to change the folder.
Everything is drawn at 3x and downsampled, so shapes and text are anti-aliased."""
import os, sys
from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageStat

W, H = 1920, 1080
S = 3  # supersampling factor; all layout values below are in output pixels
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("PM_GALLERY_OUT") or os.path.join(HERE, "..", "build", "gallery")
ICON = os.path.join(HERE, "..", "src", "main", "resources", "assets", "puremetal", "icon.png")
SF = "/System/Library/Fonts/SFNS.ttf"  # SF Pro, the macOS system font

TEXT = (245, 245, 247)
TEXT2 = (190, 190, 198)
TEXT3 = (140, 140, 150)
BLUE = (64, 156, 255)    # Pure Metal
ORANGE = (255, 159, 10)  # gains (macOS system orange)
GREY = (142, 142, 150)   # other renderers
MARGIN = 64
SETUP = "MacBook Air M2  ·  3420×2148  ·  Render distance 16  ·  Vsync off  ·  Same flight route  ·  Default settings"


def px(v):
    return round(v * S)


def font(size, weight="Regular"):
    f = ImageFont.truetype(SF, px(size))
    f.set_variation_by_name(weight)
    return f


def text(d, xy, s, size, weight, fill, anchor="ls"):
    d.text((px(xy[0]), px(xy[1])), s, font=font(size, weight), fill=fill, anchor=anchor)


def text_width(d, s, size, weight):
    return d.textlength(s, font=font(size, weight)) / S


def text_mid(d, x, y, s, size, weight, fill, h="l"):
    """Text whose capitals are optically centered on y."""
    f = font(size, weight)
    cap = -d.textbbox((0, 0), "H", font=f, anchor="ls")[1]
    d.text((px(x), px(y) + cap / 2), s, font=f, fill=fill, anchor=h + "s")


def rrect(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(tuple(px(v) for v in box), radius=px(r), fill=fill, outline=outline, width=px(width) if outline else 0)


def mask(size, r):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=px(r), fill=255)
    return m


def shadow(img, box, r, blur, dy, alpha):
    """Soft drop shadow under a rounded box."""
    pad = px(blur) * 3
    x0, y0, x1, y1 = (px(v) for v in box)
    layer = Image.new("L", (x1 - x0 + pad * 2, y1 - y0 + pad * 2), 0)
    ImageDraw.Draw(layer).rounded_rectangle((pad, pad, pad + x1 - x0, pad + y1 - y0), radius=px(r), fill=alpha)
    layer = layer.resize((layer.width // 4, layer.height // 4)).filter(ImageFilter.GaussianBlur(px(blur) / 4)).resize(layer.size, Image.BICUBIC)
    img.paste((0, 0, 0), (x0 - pad, y0 - pad + px(dy)), layer)


def cover(img, w, h):
    s = max(w / img.width, h / img.height)
    img = img.resize((max(w, round(img.width * s)), max(h, round(img.height * s))), Image.LANCZOS)
    x, y = (img.width - w) // 2, (img.height - h) // 2
    return img.crop((x, y, x + w, y + h))


def shade(img, stops):
    """Darken with a vertical black gradient; stops are (y, alpha) pairs in output pixels."""
    g = Image.new("L", (1, H))
    for y in range(H):
        for (ya, a), (yb, b) in zip(stops, stops[1:]):
            if ya <= y <= yb:
                t = (y - ya) / max(1, yb - ya)
                t = t * t * (3 - 2 * t)
                g.putpixel((0, y), round(a + (b - a) * t))
                break
    img.paste((0, 0, 0), (0, 0), g.resize(img.size, Image.BICUBIC))
    return img


def glass(img, box, r, tint=(14, 16, 22), opacity=0.62, outline=(255, 255, 255, 46), width=1, blur=22, shadow_alpha=110):
    """Frosted glass: blurred backdrop, dark translucent tint, hairline border, soft shadow."""
    shadow(img, box, r, blur=28, dy=14, alpha=shadow_alpha)
    pbox = tuple(px(v) for v in box)
    size = (pbox[2] - pbox[0], pbox[3] - pbox[1])
    back = img.crop(pbox)
    small = back.resize((size[0] // 6, size[1] // 6), Image.BILINEAR).filter(ImageFilter.GaussianBlur(blur / 2))
    back = ImageEnhance.Color(small.resize(size, Image.BICUBIC)).enhance(1.2)
    back = Image.blend(back, Image.new("RGB", size, tint), opacity)
    img.paste(back, pbox[:2], mask(size, r))
    d = ImageDraw.Draw(img, "RGBA")
    rrect(d, box, r, outline=outline, width=width)
    return d


def header(img, title, subtitle):
    icon = Image.open(ICON).convert("RGBA").resize((px(84), px(84)), Image.LANCZOS)
    shadow(img, (MARGIN, 52, MARGIN + 84, 136), 20, blur=12, dy=5, alpha=140)
    img.paste(icon, (px(MARGIN), px(52)), ImageChops.multiply(icon.getchannel("A"), mask(icon.size, 20)))
    d = ImageDraw.Draw(img, "RGBA")
    text(d, (MARGIN + 108, 100), title, 54, "Bold", TEXT)
    text(d, (MARGIN + 110, 135), subtitle, 25, "Regular", (225, 225, 232))
    return d


def footer(d, s):
    text_mid(d, W / 2, H - 40, s, 19, "Regular", (200, 200, 208), "m")


def pill(d, cx, cy, s, size=30, center=True):
    """Orange pill centred on (cx, cy), or with its left edge at cx when center is False."""
    w, h = text_width(d, s, size, "Bold") + size * 1.4, size * 1.75
    x = cx - w / 2 if center else cx
    rrect(d, (x, cy - h / 2, x + w, cy + h / 2), h / 2, fill=ORANGE)
    text_mid(d, x + w / 2, cy, s, size, "Bold", (34, 20, 0), "m")
    return w


def gain_text(new, old, suffix=""):
    if new >= 2 * old:
        return f"{new / old:.1f}x{suffix}"
    return f"+{round((new / old - 1) * 100)}%{suffix}"


def finish(img):
    return img.resize((W, H), Image.LANCZOS)


def before_after(metal, opengl, metal_fps, gl_fps):
    """One view split down the middle: OpenGL on the left half, Pure Metal on the right."""
    a, b = cover(opengl, px(W), px(H)), cover(metal, px(W), px(H))
    img = a.copy()
    img.paste(b.crop((px(W / 2), 0, px(W), px(H))), (px(W / 2), 0))
    shade(img, ((0, 175), (230, 60), (760, 40), (H, 190)))
    d = header(img, "Pure Metal", "Minecraft on Apple's own graphics API. Same world, same view, same settings.")
    # Divider with a soft glow.
    glow = Image.new("L", (px(40), px(H - 190 - 80)), 0)
    ImageDraw.Draw(glow).rectangle((px(18), 0, px(22), glow.height), fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(px(5)))
    img.paste((255, 255, 255), (px(W / 2 - 20), px(190)), glow)
    d = ImageDraw.Draw(img, "RGBA")
    d.rectangle((px(W / 2) - 2, px(190), px(W / 2) + 2, px(H - 80)), fill=(255, 255, 255, 235))
    for cx, label, fps, color in ((W / 4, "OpenGL", gl_fps, GREY), (3 * W / 4, "Pure Metal", metal_fps, BLUE)):
        box = (cx - 250, 360, cx + 250, 620)
        d = glass(img, box, 30, outline=color + (230,), width=2)
        t = f"{fps:.0f}"
        nw, uw, gap = text_width(d, t, 120, "Bold"), text_width(d, "FPS", 40, "Semibold"), 12
        left = cx - (nw + gap + uw) / 2
        text(d, (left, 518), t, 120, "Bold", color)
        text(d, (left + nw + gap, 518), "FPS", 40, "Semibold", color)
        text(d, (cx, 576), label, 30, "Semibold", TEXT, "ms")
    pill(d, W / 2, 712, gain_text(metal_fps, gl_fps, " FPS"), 40)
    footer(d, SETUP + "  ·  No Sodium")
    return finish(img)


def align(a, b, box, step=16, reach=128):
    """Offset of box's content in b (the camera can land a few pixels apart between runs): coarse search, then fine."""
    ref = a.crop(box).convert("L")
    best = (1e9, 0, 0)
    for s, cx, cy, r in ((step, 0, 0, reach), (1, None, None, step)):
        cx, cy = (best[1], best[2]) if cx is None else (cx, cy)
        for dy in range(cy - r, cy + r + 1, s):
            for dx in range(cx - r, cx + r + 1, s):
                moved = (box[0] + dx, box[1] + dy, box[2] + dx, box[3] + dy)
                e = ImageStat.Stat(ImageChops.difference(ref, b.crop(moved).convert("L"))).mean[0]
                if e < best[0]:
                    best = (e, dx, dy)
    return best[1], best[2]


def bar(img, box, color):
    x0, y0, x1, y1 = (px(v) for v in box)
    h = y1 - y0
    light = tuple(min(255, c + 30) for c in color)
    g = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        g.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(light, color)))
    img.paste(g.resize((x1 - x0, h)), (x0, y0), mask((x1 - x0, h), (box[3] - box[1]) / 2))


def benchmarks(metal, metal50, f):
    img = cover(metal, px(W), px(H)).filter(ImageFilter.GaussianBlur(px(14)))
    shade(img, ((0, 215), (H, 185)))
    d = header(img, "Benchmarks", "Average FPS on the same flight route. Higher is better.")
    y0, y1 = 196, 980
    gap = 36
    cw = (W - 2 * MARGIN - gap) / 2
    pad = 44

    # Left card: average FPS per renderer, one shared scale from zero.
    lx0, lx1 = MARGIN, MARGIN + cw
    d = glass(img, (lx0, y0, lx1, y1), 28)
    groups = (("Without Sodium", (("Pure Metal", f["metal"], BLUE), ("Vulkan", f["vulkan"], GREY), ("OpenGL", f["opengl"], GREY))),
              ("With Sodium", (("Pure Metal", f["s_metal"], BLUE), ("OpenGL", f["s_opengl"], GREY))))
    vmax = max(v for _, bars in groups for _, v, _ in bars) * 1.18
    bh, row, label_w = 48, 86, 176
    bx = lx0 + pad + label_w
    track = lx1 - pad - bx
    y = y0 + 92
    for name, bars in groups:
        text(d, (lx0 + pad, y), name, 34, "Bold", TEXT)
        y += 40
        for label, v, color in bars:
            text_mid(d, lx0 + pad, y + bh / 2, label, 26, "Regular", TEXT if color == BLUE else TEXT2)
            bw = max(bh, v / vmax * track)
            bar(img, (bx, y, bx + bw, y + bh), color)
            d = ImageDraw.Draw(img, "RGBA")
            text_mid(d, bx + bw + 16, y + bh / 2, f"{v:.0f}", 30, "Bold", color)
            y += row
        y += 92

    # Right card: render scale, a 3x close-up of the same spot at 100% and 50%.
    rx0, rx1 = lx1 + gap, lx1 + gap + cw
    d = glass(img, (rx0, y0, rx1, y1), 28)
    text(d, (rx0 + pad, y0 + 84), "Render Scale", 34, "Bold", TEXT)
    tw = text_width(d, "Render Scale", 34, "Bold")
    text(d, (rx0 + pad + tw + 12, y0 + 84), "(optional)", 26, "Regular", TEXT3)
    text(d, (rx0 + pad, y0 + 122), "Draws the world at fewer pixels. Menus and HUD stay sharp.", 23, "Regular", TEXT2)
    igap = 24
    iw = (cw - 2 * pad - igap) / 2
    ih = round(iw * 0.82)
    src_w, src_h = round(iw / 3), round(ih / 3)  # 3 output pixels per screenshot pixel
    sx, sy = round(metal.width * 0.66), round(metal.height * 0.42)
    box = (sx, sy, sx + src_w, sy + src_h)
    dx, dy = align(metal, metal50, box)
    box50 = (box[0] + dx, box[1] + dy, box[2] + dx, box[3] + dy)
    iy = y0 + 156
    for i, (shot, b, label, fps, color) in enumerate(((metal, box, "Native (100%)", f["fps100"], BLUE),
                                                       (metal50, box50, "50%", f["fps50"], ORANGE))):
        ix = rx0 + pad + i * (iw + igap)
        size = (px(iw), px(ih))
        shadow(img, (ix, iy, ix + iw, iy + ih), 16, blur=14, dy=8, alpha=130)
        img.paste(shot.crop(b).resize(size, Image.NEAREST), (px(ix), px(iy)), mask(size, 16))
        d = ImageDraw.Draw(img, "RGBA")
        rrect(d, (ix, iy, ix + iw, iy + ih), 16, outline=(255, 255, 255, 40))
        text(d, (ix + 2, iy + ih + 46), label, 26, "Semibold", TEXT)
        text(d, (ix, iy + ih + 104), f"{fps:.0f}", 54, "Bold", color)
        nw = text_width(d, f"{fps:.0f}", 54, "Bold")
        text(d, (ix + nw + 8, iy + ih + 104), "FPS", 26, "Semibold", color)
    pill(d, rx0 + pad, iy + ih + 168, gain_text(f["fps50"], f["fps100"], " FPS at 50%"), 28, center=False)
    text(d, (rx0 + pad, y1 - 40), "Without Sodium, Bilinear upscaler. 100% and 50% measured alternately in one run.", 19, "Regular", TEXT3)
    footer(d, SETUP + "  ·  Close-ups: same spot, 3× zoom")
    return finish(img)


if __name__ == "__main__":
    m, g, m50 = (Image.open(p).convert("RGB") for p in sys.argv[1:4])
    keys = ("metal", "opengl", "fps100", "fps50", "s_metal", "s_opengl", "vulkan")
    f = dict(zip(keys, map(float, sys.argv[4:11])))
    os.makedirs(OUT, exist_ok=True)
    before_after(m, g, f["metal"], f["opengl"]).save(os.path.join(OUT, "1-before-after.png"), optimize=True)
    benchmarks(m, m50, f).save(os.path.join(OUT, "2-benchmarks.png"), optimize=True)
