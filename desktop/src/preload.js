'use strict';
/** The only bridge between the windows (web pages) and the main process. */
const { contextBridge, ipcRenderer } = require('electron');

const on = (channel) => (cb) => {
  const fn = (e, payload) => cb(payload);
  ipcRenderer.on(channel, fn);
  return () => ipcRenderer.removeListener(channel, fn);
};

contextBridge.exposeInMainWorld('sublens', {
  info: () => ipcRenderer.invoke('app:info'),
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (patch) => ipcRenderer.invoke('settings:set', patch),
  onSettings: on('settings'),
  onModel: on('model'),
  onUpdate: on('update'),
  onGeometry: on('lens:geometry'),
  getGeometry: () => ipcRenderer.invoke('lens:get-geometry'),

  showLens: (show) => ipcRenderer.invoke('lens:show', show),
  showPanel: () => ipcRenderer.send('panel:show'),
  drag: (kind) => ipcRenderer.send('lens:drag', kind),
  dragEnd: () => ipcRenderer.send('lens:drag-end'),
  setHit: (hit) => ipcRenderer.send('lens:hit', hit),
  focusLens: () => ipcRenderer.send('lens:focus'),

  process: (png) => ipcRenderer.invoke('engine:process', { png }),
  reply: (text) => ipcRenderer.invoke('engine:reply', { text }),
  downloadModel: () => ipcRenderer.invoke('engine:download-model'),
  modelState: () => ipcRenderer.invoke('engine:model-state'),
  deleteDownloads: () => ipcRenderer.invoke('engine:delete'),

  online: {
    state: () => ipcRenderer.invoke('online:state'),
    set: (patch) => ipcRenderer.invoke('online:set', patch),
    setKey: (id, key) => ipcRenderer.invoke('online:set-key', { id, key }),
    test: (id) => ipcRenderer.invoke('online:test', id),
    models: (id) => ipcRenderer.invoke('online:models', id),
    paste: (id) => ipcRenderer.invoke('online:paste', id || null),
  },

  copy: (text) => ipcRenderer.send('clipboard:write', text),
  openExternal: (url) => ipcRenderer.send('open:external', url),
});
