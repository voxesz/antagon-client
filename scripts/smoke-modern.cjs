const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { execFileSync } = require('node:child_process');
const { once } = require('node:events');
const { zipSync } = require('fflate');
const { Runtime, download, offlineAccount } = require('../electron/runtime.cjs');
const { DEFAULTS } = require('../electron/settings.cjs');

async function main() {
  const project = path.resolve(__dirname, '..');
  const root = path.join(project, 'build/modern-game-test');
  const installed = path.join(
    process.platform === 'win32' ? process.env.APPDATA : path.join(os.homedir(), 'Library/Application Support'),
    'Antagon Client',
  );
  await fs.mkdir(root, { recursive: true });
  for (const directory of ['assets', 'libraries', 'runtime', 'metadata']) {
    const destination = path.join(root, directory);
    if (
      !(await fs.lstat(destination).catch(() => null)) &&
      (await fs.stat(path.join(installed, directory)).catch(() => null))
    )
      await fs.symlink(path.join(installed, directory), destination, process.platform === 'win32' ? 'junction' : 'dir');
  }
  let observedRunning = false;
  const runtime = new Runtime(root, path.join(project, 'assets'), (state) => {
    if (state.phase === 'running') observedRunning = true;
    if (['launching', 'error', 'idle'].includes(state.phase)) console.log(state);
  });
  const installation = await runtime.prepareModern();
  const game = installation.game;
  // Only this test world is removed; the profile never points to the user's saves.
  await fs.rm(path.join(game, 'saves/Antagon Modern Smoke'), { recursive: true, force: true });
  await fs.rm(path.join(game, 'screenshots'), { recursive: true, force: true });
  await fs.writeFile(
    path.join(game, 'options.txt'),
    'guiScale:2\nmaxFps:120\nenableVsync:false\nrenderDistance:5\nsimulationDistance:5\nfullscreen:false\nresourcePacks:[]\nonboardAccessibility:false\npauseOnLostFocus:false\nsoundCategory_master:0.0\n',
  );

  const query = new URLSearchParams({ game_versions: JSON.stringify([installation.version]) });
  const response = await fetch(`https://api.modrinth.com/v2/project/complementary-reimagined/version?${query}`);
  if (!response.ok) throw Error('Falha ao consultar o shader de teste.');
  const shaders = await response.json();
  const shader = shaders.find((item) => item.version_type === 'release')?.files.find((file) => file.primary);
  if (!shader) throw Error('Shader de teste compatível não encontrado.');
  await download(shader.url, path.join(game, 'shaderpacks', path.basename(shader.filename)), shader.hashes.sha1);
  await fs.mkdir(path.join(game, 'config'), { recursive: true });
  await fs.writeFile(path.join(game, 'config/antagon-modern.properties'), 'toggleSprint=false\n');
  await fs.writeFile(path.join(game, 'config/iris.properties'), `enableShaders=true\nshaderPack=${shader.filename}\n`);

  const classes = path.join(root, 'test-classes');
  await fs.mkdir(classes, { recursive: true });
  const javaHome =
    process.env.ANTAGON_MODERN_JAVA_HOME ||
    (process.platform === 'darwin'
      ? execFileSync('/usr/libexec/java_home', ['-v', '25+'], { encoding: 'utf8' }).trim()
      : process.env.JAVA_HOME);
  const javac = javaHome ? path.join(javaHome, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac') : 'javac';
  execFileSync(
    javac,
    [
      '--release',
      '25',
      '-cp',
      [...installation.classpath, ...installation.mods.map((file) => path.join(game, 'mods', file))].join(
        path.delimiter,
      ),
      '-d',
      classes,
      path.join(project, 'tests/java/studio/antagon/ModernSmoke.java'),
    ],
    { stdio: 'inherit' },
  );
  const className = 'studio/antagon/ModernSmoke.class';
  await fs.writeFile(
    path.join(game, 'mods/antagon-smoke-test.jar'),
    zipSync({
      [className]: await fs.readFile(path.join(classes, className)),
      'fabric.mod.json': Buffer.from(
        JSON.stringify({
          schemaVersion: 1,
          id: 'antagon_smoke_test',
          version: '1.0.0',
          environment: 'client',
          entrypoints: { client: ['studio.antagon.ModernSmoke'] },
        }),
      ),
    }),
  );
  runtime.prepareModern = async () => installation;
  const account = offlineAccount('AntagonTest');
  await runtime.launch({ ...DEFAULTS, gameVersion: 'latest-26', memory: 4, discordPresence: false }, account, {
    beforeSpawn: async (directory) =>
      fs.writeFile(path.join(directory, 'antagon-cosmetics.properties'), `${account.id}=client,cape:antagon_cape\n`),
  });
  const child = runtime.child;
  const timeout = setTimeout(() => child.kill(), 240000);
  try {
    const [code] = await once(child, 'close');
    const log = await fs.readFile(path.join(root, 'logs/game.log'), 'utf8');
    console.log(
      log
        .split('\n')
        .filter((line) => /MODERN TEST|ERROR|Exception/.test(line))
        .slice(-30)
        .join('\n'),
    );
    if (code !== 0 || !observedRunning || !log.includes('[MODERN TEST] OK:'))
      throw Error('O teste do Minecraft moderno falhou.');
    if (!log.includes('[MODERN TEST] Essential loaded')) throw Error('O Essential não foi carregado.');
    if (!log.includes('[MODERN TEST] Antagon capes and Toggle Sprint verified'))
      throw Error('Capas ou Toggle Sprint falharam.');
    if (!log.includes('[MODERN TEST] Minimap and Litematica schematic verified'))
      throw Error('Minimapa ou schematic do Litematica falhou.');
    if (!log.includes('[MODERN TEST] Fullscreen world map and J shortcut verified'))
      throw Error('Mapa em tela cheia ou atalho J falhou.');
    for (const name of [
      'modern-menu',
      'modern-iris',
      'modern-world-shaders',
      'modern-cape-builtins',
      'modern-cape-custom',
      'modern-cape-sprint',
      'modern-world-map',
      'modern-world-map-zoom',
      'modern-minimap-settings',
      'modern-litematica-menu',
      'modern-litematica-schematic',
    ]) {
      const png = await fs.readFile(path.join(game, 'screenshots', name + '.png'));
      if (png.length < 3000 || png.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a')
        throw Error('Screenshot inválido: ' + name);
    }
    console.log(
      `Minecraft ${installation.version} OK: Essential, capas, sprint, minimapa, mapa em tela cheia, shaders e Litematica (schematic sem shaders). Evidências: ${game}/screenshots`,
    );
  } finally {
    clearTimeout(timeout);
    if (runtime.child) runtime.child.kill();
  }
}
main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
