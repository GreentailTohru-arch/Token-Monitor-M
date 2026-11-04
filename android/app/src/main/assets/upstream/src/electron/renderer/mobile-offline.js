/* Local display cache only. No cloud sync, account login, or queued writes. */
(() => {
  'use strict';
  const native = window.Android;
  const cachedChannels = new Set(['settings:get', 'stats:get', 'dashboard:getHistory', 'serviceStatus:get', 'codexResetForecast:get']);
  const displayKeys = new Set(('locale currency currencyRates themeColors themeMode glassOpacity glassBlur uiTextScale '
    + 'showLiveDot showToolIcons titleIconOnly showCompactTotalTokens showLiveTokenRate compactTokenUnits '
    + 'showHomeLimitBars showHomeLimitProviderNames showCodexAdditionalLimits showLimitUsed reduceMotion '
    + 'tokenNumberAnimation historyEnabled showModelCosts showClientCosts modelAliasGrouping '
    + 'periodMonthMode heatmapMetric modelRankingMetric tokenRateMode homeLimitDisplayMode homeActiveDaysWindow '
    + 'homeLimitAccountCount homeModuleOrder hiddenHomeModules hiddenViews breakdownOrder initialView initialPeriod '
    + 'modelAliases hiddenModels hiddenClients showTokenComponents showOutputTokens showCacheTokens '
    + 'projectsEnabled sessionTitlesEnabled trendMetric trendRange refreshInterval').split(/\s+/));
  let data = {version:2, updatedAt:0, rpc:{}};
  let timer;
  let dirty = false;
  try {
    const saved = JSON.parse(native?.getOfflineCache?.() || 'null');
    if (saved?.version === 2 && saved.rpc?.['stats:get']?.periods && saved.rpc?.['settings:get']) data = saved;
  } catch (_) {}
  const copy = value => JSON.parse(JSON.stringify(value));
  function safeSettings(settings) {
    const result = {hubMode:'local', historyEnabled:true, subscriptions:[], locale:'zh-CN', currency:'USD'};
    for (const [key, value] of Object.entries(settings || {})) if (displayKeys.has(key)) result[key] = copy(value);
    return result;
  }
  function ready() { return Boolean(data.rpc['stats:get']?.periods && data.rpc['settings:get']); }
  function flush() {
    clearTimeout(timer); timer = null;
    if (ready() && dirty) {
      try { if (native?.saveOfflineCache?.(JSON.stringify(data))) dirty = false; } catch (_) {}
    }
  }
  function remember(channel, value) {
    if (!cachedChannels.has(channel) || !value || typeof value !== 'object') return;
    if (channel === 'stats:get' && !value.periods) return;
    data.rpc[channel] = channel === 'settings:get' ? safeSettings(value) : copy(value);
    dirty = true;
    if (channel === 'stats:get') data.updatedAt = Date.now();
    if (!timer) timer = setTimeout(flush, 1500);
  }
  function event(channel, args) {
    if (channel === 'settings:push') remember('settings:get', args[0]);
    if (channel === 'stats:push' && args[0]?.data?.stats) remember('stats:get', args[0].data.stats);
  }
  function reply(channel, args = []) {
    if (!ready()) return null;
    const ok = result => ({ok:true, result:copy(result)});
    if (Object.hasOwn(data.rpc, channel)) return ok(data.rpc[channel]);
    return {ok:false,error:'尚无这项数据的本地缓存'};
  }
  window.MobileOfflineCache = {ready, remember, event, reply, flush, reset(){clearTimeout(timer); timer=null; dirty=false; data={version:2,updatedAt:0,rpc:{}}; native?.clearOfflineCache?.();}, updatedAt:() => data.updatedAt};
  document.addEventListener('visibilitychange', () => { if (document.hidden) flush(); });
  window.addEventListener('pagehide', flush);
})();
