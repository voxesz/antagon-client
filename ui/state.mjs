export function createSettingsStore(initial, save) {
  let current = structuredClone(initial);
  let pending = Promise.resolve();
  return {
    get value() {
      return current;
    },
    update(patch) {
      const next = { ...current, ...patch };
      current = next;
      const request = pending.catch(() => {}).then(() => save(next));
      pending = request;
      return request.then((saved) => {
        if (current === next) current = saved;
        return current;
      });
    },
    flush() {
      return pending;
    },
  };
}

export function createConversation() {
  let revision = 0;
  let selected = null;
  let loading = false;
  let messages = new Map();
  const belongs = (message) => message.sender === selected || message.recipient === selected;
  const values = () =>
    [...messages.values()].sort(
      (a, b) =>
        a.created_at.localeCompare(b.created_at) ||
        String(a.id).localeCompare(String(b.id), undefined, { numeric: true }),
    );
  return {
    get selected() {
      return selected;
    },
    get loading() {
      return loading;
    },
    get messages() {
      return values();
    },
    clear() {
      selected = null;
      loading = false;
      messages.clear();
      revision++;
    },
    async open(id, fetchMessages) {
      const request = ++revision;
      selected = id;
      messages = new Map();
      loading = true;
      try {
        const history = await fetchMessages(id);
        if (request !== revision) return false;
        for (const message of history) if (belongs(message)) messages.set(String(message.id), message);
        return true;
      } finally {
        if (request === revision) loading = false;
      }
    },
    receive(message) {
      if (!selected || !belongs(message) || message.id == null || messages.has(String(message.id))) return false;
      messages.set(String(message.id), message);
      return true;
    },
  };
}
