import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const activity = readFileSync(new URL('../app/src/main/java/dev/ytchatplayer/app/MainActivity.kt', import.meta.url), 'utf8');
const script = activity.match(/val script = """\s*(if \(!window\.__ytcc_autosave_timer\)[\s\S]*?)\s*"""\.trimIndent\(\)/)?.[1];
assert.ok(script, 'The playback reporting script must exist');

function report({ live, historicalLive = false, responseId = 'archive', paused = false }) {
  const messages = [];
  const page = { location: { href: 'https://www.youtube.com/watch?v=archive&t=10s' } };
  vm.runInNewContext(script, {
    window: page,
    URL,
    document: {
      querySelector: selector => selector.includes('video')
        ? { paused, currentTime: 123.75 }
        : { getPlayerResponse: () => ({
          videoDetails: { videoId: responseId, isLiveContent: historicalLive },
          microformat: { playerMicroformatRenderer: { liveBroadcastDetails: { isLiveNow: live } } },
        }) },
    },
    alert: message => messages.push(message),
    setInterval: (callback, interval) => {
      assert.equal(interval, 5000, 'Reuse the existing autosave interval');
      callback();
      return 1;
    },
  });
  return messages.map(message => new URLSearchParams(message.substring('ytcc-save-state:'.length)));
}

assert.equal(report({ live: true })[0].get('live'), 'true');
assert.equal(report({ live: false, historicalLive: true })[0].get('live'), 'false');
assert.equal(report({})[0].get('live'), 'false');
assert.equal(report({ responseId: 'previous-video', live: true })[0].has('live'), false);
assert.equal(report({ paused: true }).length, 0);
const saved = report({ live: false })[0];
assert.equal(saved.get('t'), '123');
assert.equal(saved.get('url'), 'https://www.youtube.com/watch?v=archive');
console.log('PASS: playback report live/archive/SPA/paused/position checks');
