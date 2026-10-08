/* ==========================================================================
 * ui.js · toast / confirmDialog / html 模板 / esc 转义 / 全局横幅 / 按钮态
 * ========================================================================== */

import { describeBizError, ApiError } from './api.js';

/* ---------- XSS 习惯：所有 DB 来源数据插值前必须过 esc() ---------- */
export function esc(v) {
  return String(v ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

/**
 * 极简模板标签函数：返回 HTML 字符串（调用方 innerHTML 一次性挂载）。
 * 用法：el`<div class="x">${esc(name)}</div>` —— 插值不自动转义，
 * 转义责任在使用处（强制开发者显式调用 esc，避免双重转义）。
 */
export function el(strings, ...values) {
  return strings.reduce((acc, s, i) => acc + s + (i < values.length ? values[i] : ''), '');
}

/* ==========================================================================
 * Toast（右上角栈式，同类去重计数，按级别自动时长）
 * ========================================================================== */
const toastRoot = () => document.getElementById('toast-root');
const TOAST_DURATION = { success: 3500, warn: 5000, error: 8000, info: 6000 };
const activeToasts = new Map(); // key -> {node, countEl, count}

export function toast(title, { level = 'info', detail = '', dedupeKey = null } = {}) {
  const key = dedupeKey || `${level}:${title}`;
  const existing = activeToasts.get(key);
  if (existing) { // 同类合并 ×N
    existing.count++;
    existing.countEl.textContent = `×${existing.count}`;
    return;
  }
  const node = document.createElement('div');
  node.className = `toast ${level}`;
  node.setAttribute('role', 'status');
  node.innerHTML = el`
    <div class="t-title">${esc(title)}</div>
    ${detail ? el`<div class="t-body">${esc(detail)}</div>` : ''}
    <span class="t-count hidden"></span>
    <button class="t-close" aria-label="关闭">×</button>`;
  toastRoot().appendChild(node);
  const countEl = node.querySelector('.t-count');
  const entry = { node, countEl, count: 1 };
  activeToasts.set(key, entry);
  const close = () => {
    activeToasts.delete(key);
    node.classList.add('leaving');
    node.addEventListener('animationend', () => node.remove(), { once: true });
    setTimeout(() => node.remove(), 300); // 兜底
  };
  node.querySelector('.t-close').addEventListener('click', close);
  setTimeout(close, TOAST_DURATION[level] ?? 5000);
}

/** ApiError（biz 级）→ 按 ErrorCode 文案表弹对应 toast */
export function toastBizError(err) {
  const { title, level, detail } = describeBizError(err);
  toast(title, { level, detail, dedupeKey: `biz:${err.code}` });
}

/** 统一错误出口：三态分流（biz / not-implemented / network / http） */
export function handleError(err) {
  if (err instanceof ApiError) {
    switch (err.kind) {
      case 'biz': toastBizError(err); return;
      case 'not-implemented':
        toast('该接口尚未实现', { level: 'info', detail: `等待后端 ${err.hint} · ${err.path}` });
        return;
      case 'network': setConnected(false); toast('后端连接失败', { level: 'error', detail: '请确认 Spring Boot 已启动（localhost:8080）' }); return;
      default: toast('请求异常', { level: 'error', detail: err.msg }); return;
    }
  }
  toast('未预期错误', { level: 'error', detail: String(err?.message || err) });
  console.error(err);
}

/* ---------- 断连横幅（恢复由任意成功请求触发） ---------- */
export function setConnected(ok) {
  const banner = document.getElementById('conn-banner');
  if (banner) banner.hidden = ok;
}

/* ==========================================================================
 * confirmDialog：返回 Promise<boolean>
 * opts: { title, bodyHtml, confirmText, danger, requireText }
 *   requireText: 需要用户键入的精确文本（gen-data 用 "RESET"），不匹配则确认钮禁用
 * ========================================================================== */
export function confirmDialog(opts) {
  const { title, bodyHtml = '', confirmText = '确认', danger = false, requireText = null } = opts;
  return new Promise((resolve) => {
    const root = document.getElementById('modal-root');
    const overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.innerHTML = el`
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
        <h3 class="modal-title" id="modal-title">${esc(title)}</h3>
        <div class="modal-body">${bodyHtml}</div>
        ${requireText ? el`
          <div class="field" style="margin-top:16px">
            <label for="modal-confirm-input">请输入 <b class="mono">${esc(requireText)}</b> 以确认</label>
            <input id="modal-confirm-input" class="input mono" autocomplete="off" spellcheck="false">
          </div>` : ''}
        <div class="modal-actions">
          <button class="btn btn-secondary" data-act="cancel">取消</button>
          <button class="btn ${danger ? 'btn-danger' : 'btn-primary'}" data-act="ok" ${requireText ? 'disabled' : ''}>${esc(confirmText)}</button>
        </div>
      </div>`;
    root.appendChild(overlay);

    const input = overlay.querySelector('#modal-confirm-input');
    const okBtn = overlay.querySelector('[data-act="ok"]');
    const cancelBtn = overlay.querySelector('[data-act="cancel"]');
    const prevFocus = document.activeElement;

    const done = (val) => {
      overlay.remove();
      document.removeEventListener('keydown', onKey);
      if (prevFocus && prevFocus.focus) prevFocus.focus();
      resolve(val);
    };
    const onKey = (e) => { if (e.key === 'Escape') done(false); };
    document.addEventListener('keydown', onKey);

    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) done(false);               // 点遮罩取消
      if (e.target === cancelBtn) done(false);
      if (e.target === okBtn && !okBtn.disabled) done(true);
    });
    if (input) {
      input.addEventListener('input', () => {               // 精确匹配才点亮确认钮
        okBtn.disabled = input.value !== requireText;
      });
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !okBtn.disabled) done(true);
      });
      input.focus();
    } else {
      okBtn.focus();
    }
  });
}

/* ---------- 按钮 loading 辅助（宽度保持，spinner 见 CSS） ---------- */
export function withLoading(btn, promiseFactory) {
  btn.classList.add('loading');
  btn.disabled = true;
  const restore = () => { btn.classList.remove('loading'); btn.disabled = false; };
  return promiseFactory().finally(restore);
}

/* ---------- 骨架屏 / 区块级错误条（重试按钮经 data-retry 由视图层委托绑定） ---------- */
export function skeletonCards(n = 3) {
  return Array.from({ length: n }, () => '<div class="skeleton card"></div>').join('');
}
export function inlineError(msgText, { retry = false } = {}) {
  return el`<div class="inline-error"><span>${esc(msgText)}</span>${
    retry ? '<button class="btn btn-ghost btn-sm" data-retry="1">重试</button>' : ''
  }</div>`;
}
