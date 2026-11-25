"""Makes the satin background (RGBA: white sheen, black fold shadows). Usage: make_satin.py <out.png> <preview.png>

The image is not a tile: the apps scale it to fill the screen (crop), so the folds never repeat. It is 600 x 1300 px,
about the shape of a phone screen. The folds are smooth, so the low size does not show.
"""
import sys
import numpy as np
from PIL import Image

W, H = 600, 1300
rng = np.random.default_rng(23)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xx / W, yy / W          # in units of the image width


def smooth_noise(freq, octaves=3):
    """Sum of random-phase cosines: a soft, wavy field from -1 to 1."""
    out = np.zeros((H, W), np.float32)
    for o in range(octaves):
        for _ in range(5):
            k = rng.normal(0, freq * 2 ** o, 2)
            out += np.cos(2 * np.pi * (k[0] * u + k[1] * v) + rng.random() * 6.28) / (2 ** o)
    return out / np.abs(out).max()


# Folds: long waves that run from the top left to the bottom right, bent by a slow warp, as cloth that hangs.
warp = 0.22 * smooth_noise(0.35, 1) + 0.05 * smooth_noise(0.8, 1)
h = np.zeros((H, W), np.float32)
for i, (freq, amp) in enumerate([(0.9, 1.0), (1.6, 0.45), (2.7, 0.16)]):
    angle = np.deg2rad(-62 + rng.normal(0, 6))
    d = np.cos(angle) * u + np.sin(angle) * v
    phase = rng.random() * 6.28
    h += amp * np.sin(2 * np.pi * freq * (d + warp * (0.35 + 0.15 * i)) + phase)
h += 0.08 * smooth_noise(0.7, 1)
h /= np.abs(h).max()

# Normals of the height field. The light comes from the top left, as on the leather.
gy, gx = np.gradient(h * 70.0)
n = np.dstack([-gx, -gy, np.ones_like(h)])
n /= np.linalg.norm(n, axis=2, keepdims=True)
light = np.array([-0.45, -0.6, 0.66]); light /= np.linalg.norm(light)
view = np.array([0.0, 0.0, 1.0])
half = light + view; half /= np.linalg.norm(half)
diffuse = n @ light
# Satin has a strong sheen along the ridges (Blinn-Phong highlight).
spec = np.clip(n @ half, 0, 1) ** 24
shade = (diffuse - diffuse.mean()) * 1.6 + spec * 1.0
shade = np.clip(shade, -1, 1)

white = np.clip(shade, 0, 1) ** 1.2
black = np.clip(-shade, 0, 1) ** 0.95
# Peak opacity: sheen 22%, fold shadows 30%. Each platform scales this down per theme.
a = np.where(shade >= 0, white * 0.22, black * 0.30)
# A little dither, so that the 8-bit alpha shows no bands.
a = np.clip(a + rng.uniform(-0.6, 0.6, a.shape) / 255, 0, 1)
rgb = np.where(shade[..., None] >= 0, 255, 0).astype(np.uint8).repeat(3, 2)
img = np.dstack([rgb, np.round(a * 255).clip(0, 255).astype(np.uint8)])
Image.fromarray(img, 'RGBA').save(sys.argv[1], optimize=True)


# Preview: the image over the dark and light backgrounds, at the opacity each theme uses.
def over(bg_top, bg_bottom, strength):
    t = np.linspace(0, 1, H)[:, None, None]
    bg = (np.array(bg_top) * (1 - t) + np.array(bg_bottom) * t) * np.ones((H, W, 3))
    al = img[..., 3:4].astype(np.float32) / 255 * strength
    return (bg * (1 - al) + img[..., :3] * al).clip(0, 255).astype(np.uint8)


prev = np.concatenate([over((0x16, 0x18, 0x1D), (0x0A, 0x0B, 0x0E), 1.0), over((0xF8, 0xF9, 0xFA), (0xE6, 0xE8, 0xEC), 0.7)], 1)
Image.fromarray(prev).resize((W, H // 2)).save(sys.argv[2])
