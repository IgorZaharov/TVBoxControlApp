"""Makes the button press sound: a short, soft tap (16-bit mono WAV, 44.1 kHz). Usage: make_tap.py <out.wav>

The tap is a damped 1.1 kHz tone with a little low body and a 2 ms noise click at the start. It lasts about 50 ms
and peaks at -3 dBFS. Phone speakers do not play low tones, so the 1.1 kHz tone carries most of the sound.
The apps play it on the media volume.
"""
import sys
import wave
import numpy as np

RATE = 44100
n = int(RATE * 0.050)
t = np.arange(n) / RATE
rng = np.random.default_rng(5)

tone = 0.75 * np.sin(2 * np.pi * 1100 * t) * np.exp(-t / 0.011)
body = 0.45 * np.sin(2 * np.pi * 280 * t) * np.exp(-t / 0.016)
click = rng.normal(0, 1, n) * np.exp(-t / 0.0007)
# A soft low-pass on the click: a 5-sample moving average.
click = np.convolve(click, np.ones(5) / 5, mode='same') * 0.35
s = tone + body + click
# A 1 ms fade in and a fade to zero at the end, so there is no pop.
fade_in = np.clip(t / 0.001, 0, 1)
fade_out = np.clip((t[-1] - t) / 0.008, 0, 1)
s = s * fade_in * fade_out
s = s / np.abs(s).max() * 10 ** (-3 / 20)

with wave.open(sys.argv[1], 'wb') as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes((s * 32767).astype('<i2').tobytes())
