const { contextBridge, ipcRenderer } = require('electron');
async function call(channel, ...args) {
  const result = await ipcRenderer.invoke(channel, ...args);
  if (!result.ok) throw Error(result.error);
  return result.value;
}
contextBridge.exposeInMainWorld('antagon', {
  init: () => call('app:init'),
  saveSettings: (s) => call('settings:save', s),
  login: () => call('account:login'),
  logout: () => call('account:logout'),
  launch: () => call('game:launch'),
  installOptifine: () => call('optifine:install'),
  removeOptifine: () => call('optifine:remove'),
  optifineSite: () => call('optifine:site'),
  pickWallpaper: () => call('wallpaper:pick'),
  resetWallpaper: () => call('wallpaper:reset'),
  openFolder: () => call('app:folder'),
  logs: () => call('app:logs'),
  minimize: () => call('window:minimize'),
  onState: (fn) => {
    const listener = (_, state) => fn(state);
    ipcRenderer.on('game:state', listener);
    return () => ipcRenderer.removeListener('game:state', listener);
  },
});
