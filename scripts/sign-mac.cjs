const path = require('node:path');
const { execFileSync } = require('node:child_process');
if (process.platform !== 'darwin') throw Error('A assinatura local deve ser feita no macOS.');
const bundle = path.resolve(__dirname, '../release/Antagon Client-darwin-arm64/Antagon Client.app');
execFileSync('/usr/bin/codesign', ['--force', '--deep', '--sign', '-', bundle], { stdio: 'inherit' });
execFileSync('/usr/bin/codesign', ['--verify', '--deep', '--strict', bundle], { stdio: 'inherit' });
