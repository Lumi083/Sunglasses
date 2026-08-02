const elements = {
  enabled: document.querySelector('#enabled'),
  opacity: document.querySelector('#opacity'),
  opacityValue: document.querySelector('#opacity-value'),
  color: document.querySelector('#color'),
  colorValue: document.querySelector('#color-value'),
  shortcut: document.querySelector('#shortcut'),
  shortcutHint: document.querySelector('#shortcut-hint'),
  shortcutError: document.querySelector('#shortcut-error'),
  coverTaskbar: document.querySelector('#cover-taskbar'),
  forceTopEnabled: document.querySelector('#force-top-enabled'),
  forceTopInterval: document.querySelector('#force-top-interval'),
  totalUsage: document.querySelector('#total-usage'),
  saveStatus: document.querySelector('#save-status'),
  opacityConfirm: document.querySelector('#opacity-confirm'),
  confirmOpacity: document.querySelector('#confirm-opacity'),
  confirmSeconds: document.querySelector('#confirm-seconds'),
  confirmCancel: document.querySelector('#confirm-cancel'),
  confirmKeep: document.querySelector('#confirm-keep')
};

let currentSettings = null;
let saveTimer = null;
let usageRenderedAt = Date.now();
let confirmCountdownTimer = null;
let isRecordingShortcut = false;

function formatDuration(seconds) {
  const value = Math.max(0, Math.trunc(Number(seconds) || 0));
  const hours = Math.floor(value / 3600);
  const minutes = Math.floor((value % 3600) / 60);
  const secs = value % 60;
  if (hours > 0) return `${hours} 小时 ${minutes} 分 ${secs} 秒`;
  if (minutes > 0) return `${minutes} 分 ${secs} 秒`;
  return `${secs} 秒`;
}

function formatOpacity(opacity) {
  return `${Number((Number(opacity) * 100).toFixed(2))}%`;
}

function render(settings) {
  currentSettings = settings;
  usageRenderedAt = Date.now();
  elements.enabled.checked = settings.enabled;
  elements.opacity.value = Number((settings.opacity * 100).toFixed(2));
  elements.opacityValue.textContent = formatOpacity(settings.opacity);
  elements.color.value = settings.color;
  elements.colorValue.textContent = settings.color;
  if (!isRecordingShortcut) elements.shortcut.textContent = settings.shortcut;
  elements.shortcutHint.textContent = settings.shortcut;
  elements.coverTaskbar.checked = settings.coverTaskbar;
  elements.forceTopEnabled.checked = settings.forceTopEnabled;
  elements.forceTopInterval.value = settings.forceTopIntervalMs;
  elements.forceTopInterval.disabled = !settings.forceTopEnabled;
  elements.totalUsage.textContent = formatDuration(settings.totalUsageSeconds);
  renderOpacityConfirmation(settings.opacityConfirmation, settings.opacity);
}

function renderOpacityConfirmation(confirmation, opacity) {
  clearInterval(confirmCountdownTimer);
  confirmCountdownTimer = null;
  if (!confirmation) {
    elements.opacityConfirm.hidden = true;
    return;
  }

  elements.opacityConfirm.hidden = false;
  elements.confirmOpacity.textContent = formatOpacity(opacity);
  const updateCountdown = () => {
    const seconds = Math.max(0, Math.ceil((confirmation.deadline - Date.now()) / 1000));
    elements.confirmSeconds.textContent = String(seconds);
  };
  updateCountdown();
  confirmCountdownTimer = setInterval(updateCountdown, 250);
}

async function update(changes) {
  clearTimeout(saveTimer);
  elements.saveStatus.textContent = '正在保存...';
  const result = await window.sunglasses.updateSettings(changes);
  if (!result.ok) {
    elements.shortcutError.textContent = result.error || '保存失败';
    elements.saveStatus.textContent = '部分设置未保存';
    return false;
  }
  elements.shortcutError.textContent = '';
  elements.saveStatus.textContent = result.confirmationRequired ? '等待确认高不透明度' : '设置已保存';
  render(result.settings);
  if (!result.confirmationRequired) {
    saveTimer = setTimeout(() => { elements.saveStatus.textContent = '设置会自动保存'; }, 1400);
  }
  return true;
}

