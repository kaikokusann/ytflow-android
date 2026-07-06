import { logger } from './logging.mjs';
import { store as s } from './store.mjs';
import { isNotPip, loadTemplateDocument, getColorRGB } from './utils.mjs';

import { LiveChatLayer, VideoSegmentationExecutor } from './chat_layer.mjs'
import { LiveChatPanel, WrapStyleDefinitions } from './chat_panel.mjs';
import { LiveChatContextMenu } from './chat_contextmenu.mjs';
import { LiveChatItemFactory, EmojiModeEnum, renderChatItem, updateMutedWordsList, updateTlExclusionList } from './chat_message.mjs';
import { LiveChatLayoutCache, layoutChatItem } from './chat_layout.mjs';

/** @enum {number} */
export const SimultaneousModeEnum = Object.freeze({
	ALL: 0,
	FIRST: 1,
	MERGE: 2,
	LAST_MERGE: 3,
});

const APP_NORMAL_CHAT_KEY = 'ytlcf-app-normal-chat-enabled';
const APP_NORMAL_CHAT_FONT_SCALE_KEY = 'ytlcf-app-normal-chat-font-scale';
const APP_NORMAL_CHAT_SHOW_NAME_KEY = 'ytlcf-app-normal-chat-show-name';
const APP_NORMAL_CHAT_SHOW_PHOTO_KEY = 'ytlcf-app-normal-chat-show-photo';
const APP_NORMAL_CHAT_ATTR = 'data-ytlcf-app-normal-chat-enabled';
const APP_NORMAL_CHAT_FONT_SCALE_ATTR = 'data-ytlcf-app-normal-chat-font-scale';
const APP_NORMAL_CHAT_SHOW_NAME_ATTR = 'data-ytlcf-app-normal-chat-show-name';
const APP_NORMAL_CHAT_SHOW_PHOTO_ATTR = 'data-ytlcf-app-normal-chat-show-photo';
const APP_NORMAL_CHAT_ACTIVE_CLASS = 'ytlcf-app-normal-chat-active';
const NORMAL_CHAT_PHOTO_PARTS = Object.freeze([
	['normal', 'normal'],
	['member', 'member'],
	['moderator', 'moderator'],
	['owner', 'owner'],
	['verified', 'verified'],
	['superchat', 'paid_message'],
	['supersticker', 'paid_sticker'],
	['milestone', 'milestone'],
	['membership', 'membership'],
]);

export class NormalChatView {
	/** @type {HTMLDivElement} */
	element;
	/** @type {ShadowRoot} */
	root;
	/** @type {HTMLDivElement} */
	list;
	enabled = false;
	limit = 60;
	showName = true;
	showPhoto = true;

	constructor() {
		this.element = document.createElement('div');
		this.element.id = 'yt-lcf-normal-chat';
		Object.assign(this.element.style, {
			background: '#fff',
			boxSizing: 'border-box',
			color: '#0f0f0f',
			display: 'none',
			fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
			fontSize: 'var(--yt-lcf-normal-chat-font-size)',
			inset: '0',
			lineHeight: '1.45',
			overflow: 'hidden',
			padding: '14px 14px 18px',
			position: 'fixed',
			zIndex: '2147483646',
		});
		this.root = this.element.attachShadow({ mode: 'closed' });
		const style = document.createElement('style');
		style.textContent = `
			.list {
				box-sizing: border-box;
				display: flex;
				flex-direction: column;
				gap: 8px;
				font-size: var(--yt-lcf-normal-chat-font-size);
				height: 100%;
				justify-content: flex-end;
				overflow: hidden;
				width: 100%;
			}
			.list > div {
				animation: none !important;
				box-sizing: border-box;
				color: #0f0f0f;
				left: auto !important;
				line-height: 1.45;
				max-width: none !important;
				opacity: 1 !important;
				overflow: visible;
				padding: 7px 6px;
				position: static !important;
				text-shadow: none !important;
				text-overflow: clip;
				transform: none !important;
				-webkit-text-stroke: 0 !important;
				white-space: normal;
				width: 100%;
				z-index: auto !important;
			}
			.text .header,
			.text .body {
				display: inline;
			}
			.header {
				align-items: center;
			}
			a {
				color: inherit;
				pointer-events: none;
				text-decoration: none;
			}
			.photo {
				border-radius: 50%;
				height: 1.4em !important;
				margin-right: .35em;
				max-height: 1.4em !important;
				max-width: 1.4em !important;
				object-fit: cover !important;
				vertical-align: -.32em;
				width: 1.4em !important;
			}
			:host(.hide-photo) .photo {
				display: none !important;
			}
			.normal-chat-photo-hidden .photo {
				display: none !important;
			}
			.normal-chat-image-placeholder {
				background: #f1f3f4;
				border-radius: .35em;
				color: #5f6368;
				display: inline-block;
				font-size: .8em;
				padding: .05em .35em;
				vertical-align: .05em;
			}
				.name {
					color: #16802a;
					font-weight: 750;
					margin-right: .35em;
				}
				:host(.hide-name) .name,
				.normal-chat-name-hidden .name {
					display: none !important;
				}
				.body {
					color: #111;
					overflow: visible;
				}
			img:not(.photo),
			svg {
				height: auto !important;
				max-height: 2.8em !important;
				max-width: min(45vw, 12em) !important;
				object-fit: contain;
				vertical-align: -.18em;
				width: auto !important;
			}
			.emoji img,
			.body svg,
			.open_in_new svg {
				height: 1.15em !important;
				max-height: 1.15em !important;
				max-width: 1.15em !important;
				width: 1.15em !important;
			}
			.superchat,
			.supersticker,
			.membership,
			.gift,
			.engagement-poll {
				border-radius: 10px;
				overflow: hidden;
				padding: 0 !important;
			}
			.superchat .header,
			.supersticker .header,
			.membership .header {
				display: flex;
				padding: 8px 10px 4px;
			}
			.superchat .body,
			.supersticker .body,
			.membership .body,
			.engagement-poll .body {
				display: block;
				padding: 4px 10px 9px;
			}
		`;
		this.list = document.createElement('div');
		this.list.className = 'list';
		this.root.append(style, this.list);
		this.syncFromStorage();
	}

