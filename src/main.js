const {
  app,
  BrowserWindow,
  Menu,
  Tray,
  globalShortcut,
  ipcMain,
  nativeImage,
  screen
} = require('electron');
const fs = require('fs');
const path = require('path');

const DEFAULT_SETTINGS = {
  enabled: true,
  opacity: 0.45,
  color: '#000000',
  shortcut: 'Alt+I',
  coverTaskbar: false,
  forceTopEnabled: true,
  forceTopIntervalMs: 1000,
  totalUsageSeconds: 0
};
const OPACITY_LEVELS = [0.2, 0.3, 0.4, 0.5];
const OPACITY_CONFIRM_THRESHOLD = 0.6;
const OPACITY_CONFIRM_TIMEOUT_MS = 15_000;

let settings = { ...DEFAULT_SETTINGS };
let overlayWindows = [];
let settingsWindow = null;
let tray = null;
let rebuildTimer = null;
let forceTopTimer = null;
let usageTimer = null;
let usageStartedAt = null;
let isQuitting = false;
let pendingOpacity = null;
let opacityConfirmTimer = null;

function loadAppIcon(size) {
  const pngPath = path.join(__dirname, '..', 'icon.png');
  const iconPath = path.join(__dirname, '..', 'icon.svg');
  let image = nativeImage.createFromPath(pngPath);
  if (!image.isEmpty()) {
    return image.resize({ width: size, height: size, quality: 'best' });
  }
  image = nativeImage.createFromPath(iconPath);
  if (image.isEmpty()) {
    try {
      const svg = fs.readFileSync(iconPath, 'utf8');
      image = nativeImage.createFromDataURL(`data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`);
    } catch (_) {}
  }
  return image.isEmpty() ? null : image.resize({ width: size, height: size, quality: 'best' });
}

function settingsPath() {
  return path.join(app.getPath('userData'), 'settings.json');
}

function normalizeSettings(input = {}) {
  const opacity = Number(input.opacity);
  const interval = Number(input.forceTopIntervalMs);
  const usage = Number(input.totalUsageSeconds);
  const color = typeof input.color === 'string' && /^#[0-9a-f]{6}$/i.test(input.color)
    ? input.color.toUpperCase()
    : DEFAULT_SETTINGS.color;
  const shortcut = typeof input.shortcut === 'string' && input.shortcut.trim()
    ? input.shortcut.trim()
    : DEFAULT_SETTINGS.shortcut;

  return {
    enabled: typeof input.enabled === 'boolean' ? input.enabled : DEFAULT_SETTINGS.enabled,
    opacity: Number.isFinite(opacity) ? Math.max(0, Math.min(1, opacity)) : DEFAULT_SETTINGS.opacity,
    color,
    shortcut,
    coverTaskbar: typeof input.coverTaskbar === 'boolean'
      ? input.coverTaskbar
      : DEFAULT_SETTINGS.coverTaskbar,
    forceTopEnabled: typeof input.forceTopEnabled === 'boolean'
      ? input.forceTopEnabled
      : DEFAULT_SETTINGS.forceTopEnabled,
    forceTopIntervalMs: Number.isFinite(interval)
      ? Math.max(100, Math.min(60_000, Math.trunc(interval)))
      : DEFAULT_SETTINGS.forceTopIntervalMs,
    totalUsageSeconds: Number.isFinite(usage) ? Math.max(0, Math.trunc(usage)) : 0
  };
}

function loadSettings() {
  try {
    settings = normalizeSettings(JSON.parse(fs.readFileSync(settingsPath(), 'utf8')));
  } catch (_) {
    settings = { ...DEFAULT_SETTINGS };
  }
}

function currentUsageSeconds() {
  const activeSeconds = settings.enabled && usageStartedAt
    ? Math.trunc((Date.now() - usageStartedAt) / 1000)
    : 0;
  return settings.totalUsageSeconds + activeSeconds;
}

function flushUsage() {
  if (!settings.enabled || !usageStartedAt) return;
  settings.totalUsageSeconds = currentUsageSeconds();
  usageStartedAt = Date.now();
}

function saveSettings() {
  fs.mkdirSync(path.dirname(settingsPath()), { recursive: true });
  fs.writeFileSync(settingsPath(), JSON.stringify(settings, null, 2));
}

