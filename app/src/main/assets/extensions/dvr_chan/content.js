// @ts-check
self.browser ??= chrome;

const APP_ENABLED_KEY = 'ytcc-app-dvr-enabled';

syncAppFlagFromUrl();

if (isAppEnabled()) {
  injectPageScript();
}

function syncAppFlagFromUrl() {
  try {
    const url = new URL(location.href);
    const value = url.searchParams.get('ytcc_app_dvr');
    if (value === null) return;
    localStorage.setItem(APP_ENABLED_KEY, value === '0' ? '0' : '1');
  } catch (_error) {
    // Keep the page usable if storage or URL parsing fails.
  }
}

function isAppEnabled() {
  try {
    return localStorage.getItem(APP_ENABLED_KEY) !== '0';
  } catch (_error) {
    return true;
  }
}

function injectPageScript() {
  const script = document.createElement('script');
  script.src = browser.runtime.getURL('dvr-chan-page.js');
  script.async = false;
  script.dataset.ytccDvrChan = '1';
  script.onload = () => script.remove();
  script.onerror = () => script.remove();
  (document.documentElement || document).appendChild(script);
}
