const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

if (process.platform !== 'darwin') throw Error('A cópia para a Área de Trabalho requer macOS.');

const source = path.resolve(__dirname, '../release/Antagon Client-darwin-arm64/Antagon Client.app');
const destination = path.join(os.homedir(), 'Desktop/Antagon Client.app');
const suffix = `${process.pid}-${Date.now()}`;
const temporary = path.join(path.dirname(destination), `.Antagon Client.app.${suffix}.tmp`);
const backup = path.join(path.dirname(destination), `.Antagon Client.app.${suffix}.old`);

if (!fs.statSync(source).isDirectory()) throw Error('App empacotado não encontrado.');

try {
  execFileSync('/usr/bin/ditto', [source, temporary], { stdio: 'inherit' });
  execFileSync('/usr/bin/codesign', ['--verify', '--deep', '--strict', temporary], { stdio: 'inherit' });
  const hadPrevious = fs.existsSync(destination);
  if (hadPrevious) fs.renameSync(destination, backup);
  try {
    fs.renameSync(temporary, destination);
  } catch (error) {
    if (hadPrevious) fs.renameSync(backup, destination);
    throw error;
  }
  if (hadPrevious) fs.rmSync(backup, { recursive: true, force: true });
  console.log('Cópia atualizada:', destination);
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
