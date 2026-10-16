const { test } = require('node:test');
const assert = require('node:assert/strict');
const { sceneAction } = require('../electron/scenes.cjs');
const { createSyncServer } = require('../electron/sync-server.cjs');

for (const [event, expected] of [
  [{scene:'drink'}, 'drink'], [{scene:'sedentary'}, 'stretch'], [{scene:'stand'}, 'stretch'],
  [{scene:'posture'}, 'posture'], [{scene:'fatigue'}, 'fatigue'],
  ...['good','joyful'].map(label => [{scene:'emotion',label}, 'happy']),
  ...['possibly_stressed','possibly_low','possibly_angry'].map(label => [{scene:'emotion',label}, 'concern']),
  [{scene:'morning_seated'},'morning'], [{scene:'morning_unknown'},'morning'],
  [{scene:'evening'},'pet-3'], [{scene:'diary_ready'},'pet-1'],
  [{scene:'emotion',label:'focused'},'pet-1'], [{scene:'unknown'},'pet-1'],
  [{scene:'constructor'},'pet-1'], [{scene:42},'pet-1'],
]) test(JSON.stringify(event), () => assert.equal(sceneAction(event),expected));

test('authenticated HTTP events change animation; heartbeat preserves transient event; expiry restores evening', async () => {
  const server = createSyncServer({port: 0, host:'127.0.0.1', token:'test-only', reminderDurationMs:100});
  try {
    const address = await server.start();
    const post = async (endpoint, body, authorized=true) => {
      const response = await fetch(`http://127.0.0.1:${address.port}/v1/${endpoint}`, {method:'POST',headers:{'Content-Type':'application/json',...(authorized?{'X-LookBuddy-Token':'test-only'}:{})},body:JSON.stringify(body)});
      return {status:response.status,...await response.json()};
    };
    assert.equal((await post('reminders',{scene:'drink'},false)).status,401);
    let result=await post('sync',{scene:'evening',message:'晚上好'});
    assert.equal(result.desktop.scene_action_id,'pet-3');
    result=await post('reminders',{id:'reminder-1',category:'emotion',label:'possibly_low',message:'我听着'});
    assert.equal(result.desktop.scene_action_id,'concern');
    const event=result.desktop.scene_event_id;
    result=await post('sync',{scene:'evening',message:'晚上好'});
    assert.equal(result.desktop.scene_action_id,'concern');
    assert.equal(result.desktop.scene_event_id,event);
    await new Promise(resolve=>setTimeout(resolve,130));
    assert.equal(server.snapshot().scene_action_id,'pet-3');
    result=await post('reminders',{scene:'diary_ready',message:'日记准备好了'});
    assert.equal(result.desktop.scene_action_id,'pet-3');
    result=await post('sync',{scene:'morning_unknown'});
    await new Promise(resolve=>setTimeout(resolve,130));
    result=await post('sync',{scene:'drink',id:'same'});
    const first=result.desktop.scene_event_id;
    result=await post('sync',{scene:'drink',id:'same'});
    assert.equal(result.desktop.scene_event_id,first);
    result=await post('sync',{scene:'drink',id:'next'});
    assert.notEqual(result.desktop.scene_event_id,first);
    result=await post('sync',{scene:'diary_ready',id:'diary',message:'日记可查看'});
    const diary=result.desktop.scene_event_id;
    result=await post('sync',{scene:'idle',message:'继续陪你'});
    assert.notEqual(result.desktop.scene_event_id,diary);
    assert.equal(result.desktop.scene_message,'继续陪你');
  } finally { await server.close(); }
});
