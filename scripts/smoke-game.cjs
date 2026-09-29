const path = require('node:path');
const fs = require('node:fs/promises');
const os = require('node:os');
const { once } = require('node:events');
const { Runtime, offlineAccount } = require('../electron/runtime.cjs');
const { DEFAULTS } = require('../electron/settings.cjs');
const project = path.resolve(__dirname, '..');
const root = path.join(project, 'build/game-test-profile');
const installed = path.join(
  process.platform === 'win32' ? process.env.APPDATA : path.join(os.homedir(), 'Library/Application Support'),
  'Antagon Client',
);
const screenshots = [
  'antagon-smoke.png',
  'antagon-menu.png',
  'antagon-menu-bottom.png',
  'antagon-menu-pvp.png',
  'antagon-options.png',
  'antagon-edit.png',
  'antagon-scoreboard-clean.png',
  'antagon-fullbright.png',
];

async function main() {
  for (const directory of ['metadata', 'runtime', 'libraries', 'assets', 'minecraft/versions']) {
    const destination = path.join(root, directory);
    if (!(await fs.stat(destination).catch(() => null))) {
      const source = path.join(installed, directory);
      if (await fs.stat(source).catch(() => null)) await fs.cp(source, destination, { recursive: true });
    }
  }
  await fs.rm(path.join(root, 'minecraft/saves/Antagon-Smoke'), { recursive: true, force: true });
  await fs.rm(path.join(root, 'minecraft/screenshots'), { recursive: true, force: true });
  const runtime = new Runtime(root, path.join(project, 'assets'), (state) => {
    if (['running', 'launching', 'error', 'idle'].includes(state.phase)) console.log(state);
  });
  const size = process.env.ANTAGON_SMOKE_SMALL ? { width: 854, height: 480 } : {};
  await runtime.launch({ ...DEFAULTS, discordPresence: false }, offlineAccount('AntagonTest'), {
    smoke: true,
    ...size,
  });
  const child = runtime.child;
  let timedOut = false;
  const timeout = setTimeout(() => {
    timedOut = true;
    child.kill();
  }, 300000);
  try {
    const [code] = await once(child, 'close');
    const logfile = await fs.readFile(path.join(root, 'logs/game.log'), 'utf8');
    console.log(
      logfile
        .split('\n')
        .filter((line) => /ANTAGON/.test(line))
        .slice(-40)
        .join('\n'),
    );
    if (
      timedOut ||
      code !== 0 ||
      logfile.includes('[ANTAGON] HUD error') ||
      /\[ANTAGON TEST\] (?:java\.|.*Exception)/.test(logfile)
    )
      throw Error('O teste do jogo não terminou sem erros.');
    for (const name of screenshots) {
      if (!logfile.includes('[ANTAGON TEST] Screenshot saved ' + name)) throw Error('Etapa não executada: ' + name);
      if ((await fs.stat(path.join(root, 'minecraft/screenshots', name))).size < 10000)
        throw Error('Screenshot inválido: ' + name);
    }
    console.log('Jogo OK: HUD, menu, rolagem, categorias, editor e módulos. Perfil isolado em ' + root);
  } finally {
    clearTimeout(timeout);
  }
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
