const fs = require('node:fs');
const path = require('node:path');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function verifyScenes(win, pets, directory) {
  fs.mkdirSync(directory, {recursive:true});
  const results=[];
  for (const pet of pets.filter(pet=>pet.bounds)) {
    const event={scene_action_id:pet.id,scene_event_id:`smoke:${pet.id}`,message:pet.name};
    win.webContents.send('sync:updated', event);
    await sleep(180);
    const result=await win.webContents.executeJavaScript(`(async()=>{
      const image=document.querySelector('.pet-image'); await image.decode();
      const rect=document.querySelector('.pet-crop').getBoundingClientRect();
      if (!image.naturalWidth || rect.left < -1 || rect.right > innerWidth+1 || rect.bottom > innerHeight+1) throw new Error('Scene exceeds window');
      return {name:image.alt,src:image.src,width:rect.width,height:rect.height};
    })()`);
    if(result.name!==pet.name)throw new Error('Wrong scene: '+pet.id);
    fs.writeFileSync(path.join(directory,`scene-${pet.id}.png`),(await win.webContents.capturePage()).toPNG());
    if(pet.id==='fatigue') {
      await sleep(pet.durationMs+120);
      const held=await win.webContents.executeJavaScript(`document.querySelector('.pet-image').src.endsWith('-last.png')`);
      if(!held)throw new Error('Last frame was not held');
      win.webContents.send('sync:updated',event);
      await sleep(120);
      if(!await win.webContents.executeJavaScript(`document.querySelector('.pet-image').src.endsWith('-last.png')`))throw new Error('Heartbeat restarted animation');
      result.lastFrameHeld=true;result.duplicateDidNotReplay=true;
      win.webContents.send('sync:updated',{...event,scene_event_id:'smoke:fatigue:next'});
      await sleep(120);
      if(!await win.webContents.executeJavaScript(`document.querySelector('.pet-image').src.includes('.webp?')`))throw new Error('New event did not replay');
      result.newEventReplayed=true;
    }
    results.push(result);
  }
  win.webContents.send('sync:updated',{scene_action_id:'pet-3',scene_event_id:'smoke:evening',message:'晚上好'});
  await sleep(120);
  const evening=await win.webContents.executeJavaScript(`document.querySelector('.pet-image').alt`);
  if(evening!=='看电视吃薯片')throw new Error('Wrong evening default');
  const report={scenes:results,evening,passed:true};
  fs.writeFileSync(path.join(directory,'scene-smoke.json'),JSON.stringify(report,null,2));
  return report;
}
module.exports={verifyScenes};
