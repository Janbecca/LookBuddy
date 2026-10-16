import hashlib,json,shutil,sys
from pathlib import Path
from PIL import Image
ROOT=Path(sys.argv[1])
TARGET=Path(__file__).resolve().parents[1]/'public/pets'
manifest=[x for x in json.loads((TARGET/'manifest.json').read_text(encoding='utf-8')) if x['id']!='night']
def blob(data):return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
source=ROOT/'app/src/main/res/raw/buddy_work.webp'
shutil.copy2(source,TARGET/'pet-1.webp')
im=Image.open(source);bounds=None;durations=[]
for i in range(im.n_frames):
    im.seek(i);im.load();frame=im.convert('RGBA');box=frame.getbbox()
    if box:bounds=box if bounds is None else (min(bounds[0],box[0]),min(bounds[1],box[1]),max(bounds[2],box[2]),max(bounds[3],box[3]))
    durations.append(im.info.get('duration',100))
    if i==0:frame.save(TARGET/'pet-1.png')
frame.save(TARGET/'pet-1-last.png')
manifest[0].update(width=im.width,height=im.height,bounds=bounds,last='pets/pet-1-last.png',durationMs=sum(durations),sourceGitBlob=blob(source.read_bytes()))
night=ROOT/'app/src/main/res/drawable-nodpi/home_buddy_night.png'
shutil.copy2(night,TARGET/'night.png');ni=Image.open(night)
manifest.append(dict(id='night',name='晚间休息',animation='pets/night.png',still='pets/night.png',last='pets/night.png',width=ni.width,height=ni.height,bounds=ni.getbbox(),frames=1,sourceGitBlob=blob(night.read_bytes())))
(TARGET/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Default day/night now use exact Home asset bytes')
