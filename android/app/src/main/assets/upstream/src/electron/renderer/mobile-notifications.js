/* Private native settings: never uploaded to Hub or saved in statistics cache. */
(() => {
  document.addEventListener('DOMContentLoaded', () => {
    if (!window.Android?.getNotificationState) return;
    const inputs = [...document.querySelectorAll('[data-codex-notification]')];
    const summary = document.getElementById('notificationsSettingsSummary');
    function refresh() {
      const state = JSON.parse(Android.getNotificationState());
      for (const input of inputs) input.checked = Boolean(state.kinds[input.dataset.codexNotification]);
      summary.textContent = state.enabled ? '已开启' : '已关闭';
    }
    for (const input of inputs) {
      input.addEventListener('change', () => {
        Android.setCodexNotificationKind(input.dataset.codexNotification, input.checked);
        refresh();
      });
    }
    window.__mobileNotificationStateChanged = refresh;
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
    refresh();
  }, { once: true });
})();
