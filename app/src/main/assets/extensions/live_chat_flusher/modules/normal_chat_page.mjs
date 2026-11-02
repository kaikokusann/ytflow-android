import { logger } from './logging.mjs';
import { getLiveChatActionsAsyncIterable, getReplayChatActionsAsyncIterable } from './chat_actions.mjs';
import { NormalChatView } from './normal_chat_view.mjs';
import { store } from './store.mjs';
import { LiveChatItemFactory, renderChatItem } from './chat_message.mjs';

let started = false;

const NORMAL_CHAT_INTERVAL_KEY = 'ytlcf-app-normal-chat-interval-ms';
const NORMAL_CHAT_INTERVAL_ATTR = 'data-ytlcf-app-normal-chat-interval-ms';
const DEFAULT_NORMAL_CHAT_INTERVAL_MS = 100;
const MIN_NORMAL_CHAT_INTERVAL_MS = 50;
const MAX_QUEUE_GROUPS = 800;

export async function initializeNormalChatPage() {
	if (started || !isEnabled()) return;
	started = true;

	const view = new NormalChatView();
	const factory = new LiveChatItemFactory();
	await store.load();
	await factory.load();
	applyUrlSettings();
	document.documentElement.setAttribute('data-ytlcf-app-normal-chat-enabled', '1');
	view.syncFromStorage();
	view.setEnabled(true);
	document.body.append(view.element);
	installPageStyle();
	installSettingsSync(view);
	const pacer = new NormalChatActionPacer(view, factory);

	const searchParams = new URL(location.href).searchParams;
	const appParams = getAppParams();
	const continuation = searchParams.get('continuation');
	if (!continuation) return;
	const initialOffset = Number.parseInt(appParams.get('ytcc_chat_offset_ms') || '0', 10);

	const abortController = new AbortController();
	window.addEventListener('pagehide', () => abortController.abort(), { once: true });
	try {
		if (location.pathname === '/live_chat_replay') {
			for await (const containers of getReplayChatActionsAsyncIterable(abortController.signal, continuation, crypto.randomUUID(), undefined, {
				auth: false,
				offset: Number.isFinite(initialOffset) ? initialOffset : 0,
			})) {
				for (const container of containers || []) {
					pacer.enqueue(container.replayChatItemAction?.actions || []);
				}
			}
		} else {
			for await (const actions of getLiveChatActionsAsyncIterable(abortController.signal, continuation, { auth: false })) {
				pacer.enqueue(actions || []);
			}
		}
	} catch (error) {
		logger.warn('Normal chat page renderer stopped:', error);
	}
}

function applyUrlSettings() {
	const params = getAppParams();
	copyParamToStorageAndAttr(params, 'ytcc_app_normal_chat_font_scale', 'ytlcf-app-normal-chat-font-scale', 'data-ytlcf-app-normal-chat-font-scale');
	copyParamToStorageAndAttr(params, 'ytcc_app_normal_chat_interval_ms', NORMAL_CHAT_INTERVAL_KEY, NORMAL_CHAT_INTERVAL_ATTR);
	copyParamToStorageAndAttr(params, 'ytcc_app_normal_chat_show_name', 'ytlcf-app-normal-chat-show-name', 'data-ytlcf-app-normal-chat-show-name');
	copyParamToStorageAndAttr(params, 'ytcc_app_normal_chat_show_photo', 'ytlcf-app-normal-chat-show-photo', 'data-ytlcf-app-normal-chat-show-photo');
}

function copyParamToStorageAndAttr(params, paramName, storageKey, attrName) {
	const value = params.get(paramName);
	if (value == null) return;
	document.documentElement.setAttribute(attrName, value);
	try {
		localStorage.setItem(storageKey, value);
	} catch (_error) {}
}

