"""Keep source WebP bytes; crop transparent display bounds with CSS, never re-encode motion."""
import hashlib
import json
import shutil
import sys
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source_root = Path(sys.argv[1])
assets = json.loads((source_root / 'HOME_REMINDER_ASSETS.json').read_text(encoding='utf-8'))
target = root / 'public/pets'
manifest = json.loads((target / 'manifest.json').read_text(encoding='utf-8'))[:5]
ids = {'drink':'drink', 'stretch':'stretch', 'posture':'posture', 'fatigue':'fatigue', 'happy':'happy', 'concern':'concern', 'morning':'morning'}
for asset in assets:
    key = ids[asset['key']]
    source = source_root / asset['rawPath']
    data = source.read_bytes()
    sha = hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
    assert sha == asset['sha'], source
    shutil.copyfile(source, target / f'{key}.webp')
    im = Image.open(source)
    im.convert('RGBA').save(target / f'{key}.png')
    durations = []
    for frame in range(im.n_frames):
        im.seek(frame); im.load(); durations.append(im.info.get('duration', 100))
    im.convert('RGBA').save(target / f'{key}-last.png')
    # Android's viewport excludes the posture export's top/bottom black bands.
    bounds = asset['bounds']
    if key == 'posture': bounds = [bounds[0], bounds[1], bounds[2], min(bounds[3], 562)]
    manifest.append(dict(id=key, name=source.stem.replace('buddy_', ''), animation=f'pets/{key}.webp',
        still=f'pets/{key}.png', width=im.width, height=im.height, bounds=bounds,
        frames=im.n_frames, durationMs=sum(durations), last=f'pets/{key}-last.png', sourceGitBlob=sha))
names = {'drink':'该喝水了','stretch':'伸展','posture':'坐姿调整','fatigue':'趴桌犯困','happy':'暖暖的表情','concern':'安慰倾听用户','morning':'首次打开'}
for item in manifest:
    if item['id'] in names: item['name'] = names[item['id']]
(target / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print('Imported', len(manifest)-5, 'original scene WebP files')

import runpy
runpy.run_path(str(root / 'scripts' / 'import_home_defaults.py'), run_name='__main__')
