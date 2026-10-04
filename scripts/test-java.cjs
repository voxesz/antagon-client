const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'build/java-tests');
const gson = path.join(root, 'build/deps/gson-2.2.4.jar');
const tool = (name) => (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name) : name);
fs.mkdirSync(out, { recursive: true });
execFileSync(
  tool('javac'),
  [
    '--release',
    '8',
    '-cp',
    gson,
    '-d',
    out,
    path.join(root, 'mod-src/studio/antagon/Reflect.java'),
    path.join(root, 'mod-src/studio/antagon/MenuLayout.java'),
    path.join(root, 'mod-src/studio/antagon/ModuleRegistry.java'),
    path.join(root, 'mod-src/studio/antagon/StatusData.java'),
    path.join(root, 'mod-src/studio/antagon/RadioCatalog.java'),
    path.join(root, 'tests/java/studio/antagon/CoreTest.java'),
    path.join(root, 'tests/java/studio/antagon/RadioCatalogTest.java'),
  ],
  { stdio: 'inherit' },
);
for (const test of ['CoreTest', 'RadioCatalogTest'])
  execFileSync(tool('java'), ['-cp', [out, gson].join(path.delimiter), `studio.antagon.${test}`], { stdio: 'inherit' });
