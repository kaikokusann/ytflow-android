import { logger } from './logging.mjs';
import { getLiveChatActionsAsyncIterable, getReplayChatActionsAsyncIterable } from './chat_actions.mjs';
import { NormalChatView } from './chat_controller.mjs';
import { LiveChatItemFactory, renderChatItem } from './chat_message.mjs';

let started = false;

export async function initializeNormalChatPage() {
	if (started || !isEnabled()) return;
	started = true;

	const view = new NormalChatView();
	const factory = new LiveChatItemFactory();
	await factory.load();
	applyUrlSettings();
	document.documentElement.setAttribute('data-ytlcf-app-normal-chat-enabled', '1');
	view.syncFromStorage();
	view.setEnabled(true);
	document.body.append(view.element);
	installPageStyle();
	installSettingsSync(view);

	document.addEventListener('ytlcf-action', event => {
		handleActions(event.detail || [], view, factory);
	}, { passive: true });

	const params = new URL(location.href).searchParams;
	const continuation = params.get('continuation');
	if (!continuation) return;
	const initialOffset = Number.parseInt(params.get('ytcc_chat_offset_ms') || '0', 10);

	const abortController = new AbortController();
	try {
		if (location.pathname === '/live_chat_replay') {
			for await (const containers of getReplayChatActionsAsyncIterable(abortController.signal, continuation, {
				auth: false,
				offset: Number.isFinite(initialOffset) ? initialOffset : 0,
			})) {
				const actions = [];
				for (const container of containers || []) {
					actions.push(...(container.replayChatItemAction?.actions || []));
				}
				handleActions(actions, view, factory);
			}
		} else {
			for await (const actions of getLiveChatActionsAsyncIterable(abortController.signal, continuation, { auth: false })) {
				handleActions(actions || [], view, factory);
			}
		}
	} catch (error) {
		logger.warn('Normal chat page renderer stopped:', error);
	}
}

function applyUrlSettings() {
	const params = new URL(location.href).searchParams;
	copyParamToStorageAndAttr(params, 'ytcc_app_normal_chat_font_scale', 'ytlcf-app-normal-chat-font-scale', 'data-ytlcf-app-normal-chat-font-scale');
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
			'data-ytlcf-app-normal-chat-show-name',
			'data-ytlcf-app-normal-chat-show-photo',
		],
	});
}

function isEnabled() {
	try {
		return localStorage.getItem('ytcc-app-chat-only-enabled') === '1'
			|| new URL(location.href).searchParams.get('ytcc_app_chat_only') === '1';
	} catch (_error) {
		return false;
	}
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