function settingsSnapshot() {
  return {
    ...settings,
    opacity: pendingOpacity ? pendingOpacity.value : settings.opacity,
    opacityConfirmation: pendingOpacity
      ? { deadline: pendingOpacity.deadline, previousOpacity: pendingOpacity.previousOpacity }
      : null,
    totalUsageSeconds: currentUsageSeconds()
  };
}

function overlayHtml() {
  const alpha = settings.enabled ? (pendingOpacity ? pendingOpacity.value : settings.opacity) : 0;
  const red = parseInt(settings.color.slice(1, 3), 16);
  const green = parseInt(settings.color.slice(3, 5), 16);
  const blue = parseInt(settings.color.slice(5, 7), 16);
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    html,body{width:100%;height:100%;margin:0;overflow:hidden;background:rgba(${red},${green},${blue},${alpha})}
  </style></head><body></body></html>`;
}

function raiseOverlay(window) {
  if (!window || window.isDestroyed()) return;
  try { window.setAlwaysOnTop(true, 'screen-saver', 1); } catch (_) {}
  try { window.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true, skipTransformProcessType: true }); } catch (_) {}
  try { window.moveTop(); } catch (_) {}
}

function isSettingsWindowFocused() {
  return Boolean(
    settingsWindow
    && !settingsWindow.isDestroyed()
    && settingsWindow.isFocused()
  );
}

function createOverlay(display) {
  const area = settings.coverTaskbar ? display.bounds : display.workArea;
  const window = new BrowserWindow({
    ...area,
    frame: false,
    transparent: true,
    backgroundColor: '#00000000',
    show: false,
    skipTaskbar: true,
    resizable: false,
    movable: false,
    minimizable: false,
    maximizable: false,
    focusable: false,
    acceptFirstMouse: false,
    hasShadow: false,
    enableLargerThanScreen: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false
    }
  });

  raiseOverlay(window);
  if (settings.coverTaskbar) {
    try { window.setFullScreen(true); } catch (_) {}
  }
  window.setIgnoreMouseEvents(true, { forward: false });
  window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(overlayHtml())}`);
  window.once('ready-to-show', () => {
    window.showInactive();
    raiseOverlay(window);
  });
  window.on('blur', () => {
    if (settings.forceTopEnabled && !isSettingsWindowFocused()) raiseOverlay(window);
  });
  return window;
}

function rebuildOverlays() {
  for (const window of overlayWindows) window.destroy();
  overlayWindows = screen.getAllDisplays().map(createOverlay);
  restartForceTopLoop();
}

function scheduleRebuild() {
  clearTimeout(rebuildTimer);
  rebuildTimer = setTimeout(rebuildOverlays, 150);
}

function restartForceTopLoop() {
  clearInterval(forceTopTimer);
  forceTopTimer = null;
  if (!settings.forceTopEnabled || !settings.enabled || isSettingsWindowFocused()) return;
  forceTopTimer = setInterval(() => {
    for (const window of overlayWindows) raiseOverlay(window);
  }, settings.forceTopIntervalMs);
  forceTopTimer.unref?.();
}

function notifySettingsWindow() {
  if (settingsWindow && !settingsWindow.isDestroyed()) {
    settingsWindow.webContents.send('settings-changed', settingsSnapshot());
  }
}

function clearPendingOpacity({ rebuild = true, notify = true } = {}) {
  if (!pendingOpacity) return;
  clearTimeout(opacityConfirmTimer);
  opacityConfirmTimer = null;
  pendingOpacity = null;
  if (rebuild) rebuildOverlays();
  if (notify) notifySettingsWindow();
}

function requestOpacityConfirmation(value) {
  clearTimeout(opacityConfirmTimer);
  pendingOpacity = {
    value,
    previousOpacity: settings.opacity,
    deadline: Date.now() + OPACITY_CONFIRM_TIMEOUT_MS
  };
  opacityConfirmTimer = setTimeout(() => {
    rollbackPendingOpacity();
  }, OPACITY_CONFIRM_TIMEOUT_MS);
  opacityConfirmTimer.unref?.();
  rebuildOverlays();
  rebuildTrayMenu();
  notifySettingsWindow();
  return { ok: true, confirmationRequired: true, settings: settingsSnapshot() };
}

