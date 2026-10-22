import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('./core.js',import.meta.url),'utf8');
function run(snapshot, type='before_inference') {
 const ctx=vm.createContext({}); vm.runInContext(source,ctx,{timeout:100});
 return ctx.atlasRuntime.reduce({bridge:1,request_id:'request-1',type,snapshot});
}
test('budget boundaries yield without ending durable task',()=>{
 for(const [step,elapsed_ms,action] of [[0,0,'infer'],[7,179999,'infer'],[8,0,'yield'],[0,180000,'yield']]) {
  const result=run({step,elapsed_ms});assert.equal(result.action,action);assert.equal(result.request_id,'request-1');
 }
});
test('reject malformed input rather than inventing continuation',()=>{
 for(const snapshot of [{step:-1,elapsed_ms:0},{step:1.1,elapsed_ms:0},{step:0,elapsed_ms:-1}]) assert.throws(()=>run(snapshot));
 assert.throws(()=>run({step:0,elapsed_ms:0},'execute_tool'));
});
test('untrusted text remains data and replay is deterministic',()=>{
 const s={step:2,elapsed_ms:10,user_text:'"; throw Error("injected"); //'};
 assert.equal(JSON.stringify(run(s)),JSON.stringify(run(s)));
});
