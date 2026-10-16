"""Import originals unchanged; derive cropped, chroma-keyed animated WebP for display."""
import json
import shutil
import sys
from pathlib import Path
import numpy as np
from PIL import Image

SOURCE = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(r'C:\Users\GC\Downloads\LookBuddy设计资产\首页GIF')
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'public' / 'pets'
OUTPUT.mkdir(parents=True, exist_ok=True)
order = ['电脑办公', '该喝水了', '看电视吃薯片', '沙发犯困', '下班路上']
files = sorted(SOURCE.rglob('*.gif'), key=lambda f: order.index(f.stem) if f.stem in order else len(order))
if not files:
    raise SystemExit(f'No GIF files found in {SOURCE}')
manifest = []
for index, source in enumerate(files):
    pet_id = f'pet-{index + 1}'
    original = OUTPUT / 'originals' / f'{pet_id}.gif'
    original.parent.mkdir(exist_ok=True)
    shutil.copy2(source, original)
    gif = Image.open(source)
    frames, durations, bounds = [], [], None
    for frame_index in range(gif.n_frames):
        gif.seek(frame_index)
        pixels = np.array(gif.convert('RGBA'))
        rgb = pixels[:, :, :3].astype(np.float32)
        dominance = rgb[:, :, 1] - np.maximum(rgb[:, :, 0], rgb[:, :, 2])
        # Retain the material's colors; remove only dominant green and soften key edges.
        key = np.clip((dominance - 20) / 100, 0, 1)
        pixels[:, :, 3] = np.minimum(pixels[:, :, 3], np.round((1 - key) * 255)).astype(np.uint8)
        spill = (dominance > 20) & (pixels[:, :, 3] > 0)
        pixels[:, :, 1][spill] = np.minimum(rgb[:, :, 1][spill], np.maximum(rgb[:, :, 0], rgb[:, :, 2])[spill] + 10).astype(np.uint8)
        frame = Image.fromarray(pixels)
        box = frame.getbbox()
        if box:
            bounds = box if bounds is None else (min(bounds[0], box[0]), min(bounds[1], box[1]), max(bounds[2], box[2]), max(bounds[3], box[3]))
        frames.append(frame)
        durations.append(max(20, gif.info.get('duration', 100)))
    bounds = bounds or (0, 0, gif.width, gif.height)
    cropped = []
    for frame in frames:
        frame = frame.crop(bounds)
        frame.thumbnail((960, 720), Image.Resampling.LANCZOS)
        cropped.append(frame)
    cropped[0].save(OUTPUT / f'{pet_id}.png')
    cropped[0].save(OUTPUT / f'{pet_id}.webp', save_all=True, append_images=cropped[1:], duration=durations, loop=0, quality=90, method=3)
    manifest.append(dict(id=pet_id, name=source.stem, animation=f'pets/{pet_id}.webp', still=f'pets/{pet_id}.png', original=f'pets/originals/{pet_id}.gif', width=cropped[0].width, height=cropped[0].height, frames=len(frames), durationMs=sum(durations)))
    print(f'Imported {index + 1}/{len(files)}: {len(frames)} frames', flush=True)
    if source.stem == '该喝水了':
        icon = cropped[0].copy()
        icon.thumbnail((64, 64))
        icon.save(OUTPUT / 'tray.png')
(OUTPUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
