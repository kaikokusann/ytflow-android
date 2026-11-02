const APP_NORMAL_CHAT_FONT_SCALE_KEY = 'ytlcf-app-normal-chat-font-scale';
const APP_NORMAL_CHAT_SHOW_NAME_KEY = 'ytlcf-app-normal-chat-show-name';
const APP_NORMAL_CHAT_SHOW_PHOTO_KEY = 'ytlcf-app-normal-chat-show-photo';
const APP_NORMAL_CHAT_ATTR = 'data-ytlcf-app-normal-chat-enabled';
const APP_NORMAL_CHAT_FONT_SCALE_ATTR = 'data-ytlcf-app-normal-chat-font-scale';
const APP_NORMAL_CHAT_SHOW_NAME_ATTR = 'data-ytlcf-app-normal-chat-show-name';
const APP_NORMAL_CHAT_SHOW_PHOTO_ATTR = 'data-ytlcf-app-normal-chat-show-photo';

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
			.photo,
			[part="photo"] {
				border-radius: 50%;
				height: 1.4em !important;
				margin-right: .35em;
				max-height: 1.4em !important;
				max-width: 1.4em !important;
				object-fit: cover !important;
				vertical-align: -.32em;
				width: 1.4em !important;
			}
			:host(.hide-photo) .photo,
			:host(.hide-photo) [part="photo"],
			.normal-chat-photo-hidden .photo,
			.normal-chat-photo-hidden [part="photo"] {
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
				color: #5f6368;
				font-weight: 750;
				margin-right: .35em;
			}
			.normal .name {
				color: #5f6368;
			}
			.member .name,
			.membership .name,
			.milestone .name {
				color: var(--yt-live-chat-sponsor-color, #16802a);
			}
			.moderator .name {
				color: var(--yt-live-chat-moderator-color, #1a73e8);
			}
			.owner .name {
				color: var(--yt-live-chat-owner-color, #b77900);
			}
			.verified .name {
				color: var(--yt-live-chat-author-chip-verified-background-color, #606060);
			}
			:host(.hide-name) .name,
			.normal-chat-name-hidden .name {
				display: none !important;
			}
			.body {
				color: #111;
				overflow: visible;
			}
			:host(.hide-name) .normal .body {
				color: #111;
			}
			:host(.hide-name) .member .body {
				color: var(--yt-live-chat-sponsor-color, #16802a);
				font-weight: 750;
			}
			:host(.hide-name) .membership .body,
			:host(.hide-name) .milestone .body {
				color: var(--yt-live-chat-sponsor-color, #16802a);
				font-weight: 750;
			}
			:host(.hide-name) .moderator .body {
				color: var(--yt-live-chat-moderator-color, #1a73e8);
				font-weight: 750;
			}
			:host(.hide-name) .owner .body {
				color: var(--yt-live-chat-owner-color, #b77900);
				font-weight: 750;
			}
			:host(.hide-name) .verified .body {
				color: var(--yt-live-chat-author-chip-verified-background-color, #606060);
				font-weight: 750;
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
		this.setEnabled(document.documentElement.getAttribute(APP_NORMAL_CHAT_ATTR) === '1');
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
		this.refreshDisplaySettings();
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
		item.classList.toggle('normal-chat-name-hidden', !this.showName);
		item.classList.toggle('normal-chat-photo-hidden', !this.showPhoto);
		this.applyAuthorPhotoVisibility(item, this.showPhoto);
	}

	/**
	 * @param {HTMLElement} item
	 * @param {boolean} visible
	 */
	applyAuthorPhotoVisibility(item, visible) {
		const targets = new Set();
		for (const photo of item.querySelectorAll('.photo, [part="photo"]')) {
			const link = photo.closest('a');
			targets.add(link && link.children.length === 1 ? link : photo);
		}
		for (const target of targets) {
			if (!(target instanceof HTMLElement)) continue;
			target.toggleAttribute('hidden', !visible);
			target.setAttribute('aria-hidden', visible ? 'false' : 'true');
			if (visible) {
				target.style.removeProperty('display');
			} else {
				target.style.setProperty('display', 'none', 'important');
			}
		}
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
				image.removeAttribute('style');
				image.removeAttribute('width');
				image.removeAttribute('height');
				image.loading = 'lazy';
				image.decoding = 'async';
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
