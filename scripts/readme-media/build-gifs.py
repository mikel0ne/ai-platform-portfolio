"""Builds the README GIFs from the PNGs written by l1-shots.mjs and autoops-shots.mjs.

Usage: python build-gifs.py <shots-dir> <out-dir>
The Langfuse trace screenshots are taken by hand in the browser pane and must be in <shots-dir> as
langfuse-autoops-trace.jpg.
"""
import sys

from PIL import Image

shots, out = sys.argv[1], sys.argv[2]
WIDTH = 760
CHAT_BOX = (180, 90, 1320, 960)


def frame(name, box=None):
    im = Image.open(f'{shots}/{name}').convert('RGB')
    if box:
        im = im.crop(box)
    return im.resize((WIDTH, round(im.height * WIDTH / im.width)), Image.LANCZOS)


def build(path, frames):
    height = max(f.height for _, f in frames)
    padded = []
    for duration, f in frames:
        canvas = Image.new('RGB', (WIDTH, height), (255, 255, 255))
        canvas.paste(f, (0, 0))
        padded.append((duration, canvas))
    # per-frame palettes: one shared palette turns the blue and green of the UI grey
    quantized = [c.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
                 for _, c in padded]
    quantized[0].save(path, save_all=True, append_images=quantized[1:],
                      duration=[d for d, _ in padded], loop=0, optimize=True, disposal=1)
    print(path, len(quantized), 'frames')


l1 = []
for scenario in ('tracking', 'cancel', 'jailbreak'):
    for i, (label, ms) in enumerate((('start', 700), ('typed', 1100), ('sent', 700), ('reply', 3500))):
        l1.append((ms, frame(f'{scenario}-{i:02d}-{label}.png', CHAT_BOX)))
build(f'{out}/l1-copilot-demo.gif', l1)

build(f'{out}/autoops-search-demo.gif', [
    (1500, frame('autoops-00-start.png', (255, 0, 1395, 900))),
    (4500, frame('autoops-01-answer.png', (255, 80, 1395, 1570))),
    (4000, frame('langfuse-autoops-trace.jpg')),
])