elements.enabled.addEventListener('change', () => update({ enabled: elements.enabled.checked }));
elements.opacity.addEventListener('input', () => {
  elements.opacityValue.textContent = `${elements.opacity.value}%`;
});
elements.opacity.addEventListener('change', () => update({ opacity: Number(elements.opacity.value) / 100 }));
elements.color.addEventListener('input', () => { elements.colorValue.textContent = elements.color.value.toUpperCase(); });
elements.color.addEventListener('change', () => update({ color: elements.color.value }));
function stopShortcutRecording() {
  isRecordingShortcut = false;
  elements.shortcut.classList.remove('recording');
  elements.shortcut.textContent = currentSettings.shortcut;
}

function acceleratorKey(event) {
  const aliases = {
    ' ': 'Space',
    ArrowUp: 'Up',
    ArrowDown: 'Down',
    ArrowLeft: 'Left',
    ArrowRight: 'Right',
    Escape: 'Esc',
    '+': 'Plus'
  };
  if (aliases[event.key]) return aliases[event.key];
  if (/^[a-z0-9]$/i.test(event.key)) return event.key.toUpperCase();
  if (/^F([1-9]|1[0-9]|2[0-4])$/.test(event.key)) return event.key;
  if (['Backspace', 'Delete', 'End', 'Home', 'Insert', 'PageDown', 'PageUp', 'Tab'].includes(event.key)) {
    return event.key;
  }
  return null;
}

elements.shortcut.addEventListener('click', () => {
  isRecordingShortcut = true;
  elements.shortcutError.textContent = '';
  elements.shortcut.classList.add('recording');
  elements.shortcut.textContent = '请按下快捷键...';
});

document.addEventListener('keydown', async (event) => {
  if (!isRecordingShortcut) return;
  event.preventDefault();
  event.stopPropagation();
  if (event.key === 'Escape') {
    stopShortcutRecording();
    return;
  }
  if (['Alt', 'Control', 'Shift', 'Meta'].includes(event.key)) return;

  const key = acceleratorKey(event);
  if (!key) {
    elements.shortcutError.textContent = '不支持这个按键，请使用字母、数字、功能键或常用控制键';
    return;
  }
  if (!event.ctrlKey && !event.altKey && !event.shiftKey && !event.metaKey) {
    elements.shortcutError.textContent = '快捷键必须包含 Ctrl、Alt、Shift 或系统键';
    return;
  }
  const parts = [];
  if (event.ctrlKey) parts.push('Control');
  if (event.altKey) parts.push('Alt');
  if (event.shiftKey) parts.push('Shift');
  if (event.metaKey) parts.push('Super');
  parts.push(key);
  const accelerator = parts.join('+');
  isRecordingShortcut = false;
  elements.shortcut.classList.remove('recording');
  if (!await update({ shortcut: accelerator })) {
    elements.shortcut.textContent = currentSettings.shortcut;
  }
}, true);
elements.coverTaskbar.addEventListener('change', () => update({ coverTaskbar: elements.coverTaskbar.checked }));
elements.forceTopEnabled.addEventListener('change', () => update({ forceTopEnabled: elements.forceTopEnabled.checked }));
elements.forceTopInterval.addEventListener('change', () => update({ forceTopIntervalMs: Number(elements.forceTopInterval.value) }));
elements.confirmKeep.addEventListener('click', async () => {
  const result = await window.sunglasses.confirmOpacity();
  if (result.ok) {
    elements.saveStatus.textContent = '高不透明度设置已确认';
    render(result.settings);
  }
});
elements.confirmCancel.addEventListener('click', async () => {
  const result = await window.sunglasses.cancelOpacity();
  if (result.ok) {
    elements.saveStatus.textContent = '已恢复之前的不透明度';
    render(result.settings);
  }
});

window.sunglasses.onSettingsChanged(render);
window.sunglasses.getSettings().then(render);

setInterval(() => {
  if (!currentSettings) return;
  const liveSeconds = currentSettings.totalUsageSeconds + (
    currentSettings.enabled ? Math.trunc((Date.now() - usageRenderedAt) / 1000) : 0
  );
  elements.totalUsage.textContent = formatDuration(liveSeconds);
}, 1000);
