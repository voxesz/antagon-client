const { DiscordPresence } = require('../electron/discord.cjs');
const { DEFAULTS } = require('../electron/settings.cjs');
const rpc = new DiscordPresence((state) => {
  console.log(state.message);
  if (state.status === 'connected' || state.status === 'error') finish(state.status === 'connected');
});
const timeout = setTimeout(() => finish(false), 15000);
function finish(connected) {
  clearTimeout(timeout);
  rpc.stop();
  process.exitCode = connected ? 0 : 1;
}
rpc.setActivity({ activity: 'launcher', startedAt: Date.now() });
rpc.configure(DEFAULTS);
