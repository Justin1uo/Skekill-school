/* ==========================================================================
 * main.js · 入口：顶栏挂载 → 路由注册/守卫 → 全局兜底
 * ========================================================================== */

import { register, startRouter, setDefaultRouteResolver, navigate, requireIdentity } from './router.js';
import { getState, hasIdentity, subscribe, studentNo } from './store.js';
import { esc, el, setConnected } from './ui.js';
import * as identity from './views/identity.js';
import * as courses from './views/courses.js';
import * as mine from './views/mine.js';
import * as admin from './views/admin.js';
import * as styleguide from './views/styleguide.js';

/* ---------- 顶栏 ---------- */
const NAV_ITEMS = [
  { name: 'courses', label: '课程广场' },
  { name: 'mine', label: '我的课表' },
  { name: 'admin', label: '管理面板' },
];

function renderTopbar(activeName) {
  const { studentId } = getState();
  const bar = document.getElementById('topbar');
  bar.innerHTML = el`
    <div class="topbar-inner">
      <a class="brand" href="#/courses">Skekill <em>选课</em></a>
      <nav class="nav grow">
        ${NAV_ITEMS.map((n) => el`
          <a href="#/${n.name}" class="${n.name === activeName ? 'active' : ''}">${n.label}</a>`).join('')}
      </nav>
      ${studentId ? el`
        <span class="identity-chip">
          <span class="mono">${esc(studentNo())}</span>
          <span class="muted">id=${esc(studentId)}</span>
          <button class="chip-btn" data-act="switch">切换</button>
        </span>` : el`
        <span class="identity-chip">
          <span class="muted">未选择身份</span>
          <button class="chip-btn" data-act="switch">选择</button>
        </span>`}
    </div>`;
  bar.querySelector('[data-act="switch"]').addEventListener('click', () => navigate('#/identity'));
}

/* ---------- 路由注册（守卫：courses/mine 需要身份） ---------- */
register('identity', identity.render);
register('courses', courses.render, () => requireIdentity(null, hasIdentity));
register('mine', mine.render, () => requireIdentity(null, hasIdentity));
register('admin', admin.render);
register('styleguide', styleguide.render); // 隐藏页：设计系统组件状态一览

setDefaultRouteResolver(() => (hasIdentity() ? 'courses' : 'identity'));

document.addEventListener('route:changed', (e) => renderTopbar(e.detail.name));
subscribe(() => renderTopbar((location.hash.replace(/^#\//, '') || 'identity').split('?')[0]));

/* ---------- 全局兜底：任何未捕获异常/断连都不白屏 ---------- */
window.addEventListener('error', (e) => {
  console.error('[global]', e.error || e.message);
});
window.addEventListener('unhandledrejection', (e) => {
  console.error('[unhandled]', e.reason);
  if (e.reason?.kind === 'network') setConnected(false);
});

/* 根路径无 hash 时给默认落点（有身份→courses，无→identity） */
if (!location.hash) navigate(`#/${hasIdentity() ? 'courses' : 'identity'}`);

renderTopbar((location.hash.replace(/^#\//, '') || 'identity').split('?')[0]);
startRouter();
