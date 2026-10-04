const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const profile = path.join(root, 'build/game-test-profile');
const out = path.join(root, 'build/java-tests');
const fixtures = path.join(root, 'build/audio-test');
const jars = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? jars(file) : entry.name.endsWith('.jar') ? [file] : [];
  });
const classpath = [
  out,
  path.join(root, 'build/classes'),
  path.join(root, 'build/deps/jlayer-1.0.1.jar'),
  ...jars(path.join(profile, 'libraries/com/paulscode')),
].join(path.delimiter);
fs.mkdirSync(fixtures, { recursive: true });
fs.mkdirSync(out, { recursive: true });
const index = JSON.parse(fs.readFileSync(path.join(profile, 'assets/indexes/1.8.json'), 'utf8'));
const hash = index.objects['minecraft/sounds/random/click.ogg'].hash;
const ogg = path.join(fixtures, 'click.ogg');
fs.copyFileSync(path.join(profile, 'assets/objects', hash.slice(0, 2), hash), ogg);
execFileSync(
  'javac',
  [
    '--release',
    '8',
    '-Xlint:-options',
    '-cp',
    classpath,
    '-d',
    out,
    path.join(root, 'tests/java/studio/antagon/RadioAudioTest.java'),
  ],
  { stdio: 'inherit' },
);
execFileSync('java', ['-cp', classpath, 'studio.antagon.RadioAudioTest', fixtures, ogg], { stdio: 'inherit' });
