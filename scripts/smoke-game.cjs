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
  'antagon-title-buttons.png',
  'antagon-pause-buttons.png',
  'antagon-cape-test.png',
  'antagon-cape-late.png',
  'antagon-smoke.png',
  'antagon-menu.png',
  'antagon-menu-bottom.png',
  'antagon-menu-pvp.png',
  'antagon-options.png',
  'antagon-cps-options.png',
  'antagon-edit.png',
  'antagon-scoreboard-clean.png',
  'antagon-fullbright.png',
  'antagon-logo-cape.png',
  'antagon-hub-friends.png',
  'antagon-hub-store.png',
  'antagon-hub-inventory.png',
  'antagon-crown.png',
  'antagon-notice.png',
  'antagon-item-physics.png',
  'antagon-item-size-small.png',
  'antagon-item-size-default.png',
  'antagon-item-size-options.png',
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
  const withOptifine = process.env.ANTAGON_TEST_OPTIFINE === '1';
  if (withOptifine) {
    const mods = path.join(root, 'minecraft/mods');
    await fs.mkdir(mods, { recursive: true });
    const installedMods = path.join(installed, 'minecraft/mods');
    const optifine = (await fs.readdir(installedMods)).find((file) => /^OptiFine.*\.jar$/i.test(file));
    if (!optifine) throw Error('OptiFine não está instalado no perfil principal.');
    await fs.copyFile(path.join(installedMods, optifine), path.join(mods, optifine));
  }
  const runtime = new Runtime(root, path.join(project, 'assets'), (state) => {
    if (['running', 'launching', 'error', 'idle'].includes(state.phase)) console.log(state);
  });
  const size = process.env.ANTAGON_SMOKE_SMALL ? { width: 854, height: 480 } : {};
  const account = offlineAccount(process.env.ANTAGON_TEST_PLAYER || 'AntagonTest');
  await runtime.launch({ ...DEFAULTS, discordPresence: false }, account, {
    smoke: true,
    admin: process.env.ANTAGON_TEST_ADMIN === '1',
    beforeSpawn: async () =>
      fs.writeFile(
        path.join(root, 'minecraft/antagon-cosmetics.properties'),
        `${account.id}=client,cape:antagon_cape,hat:antagon_crown,admin\n`,
      ),
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
    if (withOptifine && !logfile.includes('[ANTAGON] OptiFine cape bridge active'))
      throw Error('A capa Antagon não substituiu a textura da OptiFine.');
    if (withOptifine && !logfile.includes('[ANTAGON] Player render cape bridge active'))
      throw Error('A capa Antagon não foi aplicada na renderização do jogador.');
    if (!logfile.includes('[ANTAGON] Menu icons rendered'))
      throw Error('Os ícones Antagon não apareceram nos botões do jogo.');
    if (!logfile.includes('[ANTAGON] Item Size patch applied'))
      throw Error('O Item Size não foi instalado no renderizador.');
    if (!logfile.includes('[ANTAGON TEST] Item Size renders the configured scale'))
      throw Error('O Item Size não passou na verificação dentro do jogo.');
    if (!logfile.includes('[ANTAGON] Hat layer installed')) throw Error('A camada da coroa não foi instalada.');
    if (!logfile.includes('[ANTAGON TEST] Cosmetics and roster ready on world entry'))
      throw Error('Cosméticos não ficaram prontos na entrada do mundo.');
    if (!logfile.includes('[ANTAGON TEST] Store button opened the in-game store'))
      throw Error('O botão da loja não abriu a loja dentro do jogo.');
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
