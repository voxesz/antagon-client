const path = require('node:path');
const fs = require('node:fs');
const { Runtime, offlineAccount } = require('../electron/runtime.cjs');
const { DEFAULTS } = require('../electron/settings.cjs');
const root = path.join(require('node:os').homedir(), 'Library/Application Support/Antagon Client');
const runtime = new Runtime(root, path.resolve(__dirname, '../assets'), (e) => {
  if (e.phase === 'running' || e.phase === 'launching' || e.phase === 'error' || e.phase === 'idle') console.log(e);
});
const settings = structuredClone(DEFAULTS);
fs.rmSync(path.join(root, 'minecraft/saves/Antagon-Smoke'), { recursive: true, force: true });
runtime
  .launch(settings, offlineAccount('AntagonTest'), { smoke: true })
  .then(({ pid }) => {
    console.log('Game PID', pid);
    const timeout = setTimeout(() => {
      if (runtime.child) {
        console.error('Smoke timeout');
        runtime.child.kill();
        process.exitCode = 1;
      }
    }, 3e5);
    runtime.child.once('exit', () => {
      clearTimeout(timeout);
      const opts = path.join(root, 'minecraft/options.txt');
      fs.writeFileSync(opts, fs.readFileSync(opts, 'utf8').replace(/^pauseOnLostFocus:.*$/m, 'pauseOnLostFocus:true'));
      const logfile = fs.readFileSync(path.join(root, 'logs/game.log'), 'utf8');
      console.log(
        logfile
          .split('\n')
          .filter((l) => /ANTAGON|Exception|ERROR|error|Caused by/.test(l))
          .slice(-45)
          .join('\n'),
      );
      if (!logfile.includes('[ANTAGON TEST] Screenshot saved') || logfile.includes('[ANTAGON] HUD error'))
        process.exitCode = 1;
    });
  })
  .catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  });