function installSettingsSync(view) {
	const sync = () => view.syncFromStorage();
	window.addEventListener('storage', sync, { passive: true });
	window.addEventListener('ytlcf-normal-chat-change', sync, { passive: true });
	window.addEventListener('message', event => {
		if (event.data?.type === 'ytlcf-normal-chat-change') sync();
	}, { passive: true });
	new MutationObserver(sync).observe(document.documentElement, {
		attributes: true,
		attributeFilter: [
			'data-ytlcf-app-normal-chat-enabled',
			'data-ytlcf-app-normal-chat-font-scale',
			NORMAL_CHAT_INTERVAL_ATTR,
			'data-ytlcf-app-normal-chat-show-name',
			'data-ytlcf-app-normal-chat-show-photo',
		],
	});
}

function isEnabled() {
	try {
		return getAppParams().get('ytcc_app_chat_only') === '1';
	} catch (_error) {
		return false;
	}
}

function getAppParams() {
	const url = new URL(location.href);
	const params = new URLSearchParams(url.search);
	const hashParams = new URLSearchParams(url.hash.replace(/^#\??/, ''));
	for (const [key, value] of hashParams) {
		if (!params.has(key)) params.set(key, value);
	}
	return params;
}

function installPageStyle() {
	const id = 'yt-lcf-normal-chat-page-only-style';
	if (document.getElementById(id)) return;
	const style = document.createElement('style');
	style.id = id;
	style.textContent = `
		html, body {
			background: #fff !important;
			height: 100vh !important;
			margin: 0 !important;
			overflow: hidden !important;
			width: 100vw !important;
		}
		yt-live-chat-app,
		yt-live-chat-renderer {
			opacity: 0 !important;
			pointer-events: none !important;
		}
	`;
	document.documentElement.append(style);
}

class NormalChatActionPacer {
	constructor(view, factory) {
		this.view = view;
		this.factory = factory;
		/** @type {LiveChat.LiveChatItemAction[][]} */
		this.queue = [];
		this.running = false;
	}

	/**
	 * @param {LiveChat.LiveChatItemAction[]} actions
	 */
	enqueue(actions) {
		if (!Array.isArray(actions) || actions.length === 0) return;
		this.queue.push(actions);
		if (this.queue.length > MAX_QUEUE_GROUPS) {
			this.queue.splice(0, this.queue.length - MAX_QUEUE_GROUPS);
		}
		this.start();
	}

	async start() {
		if (this.running) return;
		this.running = true;
		try {
			while (this.queue.length > 0) {
				const actions = this.queue.shift() || [];
				await handleActions(actions, this.view, this.factory);
				const intervalMs = readIntervalMs();
				if (intervalMs > 0 && this.queue.length > 0) {
					await sleep(intervalMs);
				}
			}
		} finally {
			this.running = false;
			if (this.queue.length > 0) this.start();
		}
	}
}

function readIntervalMs() {
	try {
		const attrValue = document.documentElement.getAttribute(NORMAL_CHAT_INTERVAL_ATTR);
		if (attrValue != null) return normalizeIntervalMs(attrValue);
		const storageValue = localStorage.getItem(NORMAL_CHAT_INTERVAL_KEY);
		if (storageValue != null) return normalizeIntervalMs(storageValue);
	} catch (_error) {}
	return DEFAULT_NORMAL_CHAT_INTERVAL_MS;
}

function normalizeIntervalMs(value) {
	const number = Number.parseInt(String(value), 10);
	if (!Number.isFinite(number)) return DEFAULT_NORMAL_CHAT_INTERVAL_MS;
	return Math.min(1000, Math.max(MIN_NORMAL_CHAT_INTERVAL_MS, number));
}

function sleep(ms) {
	return new Promise(resolve => setTimeout(resolve, ms));
}

async function handleActions(actions, view, factory) {
	for (const action of actions) {
		if ('addChatItemAction' in action) {
			const item = action.addChatItemAction?.item;
			if (!item) continue;
			try {
				const element = await renderChatItem(item, factory);
				if (element) view.add(element);
			} catch (error) {
				logger.warn('Failed to render normal chat item:', error);
			}
			continue;
		}
		if ('markChatItemAsDeletedAction' in action) {
			view.delete(action.markChatItemAsDeletedAction?.targetItemId || '');
			continue;
		}
		if ('markChatItemsByAuthorAsDeletedAction' in action) {
			view.deleteByAuthor(action.markChatItemsByAuthorAsDeletedAction?.externalChannelId || '');
		}
	}
}