function rollbackPendingOpacity() {
  if (!pendingOpacity) return;
  const safeOpacity = Math.min(
    pendingOpacity.previousOpacity,
    OPACITY_CONFIRM_THRESHOLD - 0.01
  );
  clearTimeout(opacityConfirmTimer);
  opacityConfirmTimer = null;
  pendingOpacity = null;
  settings.opacity = safeOpacity;
  saveSettings();
  rebuildOverlays();
  rebuildTrayMenu();
  notifySettingsWindow();
}

function confirmPendingOpacity() {
  if (!pendingOpacity) return { ok: false, error: '没有等待确认的不透明度设置' };
  const value = pendingOpacity.value;
  clearTimeout(opacityConfirmTimer);
  opacityConfirmTimer = null;
  pendingOpacity = null;
  settings.opacity = value;
  saveSettings();
  rebuildTrayMenu();
  rebuildOverlays();
  notifySettingsWindow();
  return { ok: true, settings: settingsSnapshot() };
}

function cancelPendingOpacity() {
  rollbackPendingOpacity();
  return { ok: true, settings: settingsSnapshot() };
}

function applySettings(changes, options = {}) {
  const targetEnabled = changes.enabled !== undefined ? Boolean(changes.enabled) : settings.enabled;
  if (!targetEnabled) clearPendingOpacity({ rebuild: false, notify: false });
  if (changes.opacity !== undefined) {
    const requestedOpacity = normalizeSettings({ ...settings, opacity: changes.opacity }).opacity;
    if (
      targetEnabled
      && requestedOpacity > OPACITY_CONFIRM_THRESHOLD
      && requestedOpacity !== settings.opacity
    ) {
      return requestOpacityConfirmation(requestedOpacity);
    }
    clearPendingOpacity({ rebuild: false, notify: false });
  }
  const previous = settings;
  const next = normalizeSettings({ ...settings, ...changes });
  if (changes.shortcut !== undefined && !registerShortcut(next.shortcut)) {
    registerShortcut(previous.shortcut);
    return { ok: false, error: `无法注册全局快捷键 ${next.shortcut}` };
  }

  if (previous.enabled && !next.enabled) {
    flushUsage();
    next.totalUsageSeconds = settings.totalUsageSeconds;
    usageStartedAt = null;
  } else if (!previous.enabled && next.enabled) {
    usageStartedAt = Date.now();
  }
  settings = next;
  saveSettings();
  rebuildTrayMenu();
  rebuildOverlays();
  if (
    !previous.enabled
    && settings.enabled
    && settings.opacity > OPACITY_CONFIRM_THRESHOLD
  ) {
    const result = requestOpacityConfirmation(settings.opacity);
    createSettingsWindow();
    return result;
  }
  if (options.notify !== false) notifySettingsWindow();
  return { ok: true, settings: settingsSnapshot() };
}

function toggleEnabled() {
  applySettings({ enabled: !settings.enabled });
}

function registerShortcut(accelerator) {
  globalShortcut.unregisterAll();
  try {
    return Boolean(accelerator && globalShortcut.register(accelerator, toggleEnabled));
  } catch (_) {
    return false;
  }
}

