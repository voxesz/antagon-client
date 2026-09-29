const path = require('node:path');
const { Runtime, offlineAccount } = require('../electron/runtime.cjs');
const { DEFAULTS } = require('../electron/settings.cjs');
const root = path.join(require('node:os').homedir(), 'Library/Application Support/Antagon Client');
const runtime = new Runtime(root, path.resolve(__dirname, '../assets'), (e) => {
  if (e.phase === 'error') console.log(e);
});
const settings = structuredClone(DEFAULTS);
runtime
  .launch(settings, offlineAccount('Antagon'), { wallpaper: process.argv[2], width: 1920, height: 1080 })
  .then(() =>
    runtime.child.once('exit', (c) => {
      const fs = require('node:fs'),
        o = path.join(root, 'minecraft/options.txt');
      fs.writeFileSync(o, fs.readFileSync(o, 'utf8').replace(/^pauseOnLostFocus:.*$/m, 'pauseOnLostFocus:true'));
      console.log('done', c);
    }),
  );