	syncFromStorage() {
		this.setEnabled(readBooleanAppFlag(APP_NORMAL_CHAT_ATTR, APP_NORMAL_CHAT_KEY, false));
		this.setFontScale(readNumberAppFlag(APP_NORMAL_CHAT_FONT_SCALE_ATTR, APP_NORMAL_CHAT_FONT_SCALE_KEY, 180));
		this.setShowName(readBooleanAppFlag(APP_NORMAL_CHAT_SHOW_NAME_ATTR, APP_NORMAL_CHAT_SHOW_NAME_KEY, true));
		this.setShowPhoto(readBooleanAppFlag(APP_NORMAL_CHAT_SHOW_PHOTO_ATTR, APP_NORMAL_CHAT_SHOW_PHOTO_KEY, true));
		this.refreshDisplaySettings();
	}

	/**
	 * @param {boolean} enabled
	 */
	setEnabled(enabled) {
		this.enabled = enabled;
		this.element.classList.toggle('enabled', enabled);
		this.element.style.display = enabled ? 'block' : 'none';
		if (!enabled) this.clear();
	}

	/**
	 * @param {number} scale
	 */
	setFontScale(scale) {
		const normalized = Math.min(300, Math.max(70, Number.isFinite(scale) ? scale : 180));
		const px = Math.round(24 * normalized / 100);
		this.element.style.setProperty('--yt-lcf-normal-chat-font-size', `${px}px`);
	}

	/**
	 * @param {boolean} showName
	 */
	setShowName(showName) {
		this.showName = showName;
		this.element.classList.toggle('hide-name', !showName);
	}

	/**
	 * @param {boolean} showPhoto
	 */
	setShowPhoto(showPhoto) {
		this.showPhoto = showPhoto;
		this.element.classList.toggle('hide-photo', !showPhoto);
	}

	/**
	 * @param {HTMLElement} source
	 */
	add(source) {
		if (!this.enabled) return;
		const existing = source.id ? this.root.getElementById(source.id) : null;
		existing?.remove();
		const item = /** @type {HTMLElement} */ (source.cloneNode(true));
		item.removeAttribute('style');
		this.removeLargeImages(item);
		this.applyDisplaySettings(item);
		this.list.append(item);
		this.trim();
	}

	/**
	 * @param {string} id
	 */
	delete(id) {
		if (!id) return false;
		const target = this.root.getElementById(id);
		target?.remove();
		return Boolean(target);
	}

	/**
	 * @param {string} id
	 */
	deleteByAuthor(id) {
		if (!id) return false;
		let removed = false;
		for (const item of this.root.querySelectorAll(`[data-author-id="${CSS.escape(id)}"]`)) {
			item.remove();
			removed = true;
		}
		return removed;
	}

	clear() {
		this.list.replaceChildren();
	}

	refreshDisplaySettings() {
		for (const item of this.list.children) {
			this.applyDisplaySettings(/** @type {HTMLElement} */ (item));
		}
	}

	/**
	 * @param {HTMLElement} item
	 */
	applyDisplaySettings(item) {
		const photoPart = NORMAL_CHAT_PHOTO_PARTS.find(([className]) => item.classList.contains(className))?.[1];
		const showByFlusher = photoPart ? s.parts[photoPart]?.photo !== false : true;
		item.classList.toggle('normal-chat-name-hidden', !this.showName);
		item.classList.toggle('normal-chat-photo-hidden', !this.showPhoto || !showByFlusher);
	}

	/**
	 * @param {HTMLElement} item
	 */
	removeLargeImages(item) {
		for (const node of item.querySelectorAll('picture, video, iframe, canvas, svg image, yt-img-shadow, yt-animated-image, ytd-thumbnail, ytd-moving-thumbnail-renderer')) {
			const placeholder = document.createElement('span');
			placeholder.className = 'normal-chat-image-placeholder';
			placeholder.textContent = '[画像]';
			node.replaceWith(placeholder);
		}
		for (const node of item.querySelectorAll('[style*="background-image"]')) {
			if (node.closest('.header')) continue;
			node.removeAttribute('style');
		}
		for (const image of item.querySelectorAll('img')) {
			const isAuthorPhoto = image.classList.contains('photo') && image.closest('.header');
			if (isAuthorPhoto) {
				image.removeAttribute('style');
				image.removeAttribute('width');
				image.removeAttribute('height');
				continue;
			}
			const emoji = image.closest('.emoji');
			if (emoji) {
				const label = image.alt || emoji.getAttribute('data-shortcut') || emoji.getAttribute('data-label') || '';
				emoji.replaceWith(label || '□');
				continue;
			}
			const placeholder = document.createElement('span');
			placeholder.className = 'normal-chat-image-placeholder';
			placeholder.textContent = '[画像]';
			image.replaceWith(placeholder);
		}
	}

	trim() {
		while (this.list.childElementCount > this.limit) {
			this.list.firstElementChild?.remove();
		}
	}
}

