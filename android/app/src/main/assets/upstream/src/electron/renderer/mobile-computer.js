(() => {
  document.addEventListener('DOMContentLoaded', () => {
    if (!window.Android?.getConnectionConfig) return;
    const $ = id => document.getElementById(id);
    const defaults = {
      wifi: { host: '', port: 47823 },
      bluetooth: {},
      usb: { host: '127.0.0.1', port: 47823 },
      remote: { host: '', port: 443 }
    };
    const help = {
      wifi: '手机与电脑连接同一 Wi-Fi，填写电脑连接组件显示的 IPv4 地址。',
      bluetooth: '先在系统蓝牙设置配对电脑，再选择设备。电脑连接组件需显示 Bluetooth ready。',
      usb: '用数据线连接电脑并允许 USB 调试，然后在电脑运行 Setup-USB.cmd。',
      remote: '填写自己的 HTTPS 穿透地址。电脑连接组件与穿透客户端需保持运行。'
    };
    const icons = { wifi: 'client', bluetooth: 'client', usb: 'local', remote: 'client' };
    const modeControl = window.TokenMonitorSelectControl?.enhance($('computerMode'), {
      getOptionMeta: option => ({ description: help[option.value], icon: syncModeIcons[icons[option.value]] })
    });
    const deviceControl = window.TokenMonitorSelectControl?.enhance($('computerDevice'));
    let saved = JSON.parse(Android.getConnectionConfig());
    let mode = Object.hasOwn(defaults, saved.mode) ? saved.mode : 'wifi';
    let editing = false;
    let drafts;
    function resetDrafts() {
      drafts = Object.fromEntries(Object.entries(defaults).map(([key, values]) => [key, { ...values }]));
      if (drafts[saved.mode]) Object.assign(drafts[saved.mode], saved);
      $('computerKey').value = saved.key || '';
    }
    function devices() {
      const data = JSON.parse(Android.getPairedComputers());
      const select = $('computerDevice');
      const previous = select.value || saved.mac;
      select.replaceChildren();
      for (const device of data.devices || []) {
        select.add(new Option((device.name || '电脑') + ' · ' + device.address, device.address));
      }
      if ([...select.options].some(option => option.value === previous)) select.value = previous;
      if (!select.options.length) {
        select.add(new Option(data.error || (data.needsPermission ? '需要附近设备权限' : '暂无已配对电脑'), ''));
      }
      $('computerBluetoothPermission').hidden = !data.needsPermission;
      deviceControl?.sync();
    }
    function select(next) {
      mode = next;
      const bluetooth = mode === 'bluetooth';
      $('computerMode').value = mode;
      modeControl?.sync();
      $('computerHelp').textContent = help[mode];
      $('computerNetworkFields').hidden = bluetooth;
      $('computerBluetoothFields').hidden = !bluetooth;
      $('computerHost').value = drafts[mode].host || '';
      $('computerPort').value = drafts[mode].port || '';
      $('computerHost').readOnly = mode === 'usb';
      if (bluetooth) devices();
    }
    function error(message = '') {
      $('computerError').textContent = message;
      $('computerError').hidden = !message;
    }
    function refresh() {
      const status = JSON.parse(Android.getHubStatus());
      const active = status.source === 'computer' && status.active;
      const visibleEditor = !active || editing;
      const state = !active ? 'notConfigured' : status.connected ? 'connected'
        : !status.reason || status.reason.startsWith('正在连接') ? 'connecting' : 'disconnected';
      $('computerMode').disabled = !visibleEditor;
      $('computerPanelConnection').dataset.state = state;
      $('computerEdit').hidden = visibleEditor;
      $('computerConnectionIdentity').hidden = visibleEditor;
      $('computerStatus').textContent = {
        connected: '已连接', connecting: '正在连接…', disconnected: '连接中断', notConfigured: '未连接'
      }[state];
      $('computerPanelDetail').textContent = active && state === 'disconnected' ? status.reason : '';
      $('computerPanelDetail').hidden = !$('computerPanelDetail').textContent;
      $('computerConnectionEndpoint').textContent = active
        ? saved.mode === 'bluetooth' ? saved.mac || '' : [saved.host, saved.port].filter(Boolean).join(':')
        : '';
      $('computerEditor').hidden = !visibleEditor;
      $('computerActions').hidden = !visibleEditor;
      $('computerCancel').hidden = !active;
      $('computerDisconnect').hidden = !active;
      $('computerSettingsSummary').textContent = active ? $('computerStatus').textContent : '未连接';
    }
    $('computerMode').addEventListener('change', event => {
      if (mode !== 'bluetooth') Object.assign(drafts[mode], { host: $('computerHost').value, port: $('computerPort').value });
      select(event.currentTarget.value);
      error();
    });
    $('computerEdit').addEventListener('click', () => { editing = true; refresh(); });
    $('computerCancel').addEventListener('click', () => {
      editing = false;
      resetDrafts();
      select(saved.mode);
      error();
      refresh();
    });
    $('computerConnect').addEventListener('click', () => {
      const message = Android.saveConnection(JSON.stringify({
        mode, host: $('computerHost').value, port: Number($('computerPort').value),
        key: $('computerKey').value, mac: $('computerDevice').value
      }));
      error(message);
      if (message) return;
      saved = JSON.parse(Android.getConnectionConfig());
      editing = false;
      refresh();
    });
    $('computerDisconnect').addEventListener('click', () => { Android.disconnectComputer(); error(); setTimeout(refresh, 250); });
    $('computerKeyPaste').addEventListener('click', async () => {
      try {
        const value = await navigator.clipboard.readText();
        if (value) $('computerKey').value = value.trim();
      } catch (_) {}
    });
    $('computerBluetoothRefresh').addEventListener('click', devices);
    $('computerBluetoothPermission').addEventListener('click', () => Android.requestBluetoothPermission());
    $('computerBluetoothSettings').addEventListener('click', () => Android.openBluetoothSettings());
    window.__mobileBluetoothPermission = devices;
    resetDrafts();
    select(mode);
    refresh();
    setInterval(() => { if (!document.hidden) refresh(); }, 1000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden && mode === 'bluetooth') devices(); });
  }, { once: true });
})();
