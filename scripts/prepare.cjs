const path = require('node:path');
const { Runtime } = require('../electron/runtime.cjs');
const root = path.join(
  process.platform === 'win32'
    ? process.env.APPDATA
    : path.join(require('node:os').homedir(), 'Library/Application Support'),
  'Antagon Client',
);
let last = '';
const runtime = new Runtime(root, path.resolve(__dirname, '../assets'), (e) => {
  if (e.message && e.message !== last) {
    last = e.message;
    console.log(e.message);
  }
});
(process.argv.includes('--modern') ? runtime.prepareModern() : runtime.prepare())
  .then((result) => console.log('Instalação pronta:', result.java))
  .catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  });