function readBooleanAppFlag(attrName, key, fallback) {
	try {
		const attrValue = document.documentElement.getAttribute(attrName);
		if (attrValue != null) return attrValue === '1';
		const value = localStorage.getItem(key);
		if (value == null) return fallback;
		return value === '1';
	} catch (_error) {
		return fallback;
	}
}

function readNumberAppFlag(attrName, key, fallback) {
	try {
		const attrValue = document.documentElement.getAttribute(attrName);
		if (attrValue != null) {
			const attrNumber = Number.parseInt(attrValue, 10);
			if (Number.isFinite(attrNumber)) return attrNumber;
		}
		const value = Number.parseInt(localStorage.getItem(key) || '', 10);
		return Number.isFinite(value) ? value : fallback;
	} catch (_error) {
		return fallback;
	}
}

export class LiveChatController {
	#skip = false;
	#isLive = false;
	#hiddenVideoStyles = new WeakMap();
	#videoHideTimer = 0;

	/** @type {?VideoSegmentationExecutor} */
	segmenter = null;

	/**
	 * @param {HTMLElement} player YouTube player element
	 */
	constructor(player) {
		this.player = player;

		this.layer = new LiveChatLayer(this);
		this.normalChat = new NormalChatView();
		const root = this.layer.root;
		this.layoutCache = new LiveChatLayoutCache(root);
		this.itemFactory = new LiveChatItemFactory();

		root.addEventListener('contextmenu', e => {
			/** @type {?HTMLElement} */
			const origin = /** @type {HTMLElement} */ (e.target).closest('[id]');
			if (origin && s.others.message_pause) {
				e.preventDefault();
				e.stopPropagation();
				if (origin.classList.contains('paused') && this.panel) {
					this.contextmenu.show(/** @type {MouseEvent} */ (e), origin, this.panel);
				} else {
					origin.classList.add('paused');
				}
			}
		}, { passive: false });
		root.addEventListener('click', e => {
			const origin = /** @type {HTMLElement} */ (e.target);
			const interactiveTags = ['A', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA'];
			if (interactiveTags.includes(origin?.tagName || 'BODY')) {
				e.stopPropagation();
			} else {
				/** @type {?HTMLElement} */ (e.target)?.parentElement?.click();
			}
		}, { passive: true });
		root.addEventListener('animationend', e => {
			const elem = /** @type {HTMLElement} */ (e.target);
			if (elem.parentNode === root) {
				this.layoutCache.delete(elem.id);
				elem.remove();
			}
		}, { passive: true });

		this.panel = new LiveChatPanel(this);
		this.contextmenu = new LiveChatContextMenu();
		this.abortController = new AbortController();
		this.#setupNormalChatPageStyle();
		const syncNormalChat = () => this.applyNormalChatSettings();
		window.addEventListener('storage', syncNormalChat, { passive: true });
		window.addEventListener('ytlcf-normal-chat-change', syncNormalChat, { passive: true });
		window.addEventListener('message', event => {
			if (event.data?.type === 'ytlcf-normal-chat-change') syncNormalChat();
		}, { passive: true });
		this.normalChatObserver = new MutationObserver(syncNormalChat);
		this.normalChatObserver.observe(document.documentElement, {
			attributes: true,
			attributeFilter: [
				APP_NORMAL_CHAT_ATTR,
				APP_NORMAL_CHAT_FONT_SCALE_ATTR,
				APP_NORMAL_CHAT_SHOW_NAME_ATTR,
				APP_NORMAL_CHAT_SHOW_PHOTO_ATTR,
			],
		});
	}

	#setupNormalChatPageStyle() {
		const id = 'yt-lcf-normal-chat-page-style';
		if (document.getElementById(id)) return;
		const style = document.createElement('style');
		style.id = id;
		style.textContent = `
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS},
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} body {
				overflow: hidden !important;
			}
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} ytd-miniplayer,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} [class*="miniplayer"],
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} [class*="MiniPlayer"],
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} .ytp-miniplayer-ui,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} .ytp-player-minimized,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} ytd-player,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} #player,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} #player-container,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} #player-container-outer,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} #movie_player,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} .html5-video-player,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} .html5-video-container,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} .video-stream,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} video,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} .ytp-contextmenu,
			html.${APP_NORMAL_CHAT_ACTIVE_CLASS} .ytp-popup {
				display: none !important;
				opacity: 0 !important;
				pointer-events: none !important;
				visibility: hidden !important;
			}
		`;
		(document.head || document.documentElement).append(style);
	}

	async start() {
		/** @type {?HTMLVideoElement} */
		const video = this.player.querySelector('#movie_player video');
		const videoContainer = video?.parentElement;
		if (!video || !videoContainer) {
			return Promise.reject('No video container element found.');
		}

		document.getElementById(this.panel.element.id)?.remove();

		// get storage data
		await s.load();
		// create form
		await this.panel.createForm();

		document.getElementById(this.layer.element.id)?.remove();
		document.getElementById(this.normalChat.element.id)?.remove();
		if (s.others.disabled) this.layer.hide();
		videoContainer.after(this.layer.element);
		(document.body || document.documentElement).append(this.normalChat.element);

		const promises = [
			// fetching your channel ID and set styles for you
			this.#setupViewerStyle(),
			this.#setupSettingMenu(),
			this.itemFactory.load(),
		];

		updateMutedWordsList();
		updateTlExclusionList();

		this.#setupPanel();
		this.layer.element.style.cssText += '--yt-lcf-layer-css: below;' + s.styles.layer_css;
		await Promise.allSettled(promises);
		this.applyNormalChatSettings();
		this.#startSendingFrame(video);
	}

	async #setupViewerStyle() {
		const res = await fetch('/account_advanced');
		const text = await res.text();
		const matches = text.match(/"(UC[\w-]{22})"/);
		const channel = matches?.[1] || '';
		if (!channel) return;

