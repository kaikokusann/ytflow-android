import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const router = readFileSync(new URL('../app/src/main/assets/extensions/youtube_router/router.js', import.meta.url), 'utf8');
const activity = readFileSync(new URL('../app/src/main/java/dev/ytchatplayer/app/MainActivity.kt', import.meta.url), 'utf8');
const settings = activity.match(/private fun normalChatSettingsScript\(\): String \{[\s\S]*?return """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(settings, 'The chat-session settings script must exist');
assert.doesNotMatch(activity, /requestNewSurface|legacyTargets|enterVideoFullScreen|PREF_CHAT_ONLY_MODE|chatCanGoBack/);

const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({ viewport: { width: 600, height: 800 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', error => errors.push(error.message));
await context.route('https://*.youtube.com/**', route => route.fulfill({
  contentType: 'text/html',
  body: `<!doctype html><html><body>
    <ytd-app style="display:block"><ytd-watch-flexy style="display:block">
      <div id="movie_player" style="width:300px;height:200px">
        <video style="width:1px;left:-10000px;opacity:.2"></video>
        <div id="yt-lcf-layer">Messages</div>
        <button class="ytp-fullscreen-button">Fullscreen</button>
      </div>
      <div id="chat" style="width:200px">
        <button id="close-chat" aria-label="Close">Close</button>
      </div>
      <button id="open-chat">Chat</button>
      <a id="watch-link" href="https://m.youtube.com/watch?v=next">Video</a>
    </ytd-watch-flexy></ytd-app>
    <script>
      document.querySelector('#close-chat').onclick = () => document.querySelector('#chat').hidden = true;
      document.querySelector('#open-chat').onclick = () => document.querySelector('#chat').hidden = false;
    </script>
    <script>${router}</script>
  </body></html>`,
}));
await page.addInitScript(() => {
  localStorage.setItem('ytcc-router-fullscreen-assist', '1');
  sessionStorage.setItem('ytcc-router-chat-closed', '1');
  window.fullScreenRequests = 0;
  HTMLElement.prototype.requestFullscreen = async () => { window.fullScreenRequests++; };
});

try {
  await page.goto('https://www.youtube.com/watch?v=fixture&ytcc_app_ycc=0&ytcc_app_lcf=1&ytcc_app_dvr=0&ytcc_app_fsassist=1');
  const flags = await page.evaluate(() => ({
    query: location.search,
    ycc: localStorage.getItem('ytcc-app-ycc-enabled'),
    lcf: localStorage.getItem('ytcc-app-lcf-enabled'),
    dvr: localStorage.getItem('ytcc-app-dvr-enabled'),
    closed: document.documentElement.classList.contains('ytcc-chat-closed'),
  }));
  assert.deepEqual(flags, { query: '?v=fixture', ycc: '0', lcf: '1', dvr: '0', closed: false });
  await page.locator('#close-chat').click();
  await page.locator('#open-chat').click();
  await page.locator('.ytp-fullscreen-button').click();
  await page.waitForTimeout(200);
  assert.equal(await page.evaluate(() => window.fullScreenRequests), 0);
  assert.equal(await page.locator('#chat').evaluate(el => getComputedStyle(el).display), 'block');
  assert.equal(await page.locator('#movie_player').evaluate(el => getComputedStyle(el).width), '300px');

  await page.evaluate(() => {
    document.documentElement.classList.add('ytcc-app-pip');
    window.dispatchEvent(new MessageEvent('message', {
      origin: 'https://www.youtube.com', data: { ytccAction: 'close-chat' },
    }));
  });
  assert.equal(await page.locator('#chat').evaluate(el => getComputedStyle(el).display), 'none');
  assert.equal(await page.locator('#movie_player').evaluate(el => getComputedStyle(el).width), '600px');
  assert.equal(await page.locator('#yt-lcf-layer').evaluate(el => getComputedStyle(el).display), 'block');
  await page.evaluate(() => document.documentElement.classList.remove('ytcc-app-pip'));
  assert.equal(await page.locator('#chat').evaluate(el => getComputedStyle(el).display), 'block');
  assert.equal(await page.locator('#movie_player').evaluate(el => getComputedStyle(el).width), '300px');

  await page.evaluate(() => history.pushState({}, '', '/results?search_query=fixture'));
  await page.waitForURL('https://m.youtube.com/results?search_query=fixture');
  await page.locator('#watch-link').click();
  await page.waitForURL(url => url.pathname === '/ytcc-open-video');
  assert.equal(new URL(page.url()).searchParams.get('url'), 'https://www.youtube.com/watch?v=next');

  await page.goto('https://www.youtube.com/live_chat_replay#ytcc_app_chat_only=1');
  await page.evaluate(() => {
    window.chatUpdates = 0;
    window.addEventListener('ytlcf-normal-chat-change', () => window.chatUpdates++);
  });
  await page.evaluate(settings
    .replaceAll('$normalChatFontScale', '140')
    .replaceAll('$normalChatIntervalMs', '50')
    .replaceAll('$showNameValue', '0')
    .replaceAll('$showIconValue', '1'));
  const chat = await page.evaluate(() => ({
    font: document.documentElement.getAttribute('data-ytlcf-app-normal-chat-font-scale'),
    interval: localStorage.getItem('ytlcf-app-normal-chat-interval-ms'),
    name: document.documentElement.getAttribute('data-ytlcf-app-normal-chat-show-name'),
    photo: document.documentElement.getAttribute('data-ytlcf-app-normal-chat-show-photo'),
    videoWidth: document.querySelector('video').style.width,
    videoLeft: document.querySelector('video').style.left,
    updates: window.chatUpdates,
    chatOnly: new URLSearchParams(location.hash.slice(1)).get('ytcc_app_chat_only'),
  }));
  assert.deepEqual(chat, { font: '140', interval: '50', name: '0', photo: '1', videoWidth: '1px', videoLeft: '-10000px', updates: 1, chatOnly: '1' });
  assert.deepEqual(errors, []);
  console.log('PASS: retired helpers stay inactive; routing, PiP, and chat settings remain functional');
} finally {
  await browser.close();
}
