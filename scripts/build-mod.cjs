const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { download } = require('../electron/runtime.cjs');
const root = path.resolve(__dirname, '..');
const build = path.join(root, 'build');
const deps = path.join(build, 'deps');
const classes = path.join(build, 'classes');
const stubs = path.join(build, 'stubs');
const source = path.join(root, 'mod-src');
const output = path.join(root, 'assets/antagon-hud-0.1.0.jar');
const DEPENDENCIES = [
  [
    'https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar',
    'beda619c465af293e63952dd573c137c17c0a4cd',
  ],
  [
    'https://libraries.minecraft.net/org/lwjgl/lwjgl/lwjgl/2.9.4-nightly-20150209/lwjgl-2.9.4-nightly-20150209.jar',
    '697517568c68e78ae0b4544145af031c81082dfe',
  ],
  [
    'https://libraries.minecraft.net/com/google/guava/guava/17.0/guava-17.0.jar',
    '9c6ef172e8de35fd8d4d8783e4821e57cdef7445',
  ],
  [
    'https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar',
    '111e7bea9c968cdb3d06ef4632bf7ff0824d0f36',
  ],
  [
    'https://libraries.minecraft.net/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar',
    '4333508b8dd8ee72aa4e39afa713b3a74579b773',
  ],
];
const MOD_INFO = [
  {
    modid: 'antagonhud',
    name: 'Antagon Client',
    version: '0.1.0',
    mcversion: '1.8.9',
    description: 'Customizable PvP mods and cosmetics for Antagon Client.',
    authorList: ['Antagon Studio'],
    clientSideOnly: true,
  },
];
const tool = (name) => (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name) : name);
const run = (name, args) => execFileSync(tool(name), args, { stdio: 'inherit' });
const javaFiles = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? javaFiles(file) : entry.name.endsWith('.java') ? [file] : [];
  });
async function main() {
  const classpath = [];
  for (const [url, sha1] of DEPENDENCIES) {
    const file = path.join(deps, path.basename(url));
    await download(url, file, sha1);
    classpath.push(file);
  }
  for (const dir of [classes, stubs]) fs.rmSync(dir, { recursive: true, force: true });
  run('javac', [
    '--release',
    '8',
    '-Xlint:-options',
    '-encoding',
    'UTF-8',
    '-d',
    stubs,
    ...javaFiles(path.join(source, 'stubs')),
  ]);
  run('javac', [
    '--release',
    '8',
    '-Xlint:-options',
    '-encoding',
    'UTF-8',
    '-cp',
    [...classpath, stubs].join(path.delimiter),
    '-d',
    classes,
    ...javaFiles(path.join(source, 'studio')),
  ]);
  const resources = path.join(classes, 'assets/antagon');
  fs.mkdirSync(resources, { recursive: true });
  fs.cpSync(path.join(source, 'resources'), classes, { recursive: true });
  fs.copyFileSync(path.join(root, 'assets/PixelifySans.ttf'), path.join(resources, 'PixelifySans.ttf'));
  fs.copyFileSync(path.join(root, 'assets/PixelifySans-LICENSE.txt'), path.join(resources, 'FONT-LICENSE.txt'));
  fs.writeFileSync(path.join(classes, 'mcmod.info'), JSON.stringify(MOD_INFO));
  const manifest = path.join(build, 'MANIFEST.MF');
  fs.writeFileSync(manifest, 'FMLCorePlugin: studio.antagon.core.AntagonCore\nFMLCorePluginContainsFMLMod: true\n');
  run('jar', ['cfm', output, manifest, '-C', classes, '.']);
  console.log('Antagon HUD built:', path.relative(root, output));
}
main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
