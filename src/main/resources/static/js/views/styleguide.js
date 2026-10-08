/* ==========================================================================
 * views/styleguide.js · 隐藏设计系统页（直接访问 #/styleguide）
 * 用途 1：逐一目检组件三态（按钮/输入/徽章/toast/modal/降级卡/进度条）
 * 用途 2：预览 v1 后端不可达的错误码文案（1004~1007）——标注为文案预览，
 *          不伪造后端响应，符合项目"不虚构"铁律。
 * ========================================================================== */

import { el, esc, toast, confirmDialog } from '../ui.js';
import { ERROR_TEXT } from '../api.js';

export function render(container) {
  container.innerHTML = el`
    <div class="view-head">
      <h1 class="title">Style Guide <span class="badge badge-neutral">#/styleguide 隐藏页</span></h1>
      <p class="subtitle">设计系统组件状态一览（暖纸感 token 的活文档）。不随导航展示。</p>
    </div>

    <div class="stack" style="gap:var(--sp-6)">
      <div>
        <h2 class="section" style="margin-bottom:var(--sp-4)">按钮</h2>
        <div class="row" style="flex-wrap:wrap">
          <button class="btn btn-primary">Primary</button>
          <button class="btn btn-secondary">Secondary</button>
          <button class="btn btn-ghost">Ghost</button>
          <button class="btn btn-danger">Danger</button>
          <button class="btn btn-primary" disabled>Disabled</button>
          <button class="btn btn-secondary loading">Loading(宽度锁定)</button>
        </div>
      </div>

      <div>
        <h2 class="section" style="margin-bottom:var(--sp-4)">徽章 / 状态</h2>
        <div class="row" style="flex-wrap:wrap">
          <span class="badge badge-ok">开放</span>
          <span class="badge badge-warn">紧张</span>
          <span class="badge badge-danger">已满</span>
          <span class="badge badge-accent">4 学分</span>
          <span class="badge badge-neutral">契约已预留</span>
        </div>
      </div>

      <div>
        <h2 class="section" style="margin-bottom:var(--sp-4)">余量进度条（分档配色）</h2>
        <div class="stack" style="max-width:420px">
          ${[['37/100', 37, ''], ['82/100', 82, 'warn'], ['100/100', 100, 'danger']].map(([t, w, cls]) => el`
            <div class="progress-row">
              <div class="progress"><div class="fill ${cls}" style="width:${w}%"></div></div>
              <span class="progress-count">${t}</span>
            </div>`).join('')}
        </div>
      </div>

      <div>
        <h2 class="section" style="margin-bottom:var(--sp-4)">Toast / Modal</h2>
        <div class="row" style="flex-wrap:wrap">
          <button class="btn btn-ghost btn-sm" data-t="success">success toast</button>
          <button class="btn btn-ghost btn-sm" data-t="warn">warn toast</button>
          <button class="btn btn-ghost btn-sm" data-t="error">error toast</button>
          <button class="btn btn-ghost btn-sm" data-t="info">info toast</button>
          <button class="btn btn-secondary btn-sm" id="btn-demo-modal">RESET 确认框演示</button>
        </div>
      </div>

      <div>
        <h2 class="section" style="margin-bottom:var(--sp-4)">错误码文案全表（ErrorCode ↔ 前端展示）</h2>
        <p class="sm ink-2" style="margin-bottom:var(--sp-4)">
          带 <span class="badge badge-neutral" style="vertical-align:middle">v1 不可达</span> 的四个码要到 Stage 3/4 后端才会返回；
          此处仅预览前端文案，<strong>非伪造后端响应</strong>。点击行尾按钮弹对应 toast。
        </p>
        <div class="table-wrap"><table class="data">
          <thead><tr><th>code</th><th>标题</th><th>级别</th><th>文案</th><th></th></tr></thead>
          <tbody>
            ${Object.entries(ERROR_TEXT).map(([code, [title, level, fn]]) => el`
              <tr>
                <td class="mono">${code}</td>
                <td>${esc(title)}</td>
                <td><span class="badge ${level === 'error' ? 'badge-danger' : level === 'warn' ? 'badge-warn' : 'badge-accent'}">${level}</span></td>
                <td class="sm ink-2">${esc(fn(''))}</td>
                <td style="text-align:right"><button class="btn btn-ghost btn-sm" data-preview="${code}">${Number(code) >= 1004 ? '预览' : '预览'}</button></td>
              </tr>`).join('')}
          </tbody>
        </table></div>
      </div>
    </div>`;

  container.addEventListener('click', (e) => {
    const t = e.target.closest('[data-t]');
    if (t) { toast(`${t.dataset.t} 级别示例`, { level: t.dataset.t, detail: 'styleguide 演示触发' }); return; }
    if (e.target.closest('#btn-demo-modal')) {
      confirmDialog({
        title: '示例：强确认对话框',
        danger: true, requireText: 'RESET', confirmText: '执行',
        bodyHtml: '<p>gen-data 同款交互：必须键入 RESET 才点亮确认钮。</p>',
      }).then((ok) => ok && toast('确认（演示）', { level: 'success' }));
      return;
    }
    const p = e.target.closest('[data-preview]');
    if (p) {
      const [title, level, fn] = ERROR_TEXT[p.dataset.preview];
      toast(title, { level, detail: fn(''), dedupeKey: `preview:${p.dataset.preview}` });
    }
  });
  return () => {};
}
