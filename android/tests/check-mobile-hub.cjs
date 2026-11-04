const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('node:assert/strict');
const dir=path.join(__dirname,'../app/src/main/assets/upstream/src/electron/renderer');
let disk='',preferences={hubMode:'local',locale:'zh-CN'};
function host(){
 const requests=[];const timers=new Map();let seq=0;
 const c={Element:class{},console,setTimeout(fn){const id=++seq;timers.set(id,fn);return id;},clearTimeout(id){timers.delete(id);},
 document:{documentElement:{classList:{add(){}},dataset:{}},getElementById(){return null;},addEventListener(){},hidden:false},addEventListener(){},
 Android:{getSettings:()=>JSON.stringify(preferences),saveSettings:text=>{preferences=JSON.parse(text);return JSON.stringify({ok:true,result:preferences});},
 getHubStatus:()=>JSON.stringify({connected:false,active:preferences.hubMode==='client',mode:preferences.hubMode==='client'?'sync':'local'}),
 requestHub:(id,method,url,body)=>requests.push({id,method,url,body:JSON.parse(body)}),
 getOfflineCache:()=>disk,saveOfflineCache:text=>{disk=text;return true;},clearOfflineCache:()=>{disk='';}}};
 c.window=c;vm.createContext(c);
 for(const name of ['mobile-defaults.js','mobile-offline.js','mobile-bootstrap.js','mobile-preload.js'])vm.runInContext(fs.readFileSync(path.join(dir,name),'utf8'),c);
 return {c,requests};
}
(async()=>{
 let {c}=host();
 c.MobileOfflineCache.remember('stats:get',{periods:{today:{totalTokens:12345}}});
 c.MobileOfflineCache.remember('settings:get',{themeColors:{bg:'#293441'},secret:'DO-NOT-CACHE',copilotApiToken:'DO-NOT-CACHE'});
 c.MobileOfflineCache.flush();assert(!disk.includes('DO-NOT-CACHE'));
 ({c}=host());assert.equal((await c.tokenMonitor.getStats()).periods.today.totalTokens,12345);
 await c.tokenMonitor.updateSettings({currency:'CNY'});assert.equal((await c.tokenMonitor.getSettings()).currency,'CNY');
 assert.equal(c.document.documentElement.dataset.mobileConnected,'false');
 await c.tokenMonitor.updateSettings({hubMode:'client',hubUrl:'http://hub.test:17321',secret:'private'});
 assert.equal(c.MobileOfflineCache.ready(),false,'Another Hub must not inherit prior data');
 c.__mobileHubEvent({stats:{periods:{today:{totalTokens:67890}}}});
 c.MobileOfflineCache.flush();
 c.__mobileHubStatus({connected:false,mode:'sync',active:true});
 assert.equal((await c.tokenMonitor.getStats()).periods.today.totalTokens,67890);
 await assert.rejects(c.tokenMonitor.saveSubscriptions([],''),/当前离线/);
 ({c}=host());assert.equal((await c.tokenMonitor.getStats()).periods.today.totalTokens,67890);
 assert(!disk.includes('private'));
 console.log('PASS: cache across restart, private settings excluded, local preferences while offline, shared writes rejected, Hub switch invalidates cache, SSE snapshot retained');
})().catch(e=>{console.error(e);process.exitCode=1;});
