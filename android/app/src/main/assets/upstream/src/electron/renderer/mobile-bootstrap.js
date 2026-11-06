/* Android adapter for the upstream Hub. Preferences remain on this phone. */
(() => {
  'use strict';
  const native=window.Android, cache=window.MobileOfflineCache;
  const pending=new Map(), listeners=new Map();
  let sequence=0, subscriptionRevision='', settings={...window.MobileDefaults};
  let connectionState={connected:false,mode:'local',active:false,reason:'disconnected'};
  const session=Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);
  let sourceGeneration=0;
  const computerActive=()=>connectionState.source==='computer'&&connectionState.active;
  function computerRequest(channel,args=[]){
    return new Promise((resolve,reject)=>{
      const id=session+'-pc-'+(++sequence);
      const timeout=setTimeout(()=>{pending.delete(id);reject(new Error('电脑响应超时'));},45000);
      pending.set(id,{resolve,reject,timeout,generation:sourceGeneration});native.requestComputer(id,channel,JSON.stringify(args));
    });
  }
  try { Object.assign(settings,JSON.parse(native.getSettings())); } catch (_) {}
  document.documentElement.classList.add('token-monitor-mobile');
  const clone=value=>JSON.parse(JSON.stringify(value));
  const projectedSettings=()=>clone({...settings,deviceIconScope:settings.hubMode==='client'&&settings.hubUrl?settings.hubUrl:'local',subscriptionsHub:settings.hubMode==='client'?settings.hubUrl:'',subscriptionsShared:settings.hubMode==='client'});
  function emptyStats(){
    const period=()=>({totalTokens:0,costUsd:0,clients:{},models:{},clientModels:{},sessions:{},projects:{}});
    return {periods:{today:period(),month:period(),allTime:period()},devices:[],limits:{providers:[]},historyPreview:{daily:[],monthly:[],summary:{}},updatedAt:null};
  }
  let latest=cache?.reply('stats:get')?.result||emptyStats();
  function emit(channel,...args){for(const fn of [...(listeners.get(channel)||[])])try{fn(null,...args);}catch(_){console.error('Mobile event failed: '+channel);}}
  function publishStats(stats){
    if(!stats?.periods)return;
    latest=stats;cache.remember('stats:get',stats);
    emit('stats:push',{event:'stats',data:{stats,mode:'sync',reason:'read'}});
    if(stats.subscriptionsUpdatedAt && stats.subscriptionsUpdatedAt!==subscriptionRevision)void refreshSubscriptions().catch(()=>{});
  }
  function request(method,path,body){
    return new Promise((resolve,reject)=>{
      const id=session+'-'+(++sequence);
      const timeout=setTimeout(()=>{pending.delete(id);reject(new Error('Hub 请求超时'));},30000);
      pending.set(id,{resolve,reject,timeout,generation:sourceGeneration});
      native.requestHub(id,method,path,JSON.stringify(body||{}));
    });
  }
  window.__mobileHubReply=(id,value)=>{
    const job=pending.get(id);if(!job)return;pending.delete(id);clearTimeout(job.timeout);
    if(job.generation!==sourceGeneration){job.reject(new Error('Hub 已更改，请重试'));return;}
    if(value?.ok)job.resolve(value.result);else{const error=new Error(value?.error||'Hub 请求失败');error.status=value?.status;job.reject(error);}
  };
  window.__mobileHubEvent=event=>{if(settings.hubMode==='client')publishStats(event?.stats);};
  window.__mobileRpcReply=(id,value)=>window.__mobileHubReply(id,value);
  window.__mobileSourceChanged=()=>{
    sourceGeneration++;Object.assign(settings,JSON.parse(native.getSettings()));
    emit('settings:push',projectedSettings());window.__mobileHubStatus(JSON.parse(native.getHubStatus()));
    void pollComputer();
  };
  window.__mobileHubStatus=value=>{
    connectionState=value||connectionState;window.__mobileConnectionState=connectionState;
    document.documentElement.dataset.mobileConnected=String(connectionState.connected===true);
    const dot=document.getElementById('liveDot');
    if(dot){dot.classList.toggle('live',connectionState.connected===true);dot.title=connectionState.connected?(computerActive()?'已连接电脑':'已连接 Hub'):cache.ready()?'离线 · 显示本地缓存':'未连接';dot.setAttribute('aria-label',dot.title);if(!connectionState.connected)for(const a of dot.getAnimations())a.cancel();}
    emit('stats:push',{event:'status',data:connectionState});
    if(!connectionState.connected)cache.flush();
  };
  function persist(next){
    const result=JSON.parse(native.saveSettings(JSON.stringify(next)));
    if(!result.ok)throw new Error(result.error||'设置保存失败');
    settings={...window.MobileDefaults,...result.result};cache.remember('settings:get',settings);
    return projectedSettings();
  }
  async function updateSettings(patch){
    const next={...settings,...patch};
    // Invalidate old replies and cached data only when the Hub identity changes.
    const changed=(next.hubUrl||'').replace(/\/+$/,'')!==(settings.hubUrl||'').replace(/\/+$/,'')||next.secret!==settings.secret||(computerActive()&&next.hubMode==='client');
    const value=persist(next);
    if(changed){sourceGeneration++;cache.reset();cache.remember('settings:get',settings);latest=emptyStats();emit('stats:push',{event:'stats',data:{stats:latest,reason:'read'}});subscriptionRevision='';}
    emit('settings:push',value);
    window.__mobileHubStatus(JSON.parse(native.getHubStatus()));
    return value;
  }
  async function refreshSubscriptions(){
    if(settings.hubMode!=='client')return;
    const value=await request('GET','/api/subscriptions');
    if(Array.isArray(value.subscriptions)){
      subscriptionRevision=value.updatedAt||'';
      const next=persist({...settings,subscriptions:value.subscriptions,subscriptionsUpdatedAt:subscriptionRevision,subscriptionsCacheHub:settings.hubUrl});
      emit('settings:push',next);
    }
  }
  async function readData(channel,path){
    if(computerActive()){
      try{const value=await computerRequest(channel);cache.remember(channel,value);if(channel==='stats:get')latest=value;return value;}
      catch(error){const saved=cache.reply(channel);if(saved?.ok)return saved.result;throw error;}
    }
    if(settings.hubMode!=='client'||!settings.hubUrl||(!connectionState.connected&&cache.ready())){
      const saved=cache.reply(channel);if(saved?.ok)return saved.result;
      return channel==='stats:get'?emptyStats():{daily:[],monthly:[],summary:{}};
    }
    try{const value=await request('GET',path);cache.remember(channel,value);if(channel==='stats:get')latest=value;return value;}
    catch(error){const saved=cache.reply(channel);if(saved?.ok)return saved.result;throw error;}
  }
  async function invoke(channel,...args){
    switch(channel){
      case 'settings:get':return projectedSettings();
      case 'settings:update':return updateSettings(args[0]||{});
      case 'stats:get':return readData(channel,'/api/stats');
      case 'dashboard:getHistory':return readData(channel,'/api/history');
      case 'stats:allTimeSessions':return clone(latest?.periods?.allTime?.sessions||{});
      case 'session:getDetail':return computerActive()?computerRequest(channel,args):{error:'mobile-transcript-unavailable'};
      case 'stream:status':return clone(connectionState);
      case 'hub:getInfo':return {running:false,mode:settings.hubMode,url:settings.hubUrl,addresses:[]};
      case 'hub:getBuildStatus':{
        if(settings.hubMode!=='client'||!settings.hubUrl)return {status:'notConfigured'};
        try{const health=await request('GET','/api/health');return {status:health.role==='hub'?'current':'unavailable',runtime:health.runtime,hubUrl:settings.hubUrl};}catch(_){return {status:'unavailable'};}
      }
      case 'subscriptions:save':{
        if(settings.hubMode!=='client')return updateSettings({subscriptions:args[0]||[]});
        if(!connectionState.connected)throw new Error('当前离线，无法保存共享订阅');
        if(args[1]?.hub!==settings.hubUrl)throw new Error('Hub 已更改，请重新打开订阅编辑');
        try{await request('PUT','/api/subscriptions',{subscriptions:args[0]||[],baseUpdatedAt:args[1]?.updatedAt??subscriptionRevision});}
        catch(error){if(error.status===409){error.code='stale_write';await refreshSubscriptions();}throw error;}
        await refreshSubscriptions();return projectedSettings();
      }
      case 'devices:delete':if(!connectionState.connected)throw new Error('当前离线，无法删除 Hub 设备');await request('DELETE','/api/devices/'+encodeURIComponent(args[0]));return {ok:true};
      case 'app:getInfo':return {version:'0.0.3',platform:'android',systemDarkUi:true,loginItemSupported:false};
      case 'appUpdate:getState':return {status:'idle',supported:false};
      case 'syncContent:status':return {supported:false,enabled:{}};
      case 'codexResetForecast:get':return computerActive()?computerRequest(channel).catch(()=>({status:'disabled'})):{status:'disabled'};
      case 'serviceStatus:get':return computerActive()?computerRequest(channel).catch(()=>({providers:[],status:'unavailable'})):{providers:[],status:'unavailable'};
      case 'tokscale:getStatus':return {available:false};
      case 'appearance:getNativeMaterial':case 'plugins:contentSelections':return {};
      case 'plugins:list':case 'plugins:skins':case 'plugins:views':return [];
      case 'appearance:getBackgroundImage':return null;
      case 'appearance:preview':return clone({...settings,...args[0]});
      case 'window:viewState':return updateSettings({lastViewState:args[0]});
      case 'dashboard:open':cache.flush();native.openDashboard();return;
      case 'dashboard:close':cache.flush();native.closeDashboard();return;
      case 'clipboard:write':native.copyText(String(args[0]??''));return;
      case 'app:openExternal':native.openExternal(String(args[0]??''));return;
      case 'window:contentReady':case 'tray:setIcons':case 'plugins:demand':case 'plugins:lifecycleHost':case 'plugins:uiLoading':
      case 'dashboard:ready':case 'dashboard:minimize':case 'window:minimize':case 'window:close':return null;
      default:throw new Error('此功能需要在电脑 Token Monitor 中操作');
    }
  }
  const ipcRenderer={invoke,send(channel,...args){void invoke(channel,...args).catch(()=>{});},
    on(channel,fn){if(!listeners.has(channel))listeners.set(channel,new Set());listeners.get(channel).add(fn);return this;},
    removeListener(channel,fn){listeners.get(channel)?.delete(fn);return this;}};
  window.__mobileElectron={ipcRenderer,contextBridge:{exposeInMainWorld(name,value){window[name]=value;}}};
  cache.remember('settings:get',settings);
  // Older Android WebViews lack the desktop renderer's moveBefore API.
  // insertBefore preserves form values; restore input focus after reparenting.
  // This fallback does not emulate atomic iframe/animation state preservation.
  if (typeof Element.prototype.moveBefore !== 'function') {
    Object.defineProperty(Element.prototype, 'moveBefore', {
      configurable: true, writable: true,
      value(node, before) {
        const focused = node.contains(document.activeElement) ? document.activeElement : null;
        this.insertBefore(node, before);
        if (focused && document.activeElement !== focused) focused.focus({preventScroll:true});
      }
    });
  }

  function simplifyMobileSettings() {
    // Keep original nodes/listeners for renderer compatibility; remove only
    // desktop-specific entries from the mobile navigation and focus order.
    const controls = [
      ['discordRpcInput', '.settings-subgroup'],
      ['windowGlassEffectLabel', '.settings-item'],
      ['blurInput', '.settings-item'],
      ['zoomInput', '.settings-item'],
      ['swapSettingsRefreshInput', '.settings-item']
    ];
    for (const [id, parent] of controls) {
      const row = document.getElementById(id)?.closest(parent);
      if (row) row.classList.add('mobile-desktop-only');
    }
    const panel = document.getElementById('settingsPanel');
    if (panel && !CSS.supports('selector(:has(*))')) {
      const syncCheckedOptions = () => {
        for (const label of panel.querySelectorAll('.inline-option')) {
          label.classList.toggle('is-checked', Boolean(label.querySelector('input:checked')));
        }
      };
      panel.addEventListener('change', syncCheckedOptions);
      new MutationObserver(syncCheckedOptions).observe(panel, {attributes: true, attributeFilter: ['class']});
      syncCheckedOptions();
    }
  }


  document.addEventListener('DOMContentLoaded',()=>{
    simplifyMobileSettings();
    // Hosting, iCloud, desktop collectors and credential editing belong on the PC.
    document.querySelectorAll('#hubModeOptions option[value="host"],#hubModeOptions option[value="icloud"]').forEach(node=>node.remove());
    document.getElementById('syncContentGroup')?.classList.add('mobile-desktop-only');
    document.getElementById('syncUploadIntervalRow')?.classList.add('mobile-desktop-only');
    window.__mobileHubStatus(JSON.parse(native.getHubStatus()));
    const close=document.getElementById('closeBtn');if(close){close.textContent='‹';close.setAttribute('aria-label','返回监控');}
  },{once:true});
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)window.__mobileHubStatus(JSON.parse(native.getHubStatus()));});
  window.addEventListener('pagehide',()=>{cache.flush();for(const entry of pending.values())clearTimeout(entry.timeout);pending.clear();});
  window.__mobileHubStatus(JSON.parse(native.getHubStatus()));
  let computerPolling=false;
  async function pollComputer(){
    if(computerPolling||document.hidden||!computerActive())return;
    computerPolling=true;const generation=sourceGeneration;
    try{const stats=await computerRequest('stats:get');if(generation===sourceGeneration&&computerActive())publishStats(stats);}catch(_){}
    finally{computerPolling=false;}
  }
  if(typeof setInterval==='function'){setInterval(pollComputer,3000);void pollComputer();}
})();
