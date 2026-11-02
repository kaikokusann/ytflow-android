import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = fileURLToPath(new URL('../app/src/main/assets/extensions/', import.meta.url));
const server = http.createServer(async (req, res) => {
  const file = path.resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
  if (!file.startsWith(root)) { res.writeHead(403).end(); return; }
  try {
    const body = await fs.readFile(file);
    const type = { '.mjs': 'text/javascript', '.js': 'text/javascript', '.html': 'text/html', '.css': 'text/css', '.json': 'application/json' }[path.extname(file)];
    res.setHeader('Content-Type', type || 'application/octet-stream');
    res.end(body);
  } catch { res.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = await chromium.launch({ headless: true });
let checks = 0;

async function fixture(folder, viewport, colorScheme) {
  const context = await browser.newContext({ viewport, colorScheme });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const manifest = JSON.parse(await fs.readFile(path.join(root, folder, 'manifest.json'), 'utf8'));
  const locale = folder === 'live_chat_flusher'
    ? JSON.parse(await fs.readFile(path.join(root, folder, '_locales/ja/messages.json'), 'utf8')) : {};
  await page.addInitScript(({ origin, folder, manifest, locale }) => {
    const data = {
      others: { mode_livestream: 1, mode_replay: 1, time_shift: 3, translation: -2, except_lang: 5 },
      styles: { font_size: '18px' },
    };
    window.testStorage = data;
    window.testMessages = [];
    const api = {
      runtime: {
        getManifest: () => manifest,
        getPlatformInfo: async () => ({ os: 'android' }),
        getURL: url => new URL(url.replace(/^\//, ''), `${origin}/${folder}/`).href,
        sendMessage: async message => { window.testMessages.push(message); return {}; },
      },
      i18n: { getMessage: key => locale[key]?.message || key },
      storage: {
        local: { get: async () => structuredClone(data), set: async value => Object.assign(data, structuredClone(value)) },
        sync: {
          get: (defaults, callback) => callback({ ...defaults, ...(data.sync || {}) }),
          set: value => { data.sync = value; },
        },
      },
      permissions: { request: async () => true },
    };
    window.browser = window.chrome = api;
  }, { origin, folder, manifest, locale });
  return { context, page, errors };
}

try {
  for (const viewport of [{ width: 360, height: 640 }, { width: 740, height: 360 }, { width: 920, height: 650 }]) {
    for (const scheme of ['light', 'dark']) {
      const { context, page, errors } = await fixture('live_chat_flusher', viewport, scheme);
      await page.goto(`${origin}/live_chat_flusher/options/options.html`);
      await page.waitForFunction(() => document.querySelector('input[name="mode_livestream"]:checked'));
      assert.equal(await page.locator('body').evaluate(el => getComputedStyle(el).fontSize), '18px');
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      for (const id of ['btn-save-top', 'btn-save']) {
        assert(await page.locator(`#${id}`).isDisabled());
        assert((await page.locator(`#${id}`).boundingBox()).height >= 48);
      }
      const first = page.locator('input[name="mode_livestream"][value="0"]');
      assert((await first.boundingBox()).height >= 24);
      assert((await first.locator('..').boundingBox()).height >= 48);
      await first.check();
      assert(await page.locator('#btn-save-top').isEnabled());
      await page.locator('input[name="mode_livestream"][value="1"]').check();
      assert(await page.locator('#btn-save-top').isDisabled());
      await first.check();
      await page.locator('#btn-save-top').click();
      await page.waitForFunction(() => location.hash.startsWith('#ytchat-saved-'));
      assert(await page.locator('#btn-save-top').isDisabled());
      assert(await page.locator('#btn-save').isDisabled());
      const result = await page.evaluate(() => ({ data: window.testStorage, messages: window.testMessages }));
      assert.equal(result.data.styles.font_size, '18px');
      assert.equal(result.data.others.time_shift_normal, 3);
      assert.equal(result.data.others.time_shift_low, 3);
      assert.equal(result.data.translation.targetIndex, 2);
      assert.equal(result.data.translation.prefixLangCode, true);
      assert.equal(result.messages.length, 0);
      assert.deepEqual(errors, []);
      await page.setViewportSize({ width: viewport.height, height: viewport.width });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      assert.equal(await page.locator('input[name="mode_livestream"]:checked').inputValue(), '0');
      if (process.env.SCREENSHOT_DIR) {
        await fs.mkdir(process.env.SCREENSHOT_DIR, { recursive: true });
        await page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, `flusher-${viewport.width}-${scheme}.png`) });
      }
      await context.close();
      checks++;

      const ycc = await fixture('youtube_chat_cleaner', viewport, scheme);
      await ycc.page.goto(`${origin}/youtube_chat_cleaner/popup.html`);
      assert.equal(await ycc.page.locator('body').evaluate(el => getComputedStyle(el).fontSize), '18px');
      await ycc.page.locator('input[name="chatWidthPercent"][value="50"]').check();
      assert.equal(await ycc.page.evaluate(() => window.testStorage.sync.chatWidthPercent), 50);
      await ycc.page.locator('input[name="hideAvatar"]').uncheck();
      assert.equal(await ycc.page.evaluate(() => window.testStorage.sync.hideAvatar), false);
      assert(await ycc.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      assert.deepEqual(ycc.errors, []);
      if (process.env.SCREENSHOT_DIR) {
        await ycc.page.screenshot({ path: path.join(process.env.SCREENSHOT_DIR, `cleaner-${viewport.width}-${scheme}.png`) });
      }
      await ycc.context.close();
      checks++;
    }
  }

  const { context, page } = await fixture('live_chat_flusher', { width: 400, height: 700 }, 'light');
  await page.goto(`${origin}/live_chat_flusher/options/options.html`);
  const normal = await page.evaluate(async () => {
    const { NormalChatView } = await import('../modules/normal_chat_view.mjs');
    const view = new NormalChatView();
    view.setEnabled(true);
    view.setFontScale(140);
    document.body.replaceChildren(view.element);
    const item = document.createElement('div');
    item.id = 'member-message';
    item.className = 'member';
    item.innerHTML = '<div class="header"><a><img class="photo" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></a><span class="name">Member</span></div><span class="body">Hello <span class="emoji"><img alt=":member:" src="data:image/gif;base64,R0lGODlhAQABAAAAACw="></span></span>';
    view.add(item);
    view.setShowPhoto(false);
    view.setShowName(false);
    const node = view.list.firstElementChild;
    return {
      emoji: node.querySelector('.emoji img') !== null,
      photoHidden: getComputedStyle(node.querySelector('.photo')).display === 'none',
      nameHidden: getComputedStyle(node.querySelector('.name')).display === 'none',
      textColor: getComputedStyle(node.querySelector('.body')).color,
      font: view.element.style.getPropertyValue('--yt-lcf-normal-chat-font-size'),
    };
  });
  assert(normal.emoji && normal.photoHidden && normal.nameHidden);
  assert.equal(normal.font, '34px');
  assert.equal(normal.textColor, 'rgb(22, 128, 42)');
  checks++;
  const replay = await page.evaluate(async () => {
    const { getReplayChatActionsAsyncIterable, getLiveChatActionsAsyncIterable } = await import('../modules/chat_actions.mjs');
    const requests = [];
    const originalFetch = window.fetch;
    window.fetch = async (url, options) => {
      const body = JSON.parse(options.body);
      requests.push({ body, authorization: options.headers.get('Authorization') });
      const seek = body.currentPlayerState?.playerOffsetMs === '100000';
      return {
        ok: true,
        json: async () => ({ continuationContents: { liveChatContinuation: {
          actions: [{ replayChatItemAction: { videoOffsetTimeMsec: seek ? '100000' : '0', actions: [] } }],
          continuations: [
            { liveChatReplayContinuationData: { continuation: 'next' } },
            { playerSeekContinuationData: { continuation: 'seek' } },
          ],
        } } }),
      };
    };
    try {
      const signal = new AbortController().signal;
      const replay = getReplayChatActionsAsyncIterable(signal, 'initial', 'fixture', undefined, { auth: false, offset: 100000 });
      const result = await replay.next();
      await replay.return();
      const live = getLiveChatActionsAsyncIterable(signal, 'live', { auth: false });
      await live.next();
      await live.return();
      return { result: result.value, requests };
    } finally { window.fetch = originalFetch; }
  });
  assert.equal(replay.result[0].replayChatItemAction.videoOffsetTimeMsec, '100000');
  assert.equal(replay.requests[1].body.currentPlayerState.playerOffsetMs, '100000');
  assert(replay.requests.every(r => r.authorization === null));
  checks++;
  await context.close();
  console.log(`PASS: ${checks} extension UI/migration/rendering scenarios`);
} finally {
  await browser.close();
  server.close();
}
