"""Makes a seamless pebble-leather tile (RGBA: white highlights, black creases). Usage: make_leather.py <out.png> <preview.png>"""
import sys
import numpy as np
from PIL import Image

N = 384                       # tile size in px; drawn at 3 px per dp (pt), so 128 dp
rng = np.random.default_rng(11)
yy, xx = np.mgrid[0:N, 0:N].astype(np.float32)

def worley(cells, jitter=0.85):
    """F1 and F2 distances to jittered points on a torus, in units of one cell."""
    step = N / cells
    pts = []
    for j in range(cells):
        for i in range(cells):
            pts.append(((i + 0.5 + (rng.random() - 0.5) * jitter) * step, (j + 0.5 + (rng.random() - 0.5) * jitter) * step))
    f1 = np.full((N, N), 1e9, np.float32); f2 = np.full((N, N), 1e9, np.float32); cid = np.zeros((N, N), np.int32)
    for k, (px, py) in enumerate(pts):
        dx = np.abs(xx - px); dx = np.minimum(dx, N - dx)
        dy = np.abs(yy - py); dy = np.minimum(dy, N - dy)
        d = np.sqrt(dx * dx + dy * dy) / step
        closer = d < f1
        f2 = np.where(closer, f1, np.minimum(f2, d)); cid = np.where(closer, k, cid); f1 = np.where(closer, d, f1)
    return f1, f2, cid, len(pts)

def periodic_noise(freq, octaves=3):
    """Sum of random-phase cosines with whole-number frequencies, so it tiles."""
    out = np.zeros((N, N), np.float32)
    for o in range(octaves):
        for _ in range(6):
            kx, ky = rng.integers(-freq * 2 ** o, freq * 2 ** o + 1, 2)
            out += np.cos(2 * np.pi * (kx * xx + ky * yy) / N + rng.random() * 6.28) / (2 ** o)
    return out / np.abs(out).max()

# Pebbles: domed cells with grooves between them. Each pebble has its own height.
f1, f2, cid, count = worley(22, jitter=0.95)
edge = np.clip((f2 - f1) / 0.45, 0, 1)
edge = edge * edge * (3 - 2 * edge)                       # smooth groove walls
dome = np.sqrt(np.clip(1 - (f1 / 0.9) ** 2, 0, 1))        # round pebble tops
heights = 0.7 + 0.3 * rng.random(count).astype(np.float32)
h = edge * (0.35 + 0.65 * dome) * heights[cid]
# Soft larger undulation, then fine pores.
h += 0.12 * periodic_noise(3)
h += 0.025 * rng.standard_normal((N, N)).astype(np.float32)

def blur(img, sigma):
    # Gaussian blur on the torus (through the FFT), so the tile stays seamless.
    fy = np.fft.fftfreq(N)[:, None]; fx = np.fft.fftfreq(N)[None, :]
    g = np.exp(-2 * (np.pi * sigma) ** 2 * (fx * fx + fy * fy))
    return np.real(np.fft.ifft2(np.fft.fft2(img) * g)).astype(np.float32)
h = blur(h, 1.4)

# Light from the top left: the slope toward the light is bright, the other side and the grooves are dark.
gx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) / 2
gy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) / 2
shade = -(gx * -0.7 + gy * -0.7) * 4.5           # >0 faces the light
shade += (h - h.mean()) * 0.55
shade = np.clip(shade, -1, 1)
white = np.clip(shade, 0, 1) ** 1.1
black = np.clip(-shade, 0, 1) ** 0.9
# Peak opacity: highlights 8%, creases 20%. Each platform scales this down per theme.
a = np.where(shade >= 0, white * 0.08, black * 0.20)
rgb = np.where(shade[..., None] >= 0, 255, 0).astype(np.uint8).repeat(3, 2)
tile = np.dstack([rgb, np.round(a * 255).clip(0, 255).astype(np.uint8)])
Image.fromarray(tile, 'RGBA').save(sys.argv[1], optimize=True)

# Preview: 2 x 2 tiles over the dark and light backgrounds, at the opacity each theme uses.
def over(bg_top, bg_bottom, strength):
    W = N * 2
    t = np.linspace(0, 1, W)[:, None, None]
    bg = (np.array(bg_top) * (1 - t) + np.array(bg_bottom) * t) * np.ones((W, W, 3))
    tt = np.tile(tile.astype(np.float32), (2, 2, 1))
    al = tt[..., 3:4] / 255 * strength
    return (bg * (1 - al) + tt[..., :3] * al).clip(0, 255).astype(np.uint8)
prev = np.concatenate([over((0x16, 0x18, 0x1D), (0x0A, 0x0B, 0x0E), 1.0), over((0xF8, 0xF9, 0xFA), (0xE6, 0xE8, 0xEC), 0.55)], 1)
Image.fromarray(prev).save(sys.argv[2])
crop = Image.fromarray(prev).crop((0, 0, 384, 384)).resize((768, 768), Image.NEAREST); crop.save(sys.argv[2].replace('.png', '-zoom.png'))
