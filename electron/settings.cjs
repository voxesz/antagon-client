const crypto = require('node:crypto');
const DEFAULTS = { mode: 'offline', nickname: 'Player', memory: 3, fullscreen: false, pack: true };
const NICKNAME = /^[A-Za-z0-9_]{3,16}$/;
function validateSettings(input) {
  const settings = structuredClone(DEFAULTS);
  if (input?.mode === 'microsoft') settings.mode = 'microsoft';
  if (typeof input?.nickname === 'string' && NICKNAME.test(input.nickname)) settings.nickname = input.nickname;
  if (Number.isFinite(input?.memory)) settings.memory = Math.max(2, Math.min(8, Math.round(input.memory)));
  for (const key of ['fullscreen', 'pack']) if (typeof input?.[key] === 'boolean') settings[key] = input[key];
  return settings;
}
function offlineAccount(name) {
  if (!NICKNAME.test(name)) throw Error('Use um nick com 3 a 16 letras, números ou _.');
  const bytes = crypto
    .createHash('md5')
    .update('OfflinePlayer:' + name)
    .digest();
  bytes[6] = (bytes[6] & 15) | 48;
  bytes[8] = (bytes[8] & 63) | 128;
  return { name, id: bytes.toString('hex'), accessToken: '0', type: 'legacy', offline: true };
}
module.exports = { DEFAULTS, validateSettings, offlineAccount };
