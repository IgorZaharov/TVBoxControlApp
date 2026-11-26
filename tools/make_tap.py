"""Makes the button press sound: a soft click, like a computer mouse (16-bit mono WAV, 48 kHz). Usage: make_tap.py <out.wav>

The click is a short noise burst through two resonators: a bright one at 3.2 kHz (the click) and a lower one at
1.4 kHz (the plastic body). There is no long tone, so the sound stays soft. The click lasts about 15 ms,
and the file is 50 ms long with a fade to zero. It peaks at -4 dBFS. The apps play it on the media volume.
"""
import sys
import wave
import numpy as np

RATE = 48000
n = int(RATE * 0.050)
t = np.arange(n) / RATE
rng = np.random.default_rng(11)


def resonator(x, freq, bandwidth):
    """A two-pole band-pass resonator: it rings at freq and decays at a rate set by bandwidth."""
    r = np.exp(-np.pi * bandwidth / RATE)
    a1 = 2 * r * np.cos(2 * np.pi * freq / RATE)
    a2 = -r * r
    y = np.zeros_like(x)
    for i in range(len(x)):
        y[i] = x[i] + a1 * (y[i - 1] if i >= 1 else 0) + a2 * (y[i - 2] if i >= 2 else 0)
    return y


# The excitation: a very short noise burst (about 1 ms).
burst = rng.normal(0, 1, n) * np.exp(-t / 0.0006)
click = resonator(burst, 3200, 700)
body = resonator(burst, 1400, 120)
s = click / np.abs(click).max() + 0.55 * body / np.abs(body).max()
# A 0.3 ms fade in and a 10 ms fade to zero at the end, so there is no pop.
fade_in = np.clip(t / 0.0003, 0, 1)
fade_out = np.clip((t[-1] - t) / 0.010, 0, 1)
s = s * fade_in * fade_out
s = s / np.abs(s).max() * 10 ** (-4 / 20)

with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(np.round(s * 32767).astype('<i2').tobytes())
