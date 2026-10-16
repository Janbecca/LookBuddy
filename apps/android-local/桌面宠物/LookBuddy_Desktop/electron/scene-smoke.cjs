const fs = require('node:fs');
const path = require('node:path');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function verifyScenes(win, pets, directory) {
  fs.mkdirSync(directory, {recursive:true});
  const results=[];
  const initial = await win.webContents.executeJavaScript(`(async()=>({geometry:await window.lookbuddy.readGeometry(),bubble:!!document.querySelector('.bubble')}))()`);
  if(initial.bubble)throw new Error('No Home prompt: bubble must be hidden');
  if(initial.geometry.width > 240 || initial.geometry.height > 240)throw new Error('Window was not halved');
  for (const pet of pets.filter(pet=>['drink','stretch','posture','fatigue','happy','concern','morning'].includes(pet.id))) {
    const event={scene_action_id:pet.id,scene_event_id:`smoke:${pet.id}`,scene_message:`首页提示-${pet.id}`,message:''};
    win.webContents.send('sync:updated', event);
    await sleep(180);
    const result=await win.webContents.executeJavaScript(`(async()=>{
      const image=document.querySelector('.pet-image'); await image.decode();
      const rect=document.querySelector('.pet-crop').getBoundingClientRect();
      if (!image.naturalWidth || rect.left < -1 || rect.right > innerWidth+1 || rect.bottom > innerHeight+1) throw new Error('Scene exceeds window');
      return {name:image.alt,src:image.src,width:rect.width,height:rect.height,prompt:document.querySelector('.bubble-text')?.textContent,geometry:await window.lookbuddy.readGeometry()};
    })()`);
    if(result.name!==pet.name)throw new Error('Wrong scene: '+pet.id);
    if(result.prompt!==event.scene_message)throw new Error('Prompt does not match Home');
    if(result.geometry.width!==initial.geometry.width || result.geometry.height!==initial.geometry.height)throw new Error('Scene changed window size');
    fs.writeFileSync(path.join(directory,`scene-${pet.id}.png`),(await win.webContents.capturePage()).toPNG());
    win.webContents.send('sync:updated',{...event,scene_message:'',message:'old prompt must not reappear'});
    await sleep(80);
    if(await win.webContents.executeJavaScript(`!!document.querySelector('.bubble')`))throw new Error('Empty current prompt did not hide bubble');
    win.webContents.send('sync:updated',event);
    await sleep(80);
    if(pet.id==='drink') {
      win.webContents.send('sync:updated',{...event,scene_completed:true});
      await sleep(100);
      if(!await win.webContents.executeJavaScript(`document.querySelector('.pet-image').src.endsWith('-last.png')`))throw new Error('Home completion was not mirrored');
      result.completionMirrored=true;
    }
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
  win.webContents.send('sync:updated',{scene_action_id:'night',scene_event_id:'smoke:evening',message:'晚上好'});
  await sleep(120);
  const evening=await win.webContents.executeJavaScript(`document.querySelector('.pet-image').alt`);
  if(evening!=='晚间休息')throw new Error('Wrong evening default');
  win.webContents.send('sync:updated',{scene_action_id:'night',scene_event_id:'smoke:evening',message:'',scene_message:''});
  await sleep(100);
  if(await win.webContents.executeJavaScript(`!!document.querySelector('.bubble')`))throw new Error('Night with no prompt displayed a bubble');
  const report={scenes:results,evening,initial,uniformWindow:true,emptyPromptHidden:true,passed:true};
  fs.writeFileSync(path.join(directory,'scene-smoke.json'),JSON.stringify(report,null,2));
  return report;
}
module.exports={verifyScenes};
