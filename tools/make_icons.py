"""Makes the app icons from the logo image. Usage: make_icons.py <logo> <repo root> <preview dir>"""
import os, sys
import numpy as np
from PIL import Image, ImageDraw

logo, repo, preview = sys.argv[1:4]
src = Image.open(logo).convert('RGB')
# The rounded square spans x 26..1211, y 32..1224 in the 1254 px logo.
sq = np.asarray(src.crop((22, 32, 1214, 1224))).astype(np.float32)  # 1192 x 1192
n = sq.shape[0]

# The logo corners are black outside a corner radius of about 298 px. The store masks use a smaller radius,
# so fill each corner with the border color, projected out along the radius.
r, inset = 300.0, 16.0
yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
for cx, cy in [(r, r), (n - 1 - r, r), (r, n - 1 - r), (n - 1 - r, n - 1 - r)]:
    inx = (xx < r) if cx < n / 2 else (xx > n - 1 - r)
    iny = (yy < r) if cy < n / 2 else (yy > n - 1 - r)
    dx, dy = xx - cx, yy - cy
    d = np.sqrt(dx * dx + dy * dy) + 1e-6
    m = inx & iny & (d > r - inset)
    sx = np.clip(np.round(cx + dx / d * (r - inset)), 0, n - 1).astype(int)
    sy = np.clip(np.round(cy + dy / d * (r - inset)), 0, n - 1).astype(int)
    sq[m] = sq[sy[m], sx[m]]
full = Image.fromarray(sq.clip(0, 255).astype(np.uint8))

def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, optimize=True)

# iOS: one 1024 px icon without alpha. Google Play: a 512 px icon.
save(full, f'{repo}/ios/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png', 1024)
save(full, f'{repo}/store/google-play/icon-512.png', 512)
save(full, f'{repo}/store/app-store/icon-1024.png', 1024)

# Android adaptive icon foreground: 108 dp layer, the D-pad ring at about 58 dp, inside the 66 dp safe zone.
scale = 0.65
side = round(n * scale)
small = np.asarray(full.resize((side, side), Image.LANCZOS))
pad = (1080 - side) // 2
fg = np.pad(small, ((pad, 1080 - side - pad), (pad, 1080 - side - pad), (0, 0)), mode='edge')
fg_img = Image.fromarray(fg)
for folder, px in {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}.items():
    save(fg_img, f'{repo}/android/app/src/main/res/mipmap-{folder}/ic_launcher_foreground.png', px)

# Previews: Android circle and rounded square masks (72 of 108 dp), iOS mask (radius 22.37%).
def masked(img, inner, shape):
    size = 256
    cut = img.crop(((img.width - inner) // 2,) * 2 + ((img.width + inner) // 2,) * 2).resize((size, size), Image.LANCZOS)
    m = Image.new('L', (size * 4, size * 4), 0)
    dr = ImageDraw.Draw(m)
    if shape == 'circle': dr.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else: dr.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 4 * shape), fill=255)
    out = Image.new('RGB', (size, size), (235, 235, 235))
    out.paste(cut, (0, 0), m.resize((size, size), Image.LANCZOS))
    return out
sheet = Image.new('RGB', (256 * 4 + 50, 256 + 20), (235, 235, 235))
for i, im in enumerate([masked(fg_img, 720, 'circle'), masked(fg_img, 720, 0.2), masked(fg_img, 720, 0.08), masked(full, n, 0.2237)]):
    sheet.paste(im, (10 + i * 266, 10))
sheet.save(f'{preview}/icons-preview.png')
print('fg side', side, 'pad', pad)