function createSettingsWindow() {
  if (settingsWindow && !settingsWindow.isDestroyed()) {
    settingsWindow.center();
    settingsWindow.show();
    settingsWindow.focus();
    return;
  }

  settingsWindow = new BrowserWindow({
    width: 860,
    height: 580,
    minWidth: 860,
    minHeight: 580,
    center: true,
    icon: loadAppIcon(256) || undefined,
    title: 'Sunglasses 设置',
    backgroundColor: '#f3f4f6',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  settingsWindow.setAlwaysOnTop(true, 'screen-saver', 2);
  settingsWindow.loadFile(path.join(__dirname, 'settings.html'));
  settingsWindow.on('focus', () => {
    clearInterval(forceTopTimer);
    forceTopTimer = null;
    try { settingsWindow.setAlwaysOnTop(true, 'screen-saver', 2); } catch (_) {}
    try { settingsWindow.moveTop(); } catch (_) {}
  });
  settingsWindow.on('blur', restartForceTopLoop);
  settingsWindow.on('closed', () => {
    settingsWindow = null;
    restartForceTopLoop();
  });
}

function createTrayIcon() {
  const appIcon = loadAppIcon(16);
  if (appIcon) return appIcon;

  const size = 16;
  const pixels = Buffer.alloc(size * size * 4);
  for (let y = 2; y < 14; y += 1) {
    for (let x = 1; x < 15; x += 1) {
      const lens = (x < 7 || x > 8) && y >= 6 && y <= 11;
      const frame = y === 5 || y === 12 || x === 1 || x === 6 || x === 9 || x === 14;
      const bridge = y === 6 && x >= 6 && x <= 9;
      if (!lens && !frame && !bridge) continue;
      const index = (y * size + x) * 4;
      pixels[index] = 25;
      pixels[index + 1] = 25;
      pixels[index + 2] = 25;
      pixels[index + 3] = frame || bridge ? 255 : 190;
    }
  }
  return nativeImage.createFromBuffer(pixels, { width: size, height: size });
}

function rebuildTrayMenu() {
  if (!tray) return;
  const template = [
    { label: '打开设置', click: createSettingsWindow },
    { type: 'separator' },
    {
      label: `启用遮罩 [${settings.shortcut}]`,
      type: 'checkbox',
      checked: settings.enabled,
      click: toggleEnabled
    },
    ...OPACITY_LEVELS.map((level) => ({
      label: `${Math.round(level * 100)}% 不透明度`,
      type: 'radio',
      checked: (pendingOpacity ? pendingOpacity.value : settings.opacity) === level,
      click: () => {
        if (!settings.enabled) applySettings({ enabled: true });
        const result = applySettings({ opacity: level });
        if (result.confirmationRequired) createSettingsWindow();
      }
    })),
    { type: 'separator' },
    { label: '退出 Sunglasses', click: () => app.quit() }
  ];
  tray.setContextMenu(Menu.buildFromTemplate(template));
  const visibleOpacity = pendingOpacity ? pendingOpacity.value : settings.opacity;
  tray.setToolTip(`Sunglasses: ${settings.enabled ? `${Math.round(visibleOpacity * 100)}%` : '已关闭'}`);
}

ipcMain.handle('settings:get', () => settingsSnapshot());
ipcMain.handle('settings:update', (_event, changes = {}) => {
  const allowedChanges = {};
  for (const key of ['enabled', 'opacity', 'color', 'shortcut', 'coverTaskbar', 'forceTopEnabled', 'forceTopIntervalMs']) {
    if (Object.prototype.hasOwnProperty.call(changes, key)) allowedChanges[key] = changes[key];
  }
  return applySettings(allowedChanges);
});
ipcMain.handle('opacity:confirm', () => confirmPendingOpacity());
ipcMain.handle('opacity:cancel', () => cancelPendingOpacity());

app.whenReady().then(() => {
  if (process.platform === 'darwin') {
    const dockIcon = loadAppIcon(512);
    if (dockIcon) app.dock.setIcon(dockIcon);
    app.dock.hide();
  }
  loadSettings();
  usageStartedAt = settings.enabled ? Date.now() : null;
  tray = new Tray(createTrayIcon());
  tray.on('click', createSettingsWindow);
  tray.on('double-click', createSettingsWindow);
  tray.on('right-click', createSettingsWindow);
  tray.on('middle-click', createSettingsWindow);
  rebuildTrayMenu();
  rebuildOverlays();
  if (settings.enabled && settings.opacity > OPACITY_CONFIRM_THRESHOLD) {
    requestOpacityConfirmation(settings.opacity);
    createSettingsWindow();
  }
  if (!registerShortcut(settings.shortcut)) {
    settings.shortcut = DEFAULT_SETTINGS.shortcut;
    registerShortcut(settings.shortcut);
    saveSettings();
  }

  usageTimer = setInterval(() => {
    flushUsage();
    saveSettings();
    notifySettingsWindow();
  }, 60_000);
  usageTimer.unref?.();

  screen.on('display-added', scheduleRebuild);
  screen.on('display-removed', scheduleRebuild);
  screen.on('display-metrics-changed', scheduleRebuild);
});

app.on('window-all-closed', () => {
  // The tray owns the application lifecycle.
});

app.on('before-quit', () => {
  isQuitting = true;
  flushUsage();
  saveSettings();
  clearTimeout(rebuildTimer);
  clearInterval(forceTopTimer);
  clearInterval(usageTimer);
  clearTimeout(opacityConfirmTimer);
  globalShortcut.unregisterAll();
  for (const window of overlayWindows) window.destroy();
  overlayWindows = [];
});

app.on('activate', () => {
  if (!isQuitting) createSettingsWindow();
});
