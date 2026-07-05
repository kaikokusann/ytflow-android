// @ts-check

const DEFAULTS = {
  enabled: true,
  hideAvatar: true,
  hideName: true,
  compact: true,
  alignRight: false,
  chatWidthPercent: 40
};

const form = document.querySelector('form');

chrome.storage.sync.get(DEFAULTS, values => {
  for (const key of Object.keys(DEFAULTS)) {
    const input = form?.elements.namedItem(key);
    if (input instanceof HTMLInputElement) {
      input.checked = Boolean(values[key]);
    } else if (input instanceof RadioNodeList) {
      input.value = String(values[key]);
    } else if (input instanceof HTMLSelectElement) {
      input.value = String(values[key]);
    }
  }
});

form?.addEventListener('change', () => {
  const next = {};
  for (const key of Object.keys(DEFAULTS)) {
    const input = form.elements.namedItem(key);
    if (input instanceof HTMLInputElement) {
      next[key] = input.checked;
    } else if (input instanceof RadioNodeList) {
      next[key] = Number.parseInt(String(input.value), 10);
    } else if (input instanceof HTMLSelectElement) {
      next[key] = Number.parseInt(input.value, 10);
    }
  }
  chrome.storage.sync.set(next);
});
