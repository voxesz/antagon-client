const { contextBridge, ipcRenderer } = require('electron');
async function call(channel, ...args) {
  const result = await ipcRenderer.invoke(channel, ...args);
  if (!result.ok) throw Error(result.error);
  return result.value;
}
contextBridge.exposeInMainWorld('antagon', {
  platform: process.platform,
  discord: {
    state: () => call('discord:state'),
    onState: (fn) => {
      const listener = (_, state) => fn(state);
      ipcRenderer.on('discord:state', listener);
      return () => ipcRenderer.removeListener('discord:state', listener);
    },
  },
  checkUpdate: () => call('update:check'),
  installUpdate: () => call('update:install'),
  community: {
    state: () => call('community:state'),
    login: () => call('community:login'),
    logout: () => call('community:logout'),
    add: (name) => call('community:add', name),
    accept: (id) => call('community:accept', id),
    remove: (id) => call('community:remove', id),
    messages: (id) => call('community:messages', id),
    send: (id, body) => call('community:send', id, body),
    join: (server) => call('community:join', server),
    onEvent: (fn) => {
      const listener = (_, event) => fn(event);
      ipcRenderer.on('community:event', listener);
      return () => ipcRenderer.removeListener('community:event', listener);
    },
  },
  store: {
    state: () => call('store:state'),
    purchase: (item) => call('store:purchase', item),
    equip: (item, kind) => call('store:equip', item, kind),
    checkout: (pack) => call('store:checkout', pack),
  },
  admin: {
    access: () => call('admin:access'),
    find: (name) => call('admin:find', name),
    change: (action, id, value) => call('admin:change', action, id, value),
    catalog: () => call('admin:catalog'),
    setActive: (item, active) => call('admin:setActive', item, active),
    take: (item, take) => call('admin:take', item, take),
    remove: (item) => call('admin:delete', item),
    createCape: (cape) => call('admin:createCape', cape),
  },
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
  onOpenView: (fn) => {
    const listener = (_, view) => fn(view);
    ipcRenderer.on('game:open-view', listener);
    return () => ipcRenderer.removeListener('game:open-view', listener);
  },
});
