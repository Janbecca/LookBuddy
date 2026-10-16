const test = require('node:test');
const assert = require('node:assert/strict');
const { migratePreferences } = require('../electron/preferences.cjs');
test('current 467 window becomes 233 once, preserves corner and preferences',()=>{
  const first=migratePreferences({width:467,x:1205,y:444,bubble:true,index:0});
  assert.equal(first.width,233);assert.equal(first.x+first.width,1205+467);assert.equal(first.y+first.width,444+467);
  assert.deepEqual(migratePreferences(first),first);
});
test('old preset migrates proportionally; automatic cycling stops for Home synchronization',()=>{
  assert.equal(migratePreferences({size:'large',auto:true}).width,290);
  assert.equal(migratePreferences({width:233,sizeVersion:1,auto:true}).auto,false);
});