		const style = this.layer.root.querySelector('#yourcss');
		if (!style) return;

		style.textContent = `\
[data-author-id="${channel}"] {
	color: var(--yt-lcf-you-color);
	:host(.has-you-name) &.text {
		background-color: var(--yt-live-chat-you-message-background-color);
		border-radius: .5em;
		padding: 0 .25em;
	}
	&.text .photo {
		display: var(--yt-lcf-you-display-photo);
	}
	&.text .name {
		display: var(--yt-lcf-you-display-name);
	}
	&.text .message {
		display: var(--yt-lcf-you-display-message);
	}
}`;
	}

	/**
	 * Adds setting menus to the video control.
	 */
	async #setupSettingMenu() {
		const ytpPanelMenu = this.player.querySelector('.ytp-settings-menu .ytp-panel-menu');
		if (!ytpPanelMenu) return;

		const doc = await loadTemplateDocument('../templates/panel_menu.html');
		const [checkbox, popupmenu, pipmenu] = doc.body.children;
		checkbox.setAttribute('aria-checked', s.others.disabled ? 'false' : 'true');
		checkbox.addEventListener('click', e => {
			const cb = /** @type {?HTMLElement} */ (e.currentTarget);
			if (!cb) return;
			const checked = cb.getAttribute('aria-checked') === 'true';
			cb.setAttribute('aria-checked', (!checked).toString());
			this.layer.clear();
			this.layer[checked ? 'hide' : 'show']();
			s.others.disabled = checked ? 1 : 0;
		}, { passive: true });
		popupmenu.addEventListener('click', () => {
			if (!this.panel) return;
			this.panel[this.panel.element.hidden ? 'show' : 'hide']();
		}, { passive: true });
		ytpPanelMenu.querySelector('#' + checkbox.id)?.remove();
		ytpPanelMenu.querySelector('#' + popupmenu.id)?.remove();
		ytpPanelMenu.querySelector('#' + pipmenu.id)?.remove();
		ytpPanelMenu.append(checkbox, popupmenu, pipmenu);
	}

	async #setupPanel() {
		const le = this.layer.element;
		le.after(this.panel.element);

		const form = this.panel.form;
		if (!form) return;
		this.#setupDynamicControls(form);
		this.#applySettingsToControls(form);
		this.#applyInitialStyles(form);
	}

	/**
	 * @param {HTMLFormElement} form
	 */
	#setupDynamicControls(form) {
		const ctrls = form.elements;
		const marker = form.querySelector('#language_exception_marker');
		if (ctrls.translation && marker) {
			const options = navigator.languages.map((lang, i) => new Option(lang, `${i + 1}`));
			/** @type {HTMLSelectElement} */ (ctrls.translation).append(...options);
			const checkboxes = navigator.languages.map((lang, i) => {
				const label = document.createElement('label');
				const input = document.createElement('input');
				input.type = 'checkbox';
				input.name = 'except_lang';
				input.value = i.toString();
				const span = document.createElement('span');
				span.textContent = lang;
				label.append(input, span);
				return label;
			});
			marker.after(...checkboxes);
		}
	}

	/**
	 * @param {HTMLFormElement} form
	 */
	#applySettingsToControls(form) {
		const le = this.layer.element;
		const ctrls = form.elements;
		for (const select of form.querySelectorAll('select')) {
			if (select.name in s.others) {
				const name = /** @type {keyof typeof s.others} */ (select.name);
				const val = s.others[name];
				select.selectedIndex = Math.abs(val);
				if (name === 'emoji') {
					le.setAttribute('data-emoji', Object.keys(EmojiModeEnum)[val].toLowerCase());
				} else if (name === 'wrap') {
					const wrapStyle = WrapStyleDefinitions[val];
					le.style.setProperty('--yt-lcf-message-hyphens', wrapStyle.hyphens);
					le.style.setProperty('--yt-lcf-message-word-break', wrapStyle.wordBreak);
					le.style.setProperty('--yt-lcf-message-white-space', wrapStyle.whiteSpace);
					le.style.setProperty('--yt-lcf-max-width', s.styles.max_width);
				}
			} else if (select.name === 'muted_words_mode') {
				select.selectedIndex = s.mutedWords.mode;
			}
		}
		const checkboxes = /** @type {NodeListOf<HTMLInputElement>} */ (form.querySelectorAll('input[type="checkbox"]'));
		for (const cb of checkboxes) {
			const [_, _type] = cb.name.match(/^(.+)_display$/) || [];
			if (_type in s.parts) this.#applyPartSetting(cb, /** @type {keyof typeof s.parts} */ (_type));
			else this.#applyCheckboxSetting(cb, ctrls);
		}

		for (const [prop, value] of Object.entries(s.styles)) {
			le.style.setProperty(`--yt-lcf-${prop.replace(/_/g, '-')}`, value);
			/** @type {?HTMLInputElement} */
			const input = form.querySelector(`input.styles[name="${prop}"]`);
			if (input) {
				if (input.type === 'number') input.valueAsNumber = Number.parseFloat(value);
				else input.value = value;
			}
		}

		const rect = le.getBoundingClientRect();
		if (/** @type {HTMLInputElement} */ (ctrls.speed).checked) {
			/** @type {HTMLInputElement} */ (ctrls.px_per_sec).valueAsNumber = s.others.px_per_sec;
		} else if (rect.width > 0) {
			const dur = /** @type {HTMLInputElement} */ (ctrls.animation_duration).valueAsNumber;
			/** @type {HTMLInputElement} */ (ctrls.px_per_sec).valueAsNumber = Math.round(rect.width / dur);
		}
		/** @type {HTMLInputElement} */ (ctrls.limit_number).valueAsNumber = s.others.limit || 100;
		/** @type {HTMLInputElement} */ (ctrls.container_limit_number).valueAsNumber = s.others.container_limit || 20;

		const lines = s.others.number_of_lines;
		const inputLineNum = /** @type {HTMLInputElement} */ (ctrls.number_of_lines);
		if (rect.height > 0) {
			if (lines > 0) {
				const sizeByLines = (rect.height / lines / Number.parseFloat(s.styles.line_height)) | 0;
				const fsVal = s.others.type_of_lines > 0 ? `max(${s.styles.font_size}, ${sizeByLines}px)` : `${sizeByLines}px`;
				le.style.setProperty('--yt-lcf-font-size', fsVal);
				inputLineNum.valueAsNumber = lines;
				this.layoutCache.resize(lines);
			} else {
				const linesBySize = (rect.height / Number.parseFloat(s.styles.font_size) / Number.parseFloat(s.styles.line_height)) | 0;
				le.style.setProperty('--yt-lcf-font-size', s.styles.font_size);
				inputLineNum.valueAsNumber = linesBySize;
				this.layoutCache.resize(linesBySize);
			}
		}

		/** @type {HTMLInputElement} */ (ctrls.time_shift).valueAsNumber = s.others.time_shift || 0;
		/** @type {HTMLInputElement} */ (ctrls.time_shift).disabled = s.others.mode_replay === 0;
	}

	/**
	 * @param {HTMLInputElement} cb
	 * @param {keyof typeof s.parts} type
	 */
	#applyPartSetting(cb, type) {
		const le = this.layer.element;
		const kebab = type.replace(/_/g, '-');
		const part = s.parts[type];
		switch (cb.value) {
			case 'color': if ('color' in part) {
				cb.checked = part.color + part.strokeColor !== '';
				const fillProp = `--yt-lcf-${kebab}-color`;
				const strokeProp = `--yt-lcf-${kebab}-stroke-color`;
				if (part.color) le.style.setProperty(fillProp, part.color);
				else le.style.removeProperty(fillProp);
				if (part.strokeColor) le.style.setProperty(strokeProp, part.strokeColor);
				else le.style.removeProperty(strokeProp);

				const computed = getComputedStyle(le);
				const fillPicker = /** @type {?HTMLInputElement} */ (cb.parentElement?.nextElementSibling);
				if (fillPicker) fillPicker.value = part.color || computed.getPropertyValue(fillProp) || '#ffffff';
				const strokePicker = /** @type {?HTMLInputElement} */ (fillPicker?.nextElementSibling);
				if (strokePicker) strokePicker.value = part.strokeColor || computed.getPropertyValue(strokeProp) || '#000000';
				break;
			}
			// biome-ignore lint/suspicious/noFallthroughSwitchClause: To use default case
			case 'name': {
				const div = /** @type {HTMLDivElement} */ (cb.closest('div'));
				if (part.name) div.classList.add('outlined');
				cb.addEventListener('change', e => {
					const method = /** @type {HTMLInputElement} */ (e.target).checked ? 'add' : 'remove';
					div.classList[method]('outlined');
					le.classList[method](`has-${type}-name`);
				}, { passive: true });
			}
			default: {
				// @ts-expect-error
				cb.checked = part[cb.value];
				le.style.setProperty(`--yt-lcf-${kebab}-display-${cb.value}`, cb.checked ? 'inline' : 'none');
			}
		}
	}

	/**
	 *
	 * @param {HTMLInputElement} cb
	 * @param {HTMLFormControlsCollection} ctrls
	 */
	#applyCheckboxSetting(cb, ctrls) {
		const le = this.layer.element;
		switch (cb.name) {
			case 'speed': {
				cb.checked = s.others.px_per_sec > 0;
				/** @type {HTMLInputElement} */ (ctrls.animation_duration).disabled = cb.checked;
				/** @type {HTMLInputElement} */ (ctrls.px_per_sec).disabled = !cb.checked;
				break;
			}
			case 'lines': {
				cb.checked = s.others.number_of_lines > 0;
				/** @type {HTMLInputElement} */ (ctrls.font_size).disabled = cb.checked;
				/** @type {HTMLInputElement} */ (ctrls.number_of_lines).disabled = !cb.checked;
				/** @type {HTMLInputElement} */ (ctrls.type_of_lines).disabled = !cb.checked;
				break;
			}
			case 'unlimited': {
				/** @type {HTMLInputElement} */ (ctrls.limit_number).disabled = cb.checked = s.others.limit === 0;
				/** @type {LiveChatLayer} */ this.layer.limit = s.others.limit;
				break;
			}
			case 'container_unlimited': {
				/** @type {HTMLInputElement} */ (ctrls.container_limit_number).disabled = cb.checked = s.others.container_limit === 0;
				break;
			}
			case 'overlapping':
			case 'direction': {
				const val = Number.parseInt(cb.value, 10);
				cb.checked = !!(s.others[cb.name] & 1 << val);
				break;
			}
			case 'show_username': {
				cb.checked = s.others.show_username > 0;
				break;
			}
			case 'muted_words_regexp': {
				cb.checked = s.mutedWords.regexp;
				break;
			}
			case 'except_lang': {
				const val = Number.parseInt(cb.value, 10);
				cb.checked = !!(s.others.except_lang & 1 << val);
				const abs = Math.abs(s.others.translation);
				cb.disabled = abs === 0 || abs === val + 1;
				break;
			}
			case 'prefix_lang':
			case 'suffix_original': {
				cb.checked = cb.name === 'prefix_lang' ? s.others.translation < 0 : s.others.suffix_original > 0;
				cb.disabled = /** @type {HTMLSelectElement} */ (ctrls.translation).selectedIndex === 0;
				le.classList[cb.checked ? 'add' : 'remove'](cb.name);
				break;
			}
		}
	}

	/**
	 * @param {HTMLFormElement} form
	 */
	#applyInitialStyles(form) {
		const le = this.layer.element;
		const ctrls = form.elements;

		/** @type { [ string, number, number ][] } */
		const colormap = [
			['--yt-live-chat-normal-message-background-color', 0xffc0c0c0, -1],
			['--yt-live-chat-verified-message-background-color', 0xffc0c0c0, -1],
			['--yt-live-chat-member-message-background-color', 0xffc0c0c0, -1],
			['--yt-live-chat-moderator-message-background-color', 0xffc0c0c0, -1],
			['--yt-live-chat-owner-message-background-color', 0xffc0c0c0, -1],
			['--yt-live-chat-you-message-background-color', 0xffc0c0c0, -1],
			['--yt-live-chat-paid-sticker-background-color', 0xffffb300, -1],
			['--yt-live-chat-author-chip-owner-background-color', 0xffffd600, -1],
		];
		for (const [name, rgb, alpha] of colormap) {
			le.style.setProperty(name, `rgba(${getColorRGB(rgb).join()},${alpha < 0 ? 'var(--yt-lcf-background-opacity)' : alpha})`);
		}

		for (const [k, v] of Object.entries(s.parts)) {
			le.classList[v.name ? 'add' : 'remove'](`has-${k}-name`);
		}

		const root = this.layer.root;
		const customCss = root.querySelector('#customcss');
		const userDefinedCss = root.querySelector('#userdefinedcss');
		for (const [selector, css] of Object.entries(s.cssTexts)) {
			if (selector) {
				if (customCss) customCss.textContent += `:host>${selector}{${css}}`;
				const name = selector.substring(1) + '_css';
				const input = /** @type {?HTMLInputElement} */ (ctrls[name]);
				if (input) input.value = css;
			} else {
				if (userDefinedCss) userDefinedCss.textContent = css;
				const textarea = /** @type {?HTMLTextAreaElement} */ (ctrls.user_defined_css);
				if (textarea) textarea.value = css;
			}
		}
		const dir = s.others.direction;
		if (dir) {
			le.classList[dir & 1 ? 'add': 'remove']('direction-reversed-y');
			le.classList[dir & 2 ? 'add': 'remove']('direction-reversed-x');
		}
		const langIndex = s.others.translation;
		if (langIndex < 0) le.classList.add('prefix_lang');

		// layer CSS
		/** @type {HTMLInputElement} */ (ctrls.layer_css).value = s.styles.layer_css;

		/** @type {HTMLInputElement} */ (ctrls.muted_words_replacement).value = s.mutedWords.replacement;
		/** @type {HTMLTextAreaElement} */ (ctrls.muted_words_list).value = s.mutedWords.plainList.join('\n');
	}

	/**
	 * @param {HTMLVideoElement} video
	 */
	#startSendingFrame(video) {
		if (!s.others.person_detection) return;

		const canvas = document.createElement('canvas');
		const ctx = canvas.getContext('bitmaprenderer');
		if (!ctx) {
			logger.warn('ImageBitmapRenderingContext is not supported.');
			return;
		}
		canvas.id = 'yt-lcf-mask-canvas';
		canvas.style.pointerEvents = 'none';
		canvas.style.position = 'absolute';
		canvas.style.visibility = 'hidden';

		const [w, h] = VideoSegmentationExecutor.TARGET_SIZE;
		[canvas.width, canvas.height] = [w, h];
		const imageData = new ImageData(w, h);
		const u32data = new Uint32Array(imageData.data.buffer);
		const prevRecv = new Uint8ClampedArray(w * h);
		const localBuffer = new Uint8ClampedArray(w * h);
		let hasNew = false;

		this.segmenter = new VideoSegmentationExecutor(async res => {
			const data = res?.at(0)?.mask?.data;
			if (!data) return;
			localBuffer.set(data);
			hasNew = true;
		});
		this.segmenter.observe(video, this.layer);

		(async function renderLoop() {
			if (hasNew) {
				hasNew = false;
				const ALPHA_MASK = 0xFF000000 >>> 0;
				const UPPER_THRESHOLD = 160;
				const LOWER_THRESHOLD = 96;
				for (let i = 0, l = localBuffer.length; i < l; i++) {
					const curentVal = (prevRecv[i] + localBuffer[i]) >> 1;
					const prevVal = u32data[i] & 0xFF;
					const b = curentVal < LOWER_THRESHOLD ? 0 : curentVal > UPPER_THRESHOLD ? 255 : prevVal;
					u32data[i] = (ALPHA_MASK | (b << 16) | (b << 8) | b) >>> 0;
					prevRecv[i] = localBuffer[i];
				}
				const bitmap = await createImageBitmap(imageData);
				ctx.transferFromImageBitmap(bitmap);
				bitmap.close();
			}
			requestAnimationFrame(renderLoop);
		})();

		const le = this.layer.element;
		le.style.maskImage = `linear-gradient(#fff, #fff), -moz-element(#${canvas.id})`;
		le.style.maskMode = 'luminance';
		le.style.maskPosition = `0px 0px, ${video.style.left} ${video.style.top}`;
		le.style.maskSize = `100% 100%, ${video.style.width} ${video.style.height}`;
		le.style.maskRepeat = 'no-repeat, no-repeat';
		le.style.maskComposite = 'exclude';
		le.after(canvas);
	}

	/**
	 * @param {boolean} isLive
	 */
	setPlaybackKind(isLive) {
		this.#isLive = isLive;
		this.applyNormalChatSettings();
	}

	applyNormalChatSettings() {
		this.normalChat.syncFromStorage();
		document.documentElement.classList.toggle(APP_NORMAL_CHAT_ACTIVE_CLASS, this.normalChat.enabled);
		if (this.normalChat.enabled) {
			this.#resetPageScrollForNormalChat();
			this.#startHidingVideoSurfaces();
			this.layer.hide();
			this.layoutCache.clear();
		} else {
			this.#stopHidingVideoSurfaces();
			if (!s.others.disabled) this.layer.show();
			return;
		}
		const video = /** @type {?HTMLVideoElement} */ (this.player.querySelector('#movie_player video') || document.querySelector('#movie_player video') || document.querySelector('video'));
		const player = document.querySelector('#movie_player');
		if (this.#isLive) {
			try {
				if (player && typeof player.pauseVideo === 'function') {
					player.pauseVideo();
					return;
				}
			} catch (_error) {
				// Fall back to the media element below.
			}
			try { video?.pause(); } catch (_error) {}
		} else {
			try {
				if (player && typeof player.playVideo === 'function') {
					player.playVideo();
					return;
				}
			} catch (_error) {
				// Fall back to the media element below.
			}
			try { video?.play(); } catch (_error) {}
		}
	}

	#startHidingVideoSurfaces() {
		this.#hideVideoSurfaces();
		if (this.#videoHideTimer) return;
		this.#videoHideTimer = window.setInterval(() => this.#hideVideoSurfaces(), 500);
	}

	#stopHidingVideoSurfaces() {
		if (this.#videoHideTimer) {
			clearInterval(this.#videoHideTimer);
			this.#videoHideTimer = 0;
		}
		for (const element of this.#videoSurfaceElements()) {
			const original = this.#hiddenVideoStyles.get(element);
			if (original == null) continue;
			element.style.cssText = original;
			this.#hiddenVideoStyles.delete(element);
			if (element instanceof HTMLVideoElement) {
				element.removeAttribute('width');
				element.removeAttribute('height');
			}
		}
	}

	#hideVideoSurfaces() {
		for (const element of this.#videoSurfaceElements()) {
			if (!this.#hiddenVideoStyles.has(element)) {
				this.#hiddenVideoStyles.set(element, element.style.cssText);
			}
			element.style.setProperty('display', 'none', 'important');
			element.style.setProperty('visibility', 'hidden', 'important');
			element.style.setProperty('opacity', '0', 'important');
			element.style.setProperty('pointer-events', 'none', 'important');
			element.style.setProperty('position', 'fixed', 'important');
			element.style.setProperty('left', '-10000px', 'important');
			element.style.setProperty('top', '-10000px', 'important');
			element.style.setProperty('width', '1px', 'important');
			element.style.setProperty('height', '1px', 'important');
			element.style.setProperty('transform', 'translate(-10000px, -10000px) scale(0.01)', 'important');
			if (element instanceof HTMLVideoElement) {
				element.setAttribute('width', '1');
				element.setAttribute('height', '1');
			}
		}
	}

	#videoSurfaceElements() {
		return document.querySelectorAll([
			'video',
			'.video-stream',
			'.html5-video-player',
			'.html5-video-container',
			'#movie_player',
			'#player',
			'#player-container',
			'#player-container-outer',
			'ytd-player',
			'ytd-miniplayer',
			'[class*="miniplayer"]',
			'[class*="MiniPlayer"]',
		].join(','));
	}

	#resetPageScrollForNormalChat() {
		try {
			window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
		} catch (_error) {
			try { window.scrollTo(0, 0); } catch (_error2) {}
		}
		for (const scroller of [
			document.scrollingElement,
			document.documentElement,
			document.body,
			document.querySelector('ytd-app'),
			document.querySelector('#content'),
			document.querySelector('#page-manager'),
		]) {
			try {
				if (scroller && 'scrollTop' in scroller) scroller.scrollTop = 0;
			} catch (_error) {}
		}
		for (const scroller of document.querySelectorAll('*')) {
			try {
				if (scroller.scrollTop > 0) scroller.scrollTop = 0;
			} catch (_error) {}
		}
	}

	/**
	 * Fires chat actions.
	 * @param {CustomEvent<LiveChat.LiveChatItemAction[]>} event
	 */
	async #onAction(event) {
		const le = this.layer.element;
		const root = this.layer.root;
		const normalChatEnabled = this.normalChat.enabled;
		if ((isNotPip() && document.visibilityState === 'hidden') || (!normalChatEnabled && (le.hidden || le.parentElement?.classList.contains('paused-mode')))) return;

		/** @type {Record<string, LiveChat.LiveChatItemAction[]>} */
		const filtered = { add: [], delete: [], delete_author: [], replace: [] };
		for (const a of event.detail) {
			if ('addChatItemAction' in a) filtered.add.push(a);
			else if ('markChatItemAsDeletedAction' in a) filtered.delete.push(a);
			else if ('markChatItemsByAuthorAsDeletedAction' in a) filtered.delete_author.push(a);
			else if ('replaceChatItemAction' in a) filtered.replace.push(a);
		};
		if (this.#skip && filtered.add.length > 0) {
			this.#skip = false;
			return;
		}

		// Add
		const sv = s.others.simultaneous;
		const last = sv === SimultaneousModeEnum.LAST_MERGE ? /** @type {?HTMLElement} */ (root.lastElementChild) : null;
		const bodies = last ? [ `<!-- ${last.className} -->` + (last.getAttribute('data-text') || '') ] : [];
		const ids = last ? [last.id] : [];
		/** @type {(el: HTMLElement) => void} */
		let merge = el => void el;
		switch (s.others.simultaneous) {
			case SimultaneousModeEnum.FIRST: {
				// @ts-expect-error
				const notext = filtered.add.slice(1).filter(a => !a.addChatItemAction?.item.liveChatTextMessageRenderer);
				filtered.add.splice(1, Infinity, ...notext);
				break;
			}
			case SimultaneousModeEnum.LAST_MERGE:
			case SimultaneousModeEnum.MERGE: {
				merge = el => {
					const text = el.getAttribute('data-text');
					const body = text ? `<!-- ${el.className} -->${text}` : '';
					if (!body) return;
					const index = bodies.indexOf(body);
					if (index < 0) {
						bodies.push(body);
						ids.push(el.id);
					} else {
						const earlier = root.getElementById(ids[index]);
						/** @type {?HTMLElement | undefined} */
						const _name = earlier?.querySelector('.name');
						/** @type {?HTMLSpanElement} */
						const _photo = el.querySelector('.photo');
						if (earlier && _name && _photo) {
							const parent = _photo.parentElement;
							if (parent) _name.insertAdjacentElement('beforebegin', parent);
							if (!_name.textContent)  _name.textContent = '';
							this.updateCurrentItem(earlier);
							return;
						}
					}
				};
				break;
			}
		}
		for (const a of filtered.add) this.#addChatItem(a, merge);
		for (const a of filtered.delete) this.#deleteChatItem(a);
		for (const a of filtered.delete_author) this.#deleteChatItemByAuthor(a);
		for (const a of filtered.replace) this.#replaceChatItem(a);
	}

	/**
	 * @param {LiveChat.LiveChatItemAction} action add chat item action object
	 * @param {(el: HTMLElement) => void} callback
	 */
	#addChatItem(action, callback) {
		const item = action.addChatItemAction?.item;
		if (!item) {
			logger.warn('Failed to add message.');
			return;
		}
		return renderChatItem(item, this.itemFactory).then(el => {
			if (!el) return;
			this.normalChat.add(el);
			if (this.normalChat.enabled) return;
			callback(el);
			if (this.layer.root.getElementById(el.id)) {
				logger.debug('Skipped rendering chat item (already exists on the layer):', `#${el.id}`);
			} else {
				/** @type { ["dense", "random"] } */
				const modeOptions = ['dense', 'random'];
				layoutChatItem(el, this.layoutCache, modeOptions[s.others.density]);
			}
		}).catch(logger.warn);
	}

	/**
	 * @param {LiveChat.LiveChatItemAction} action
	 */
	#deleteChatItem(action) {
		// @ts-expect-error
		const id = action.markChatItemAsDeletedAction.targetItemId;
		const deletedFromNormalChat = this.normalChat.delete(id);
		if (this.layoutCache.delete(id).some(v => v)) {
			const target = this.layer.root.getElementById(id);
			target?.remove();
		} else if (!deletedFromNormalChat) {
			logger.warn(`Failed to delete message: #${id}`);
		}
	}

	/**
	 * @param {LiveChat.LiveChatItemAction} action
	 */
	#deleteChatItemByAuthor(action) {
		// @ts-expect-error
		const id = action.markChatItemsByAuthorAsDeletedAction.externalChannelId;
		const deletedFromNormalChat = this.normalChat.deleteByAuthor(id);
		const targets = this.layer.root.querySelectorAll(`[data-author-id="${id}"]`);
		for (const target of targets) {
			this.layoutCache.delete(target.id);
			target.remove();
		}
		if (!deletedFromNormalChat && targets.length === 0) {
			logger.warn(`Failed to delete messages by author: #${id}`);
		}
	}

	/**
	 * @param {LiveChat.LiveChatItemAction} action
	 */
	#replaceChatItem(action) {
		// @ts-expect-error
		const id = action.replaceChatItemAction.targetItemId;
		const target = this.layer.root.getElementById(id);
		const item = action.replaceChatItemAction?.replacementItem;
		if ((target || this.normalChat.enabled) && item) {
			renderChatItem(item, this.itemFactory).then(el => {
				if (!el) return;
				this.normalChat.delete(id);
				this.normalChat.add(el);
				target?.replaceWith(el);
			}).catch(logger.warn);
		} else {
			logger.warn(`Failed to replace message: #${id}`);
		}
	}

	/**
	 * Updates the current style of the given item.
	 * @param {HTMLElement} item message element
	 */
	updateCurrentItem(item) {
		const lw = this.layer.element.clientWidth;
		const isLong = item.clientWidth >= lw * (Number.parseInt(s.styles.max_width, 10) / 100 || 1);
		item.classList[isLong ? 'add' : 'remove']('wrap');
		item.style.setProperty('--yt-lcf-translate-x', `-${lw + item.clientWidth}px`);
	}

	/**
	 * Sets a flag to skip rendering the messages once.
	 */
	skip() {
		this.#skip = true;
	}

	listen() {
		this.unlisten();
		document.addEventListener('ytlcf-action', e => {
			this.#onAction(e);
		}, { passive: true, signal: this.abortController.signal });
	}

	unlisten() {
		this.abortController.abort();
		this.abortController = new AbortController();
	}

	close() {
		this.unlisten();
		this.normalChatObserver?.disconnect();
		this.#stopHidingVideoSurfaces();
		document.documentElement.classList.remove(APP_NORMAL_CHAT_ACTIVE_CLASS);
		this.layer.clear();
		this.normalChat.clear();
	}
}
