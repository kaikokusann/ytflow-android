(() => {
  const CHAT_CLOSED_KEY = 'ytcc-router-chat-closed';
  const FULLSCREEN_ASSIST_KEY = 'ytcc-router-fullscreen-assist';
  const OPEN_VIDEO_PATH = '/ytcc-open-video';
  const APP_FLAG_PARAMS = {
    ytcc_app_ycc: 'ytcc-app-ycc-enabled',
    ytcc_app_lcf: 'ytcc-app-lcf-enabled',
    ytcc_app_chat_only: 'ytcc-app-chat-only-enabled',
    ytcc_app_fsassist: FULLSCREEN_ASSIST_KEY
  };
  const CHAT_CLOSED_CLASS = 'ytcc-chat-closed';
  const PIP_CLASS = 'ytcc-app-pip';
  const COMPACT_DESKTOP_CLASS = 'ytcc-compact-desktop';
  const CHAT_CSS = `
    html.${COMPACT_DESKTOP_CLASS}:not(.${PIP_CLASS}):not(.ytcc-app-fullscreen),
    html.${COMPACT_DESKTOP_CLASS}:not(.${PIP_CLASS}):not(.ytcc-app-fullscreen) body {
      overflow-x: hidden !important;
    }
    html.${COMPACT_DESKTOP_CLASS}:not(.${PIP_CLASS}):not(.ytcc-app-fullscreen) ytd-app {
      min-height: calc(100vh / 0.88) !important;
      transform: scale(0.88) !important;
      transform-origin: 0 0 !important;
      width: calc(100% / 0.88) !important;
    }
    html.${PIP_CLASS},
    html.${PIP_CLASS} body {
      background: #000 !important;
      height: 100vh !important;
      margin: 0 !important;
      overflow: hidden !important;
      padding: 0 !important;
      width: 100vw !important;
    }
    html.${PIP_CLASS} ytd-app,
    html.${PIP_CLASS} ytd-watch-flexy,
    html.${PIP_CLASS} ytd-watch-flexy #columns,
    html.${PIP_CLASS} ytd-watch-flexy #primary,
    html.${PIP_CLASS} ytd-watch-flexy #primary-inner,
    html.${PIP_CLASS} ytd-watch-flexy #player,
    html.${PIP_CLASS} ytd-watch-flexy #player-container,
    html.${PIP_CLASS} ytd-watch-flexy #player-container-inner,
    html.${PIP_CLASS} ytd-watch-flexy #player-theater-container,
    html.${PIP_CLASS} ytd-watch-flexy ytd-player,
    html.${PIP_CLASS} ytd-watch-flexy #ytd-player,
    html.${PIP_CLASS} ytd-watch-flexy #movie_player,
    html.${PIP_CLASS} ytd-watch-flexy .html5-video-player,
    html.${PIP_CLASS} ytd-watch-flexy .html5-video-container,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-player-content {
      box-sizing: border-box !important;
      height: 100vh !important;
      left: 0 !important;
      margin: 0 !important;
      max-height: 100vh !important;
      max-width: 100vw !important;
      min-height: 0 !important;
      min-width: 0 !important;
      padding: 0 !important;
      top: 0 !important;
      width: 100vw !important;
    }
    html.${PIP_CLASS} ytd-watch-flexy #player,
    html.${PIP_CLASS} ytd-watch-flexy #player-container,
    html.${PIP_CLASS} ytd-watch-flexy #player-container-inner,
    html.${PIP_CLASS} ytd-watch-flexy #movie_player,
    html.${PIP_CLASS} ytd-watch-flexy .html5-video-player,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-player-content {
      position: fixed !important;
    }
    html.${PIP_CLASS} ytd-watch-flexy .html5-main-video {
      height: 100vh !important;
      left: 0 !important;
      object-fit: contain !important;
      top: 0 !important;
      width: 100vw !important;
    }
    html.${PIP_CLASS} ytd-watch-flexy #yt-lcf-layer {
      contain: strict !important;
      display: block !important;
      font-size: clamp(13px, 2.2vh, 18px) !important;
      height: 100vh !important;
      left: 0 !important;
      max-height: 100vh !important;
      max-width: 100vw !important;
      opacity: var(--yt-lcf-layer-opacity, 1) !important;
      pointer-events: none !important;
      position: fixed !important;
      top: 0 !important;
      width: 100vw !important;
      z-index: 2147483647 !important;
    }
    html.${PIP_CLASS} #masthead-container,
    html.${PIP_CLASS} ytd-mini-guide-renderer,
    html.${PIP_CLASS} ytd-watch-flexy #secondary,
    html.${PIP_CLASS} ytd-watch-flexy #secondary-inner,
    html.${PIP_CLASS} ytd-watch-flexy #chat,
    html.${PIP_CLASS} ytd-watch-flexy ytd-live-chat-frame,
    html.${PIP_CLASS} ytd-watch-flexy ytd-engagement-panel-section-list-renderer[target-id*="chat"],
    html.${PIP_CLASS} ytd-watch-metadata,
    html.${PIP_CLASS} ytd-comments,
    html.${PIP_CLASS} #related,
    html.${PIP_CLASS} #below,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-chrome-top,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-chrome-bottom,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-chrome-controls,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-gradient-top,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-gradient-bottom,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-progress-bar-container,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-volume-panel,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-large-play-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-pause-overlay,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-spinner,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-bezel,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-left-controls,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-right-controls,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-cards-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-cards-teaser,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-iv-player-content,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-iv-video-content,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-show-cards-title,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-suggested-action,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-ce-element,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-ce-covering-overlay,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-endscreen-content,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-playlist-menu-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-miniplayer-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-next-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-prev-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-overflow-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-settings-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-subtitles-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-size-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-remote-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-fullscreen-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-play-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-mute-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-autonav-toggle-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-watermark,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-paid-content-overlay,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-tooltip,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-menuitem,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-popup,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-player-content .ytp-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-player-content .ytp-chrome-controls {
      display: none !important;
    }
    html.${PIP_CLASS} ytd-watch-flexy .html5-video-player > :not(.html5-video-container):not(#yt-lcf-layer),
    html.${PIP_CLASS} ytd-watch-flexy .ytp-player-content > :not(#yt-lcf-layer),
    html.${PIP_CLASS} ytd-watch-flexy .ytp-touch-feedback-shape,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-doubletap-ui,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-mobile-a11y-button,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-inline-preview-ui,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-cued-thumbnail-overlay,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-cued-thumbnail-overlay-image,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-ad-overlay-container,
    html.${PIP_CLASS} ytd-watch-flexy .ytp-flyout-cta,
    html.${PIP_CLASS} ytd-watch-flexy [class*="touch-feedback"],
    html.${PIP_CLASS} ytd-watch-flexy [class*="doubletap"],
    html.${PIP_CLASS} ytd-watch-flexy [class*="overlay-button"] {
      display: none !important;
      opacity: 0 !important;
      pointer-events: none !important;
      visibility: hidden !important;
    }
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #columns {
      display: block !important;
    }
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #primary,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #primary-inner,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-container,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-container-inner,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-theater-container,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy ytd-player,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #ytd-player,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #movie_player,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy .html5-video-player,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy .html5-video-container,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy .html5-main-video,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy .ytp-player-content,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy .ytp-chrome-bottom,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy .ytp-progress-bar-container {
      width: 100% !important;
      max-width: none !important;
      flex-basis: 100% !important;
    }
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy[fullscreen] #movie_player,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy[fullscreen] .html5-video-player {
      width: 100vw !important;
      max-width: 100vw !important;
    }
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy[fullscreen] .ytp-chrome-bottom {
      left: 12px !important;
      width: calc(100vw - 24px) !important;
    }
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy[fullscreen] .ytp-progress-bar-container {
      width: calc(100vw - 24px) !important;
    }
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS},
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} body {
      background: #000 !important;
      height: 100vh !important;
      margin: 0 !important;
      overflow: hidden !important;
      padding: 0 !important;
      width: 100vw !important;
    }
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-app,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #columns,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #primary,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #primary-inner,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-container,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-container-inner,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-theater-container,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy ytd-player,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #ytd-player,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #movie_player,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy .html5-video-player,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy .html5-video-container {
      box-sizing: border-box !important;
      height: 100vh !important;
      left: 0 !important;
      margin: 0 !important;
      max-height: 100vh !important;
      max-width: 100vw !important;
      min-height: 0 !important;
      min-width: 0 !important;
      padding: 0 !important;
      top: 0 !important;
      width: 100vw !important;
    }
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-container,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #player-container-inner,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy #movie_player,
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy .html5-video-player {
      position: fixed !important;
    }
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy .html5-main-video {
      height: 100vh !important;
      left: 0 !important;
      object-fit: contain !important;
      top: 0 !important;
      width: 100vw !important;
    }
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy .ytp-chrome-bottom {
      bottom: 0 !important;
      left: 12px !important;
      width: calc(100vw - 24px) !important;
    }
    html.ytcc-app-fullscreen.${CHAT_CLOSED_CLASS} ytd-watch-flexy .ytp-progress-bar-container {
      width: calc(100vw - 24px) !important;
    }
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #secondary,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #secondary-inner,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy #chat,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy ytd-live-chat-frame,
    html.${CHAT_CLOSED_CLASS} ytd-watch-flexy ytd-engagement-panel-section-list-renderer[target-id*="chat"] {
      display: none !important;
      width: 0 !important;
      min-width: 0 !important;
      max-width: 0 !important;
      flex-basis: 0 !important;
    }
  `;
  const VIDEO_PATHS = new Set(['/watch']);
  const MOBILE_PATH_PREFIXES = [
    '/feed/history',
    '/feed/subscriptions',
    '/feed/library',
    '/results',
    '/playlist',
    '/@',
    '/channel/',
    '/c/',
    '/user/'
  ];

  function isWatchLikePage() {
    return location.pathname === '/watch' || location.pathname.startsWith('/live/');
  }

  function isMobileBrowsingPage() {
    return location.hostname === 'm.youtube.com' && !isWatchLikePage();
  }

  function videoUrlFromClick(target) {
    if (!(target instanceof Element)) return null;
    const anchor = target.closest('a[href]');
    if (!anchor) return null;
    let url;
    try {
      url = new URL(anchor.href, location.href);
    } catch (_error) {
      return null;
    }
    if (!/(^|\.)youtube\.com$/.test(url.hostname)) return null;
    if (!(url.pathname === '/watch' || url.pathname.startsWith('/live/'))) return null;
    url.hostname = 'www.youtube.com';
    return url.toString();
  }

  function handOffVideoToApp(videoUrl) {
    const handoffUrl = new URL(`https://www.youtube.com${OPEN_VIDEO_PATH}`);
    handoffUrl.searchParams.set('url', videoUrl);
    location.href = handoffUrl.toString();
  }

  function installChatClosedStyle() {
    if (document.getElementById('ytcc-router-chat-style')) return;
    const style = document.createElement('style');
    style.id = 'ytcc-router-chat-style';
    style.textContent = CHAT_CSS;
    (document.head || document.documentElement).appendChild(style);
  }

  function setChatClosedLayout(enabled) {
    installChatClosedStyle();
    document.documentElement.classList.toggle(CHAT_CLOSED_CLASS, enabled && isWatchLikePage());
  }

  function setCompactDesktopLayout() {
    installChatClosedStyle();
    const enabled = location.hostname === 'www.youtube.com' && isWatchLikePage();
    document.documentElement.classList.toggle(COMPACT_DESKTOP_CLASS, enabled);
  }

  function rememberChatClosed(enabled) {
    try {
      if (enabled) sessionStorage.setItem(CHAT_CLOSED_KEY, '1');
      else sessionStorage.removeItem(CHAT_CLOSED_KEY);
    } catch (_error) {
      // sessionStorage can be unavailable in rare embedded states.
    }
    setChatClosedLayout(enabled);
  }

  function shouldTreatAsChatClose(target) {
    if (!(target instanceof Element)) return false;
    const button = target.closest('button, yt-button-shape, tp-yt-paper-icon-button, .yt-spec-button-shape-next');
    if (!button) return false;
    const chatContainer = button.closest('#chat, ytd-live-chat-frame, ytd-engagement-panel-section-list-renderer[target-id*="chat"]');
    if (!chatContainer) return false;

    const label = [
      button.getAttribute('aria-label'),
      button.getAttribute('title'),
      button.textContent
    ].filter(Boolean).join(' ');
    if (/(閉じる|close|hide|×|✕|✖)/i.test(label)) return true;

    const icon = button.querySelector('yt-icon, svg, path');
    return Boolean(icon && /close/i.test(icon.getAttribute('icon') || icon.getAttribute('d') || ''));
  }

  function shouldTreatAsChatOpen(target) {
    if (!(target instanceof Element)) return false;
    const button = target.closest('button, yt-button-shape, tp-yt-paper-button, .yt-spec-button-shape-next');
    if (!button) return false;
    const label = [
      button.getAttribute('aria-label'),
      button.getAttribute('title'),
      button.textContent
    ].filter(Boolean).join(' ');
    return /(チャット|chat|コメント|comments)/i.test(label);
  }

  function isFullScreenAssistEnabled() {
    try {
      return localStorage.getItem(FULLSCREEN_ASSIST_KEY) === '1';
    } catch (_error) {
      return false;
    }
  }

  function shouldTreatAsFullScreenButton(target) {
    if (!(target instanceof Element)) return false;
    return Boolean(target.closest('.ytp-fullscreen-button'));
  }

  function requestAssistedFullScreen() {
    const target =
      document.querySelector('#movie_player') ||
      document.querySelector('ytd-player') ||
      document.querySelector('#player-container') ||
      document.documentElement;
    setTimeout(() => {
      if (!document.fullscreenElement && target.requestFullscreen) {
        target.requestFullscreen().catch(() => {});
      }
    }, 80);
  }

  function restoreRememberedChatLayout() {
    let shouldClose = false;
    try {
      shouldClose = sessionStorage.getItem(CHAT_CLOSED_KEY) === '1';
    } catch (_error) {
      shouldClose = false;
    }
    setChatClosedLayout(shouldClose);
  }

  function shouldAcceptMessage(event) {
    if (event.origin !== 'https://www.youtube.com') return false;
    const data = event.data;
    if (!data || typeof data !== 'object') return false;
    return data.ytccAction === 'close-chat';
  }

  function targetFor(rawUrl) {
    const url = new URL(rawUrl);
    if (!/(^|\.)youtube\.com$/.test(url.hostname)) return null;
    if (url.pathname === OPEN_VIDEO_PATH) return null;
    if (url.pathname.startsWith('/live/') || VIDEO_PATHS.has(url.pathname)) {
      url.hostname = 'www.youtube.com';
      return url.toString();
    }
    if (
      url.pathname === '/' ||
      MOBILE_PATH_PREFIXES.some(prefix => url.pathname.startsWith(prefix))
    ) {
      url.hostname = 'm.youtube.com';
      if (url.pathname === '/') url.searchParams.delete('app');
      return url.toString();
    }
    return null;
  }

  function consumeAppFlagParams() {
    const url = new URL(location.href);
    let changed = false;
    for (const [param, key] of Object.entries(APP_FLAG_PARAMS)) {
      const value = url.searchParams.get(param);
      if (value === null) continue;
      try {
        localStorage.setItem(key, value === '0' ? '0' : '1');
      } catch (_error) {
        // Ignore storage failures and keep the current page usable.
      }
      url.searchParams.delete(param);
      changed = true;
    }
    if (changed) history.replaceState(history.state, document.title, url.toString());
    return changed;
  }

  function route() {
    consumeAppFlagParams();
    const target = targetFor(location.href);
    if (target && target !== location.href) {
      location.replace(target);
      return;
    }
    setCompactDesktopLayout();
    if (!isWatchLikePage()) {
      rememberChatClosed(false);
    } else {
      restoreRememberedChatLayout();
    }
  }

  document.addEventListener('click', event => {
    if (isMobileBrowsingPage()) {
      const videoUrl = videoUrlFromClick(event.target);
      if (videoUrl) {
        event.preventDefault();
        event.stopPropagation();
        event.stopImmediatePropagation();
        handOffVideoToApp(videoUrl);
        return;
      }
    }
    if (!isWatchLikePage()) return;
    if (isFullScreenAssistEnabled() && shouldTreatAsFullScreenButton(event.target)) {
      requestAssistedFullScreen();
      return;
    }
    if (shouldTreatAsChatClose(event.target)) {
      setTimeout(() => rememberChatClosed(true), 120);
      return;
    }
    if (shouldTreatAsChatOpen(event.target)) {
      rememberChatClosed(false);
    }
  }, true);

  addEventListener('message', event => {
    if (!shouldAcceptMessage(event)) return;
    if (!isWatchLikePage()) return;
    rememberChatClosed(true);
  });

  addEventListener('DOMContentLoaded', restoreRememberedChatLayout, { once: true });
  route();

  const pushState = history.pushState;
  const replaceState = history.replaceState;
  history.pushState = function (...args) {
    const result = pushState.apply(this, args);
    queueMicrotask(route);
    return result;
  };
  history.replaceState = function (...args) {
    const result = replaceState.apply(this, args);
    queueMicrotask(route);
    return result;
  };
  addEventListener('popstate', () => queueMicrotask(route), { passive: true });
  addEventListener('yt-navigate-finish', route, { passive: true });

  // SPAにおけるHistoryAPIフックの漏れを確実に防ぐためのURL監視ポーリング
  let lastUrl = location.href;
  setInterval(() => {
    if (location.href !== lastUrl) {
      lastUrl = location.href;
      route();
    }
  }, 100);

})();
