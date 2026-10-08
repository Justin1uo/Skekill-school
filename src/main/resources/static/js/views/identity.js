/* ==========================================================================
 * views/identity.js · 身份门
 * 无鉴权是后端既成事实（演示环境），此处诚实标注并只做范围校验。
 * ========================================================================== */

import { el, esc, toast } from '../ui.js';
import { navigate } from '../router.js';
import { setStudentId, studentNo, getState, STUDENT_ID_RANGE } from '../store.js';

const QUICK_IDS = [1, 2, 3, 42, 500];

export function render(container, params) {
  const next = params.get('next') || 'courses';
  const { studentId } = getState();

  container.innerHTML = el`
    <div class="view-head center" style="padding-top: var(--sp-5)">
      <h1 class="display">选择你的身份</h1>
      <p class="subtitle" style="max-width:520px;margin-left:auto;margin-right:auto">
        演示环境<strong>无鉴权</strong>：studentId 由调用方明文自报（与后端事实一致）。<br>
        数据由 <code class="mono">gen-data</code> 生成，学号范围 ${STUDENT_ID_RANGE[0]}~${STUDENT_ID_RANGE[1]}。
      </p>
    </div>

    <div class="course-grid" style="max-width:860px;margin:0 auto">
      <!-- 学生入口 -->
      <div class="card">
        <h3 style="margin-bottom:var(--sp-3)">学生</h3>
        ${studentId ? el`<p class="sm ink-2" style="margin-bottom:var(--sp-3)">当前身份：<b class="mono">${esc(studentNo())}</b>（id=${esc(studentId)}），可直接继续。</p>` : ''}
        <div class="field">
          <label for="sid-input">studentId（${STUDENT_ID_RANGE[0]} ~ ${STUDENT_ID_RANGE[1]}）</label>
          <input id="sid-input" class="input mono" type="text" inputmode="numeric"
                 placeholder="例如 1" value="${studentId ? esc(studentId) : ''}">
          <div class="field-hint" id="sid-hint">学号预览：<span class="mono" id="sid-preview">${esc(studentNo(studentId || 1))}</span></div>
        </div>
        <div class="row-tight" style="margin-top:var(--sp-4)">
          <div class="chips" id="quick-chips">
            ${QUICK_IDS.map((q) => el`<button class="chip" data-q="${q}">id=${q}</button>`).join('')}
          </div>
          <button class="btn btn-ghost btn-sm" id="btn-random" type="button">随机一个</button>
        </div>
        <div class="row" style="margin-top:var(--sp-5)">
          <button class="btn btn-primary grow" id="btn-enter" type="button">进入系统</button>
        </div>
        <p class="xs muted" style="margin-top:var(--sp-3)">姓名/年级/专业暂无查询接口，仅展示派生学号。</p>
      </div>

      <!-- 管理员入口 -->
      <div class="card">
        <h3 style="margin-bottom:var(--sp-3)">管理员（演示）</h3>
        <p class="sm ink-2">重置压测数据、查看服务健康。后端 <code class="mono">/api/admin/**</code> 无密码保护，与本地开发环境事实一致，公网部署前必须关闭。</p>
        <ul class="consequence-list" style="margin-top:var(--sp-3)">
          <li class="muted" style="padding-left:0">gen-data 会清空全部选课记录</li>
        </ul>
        <div class="row" style="margin-top:var(--sp-5)">
          <button class="btn btn-secondary grow" id="btn-admin" type="button">进入管理面板</button>
        </div>
      </div>
    </div>`;

  const input = container.querySelector('#sid-input');
  const preview = container.querySelector('#sid-preview');
  const hint = container.querySelector('#sid-hint');

  const livePreview = () => {
    const n = parseInt(input.value, 10);
    const ok = Number.isInteger(n) && n >= STUDENT_ID_RANGE[0] && n <= STUDENT_ID_RANGE[1];
    preview.textContent = ok ? studentNo(n) : '-';
    hint.classList.toggle('error', input.value !== '' && !ok);
    return ok;
  };
  input.addEventListener('input', livePreview);
  livePreview();

  container.addEventListener('click', (e) => {
    const chip = e.target.closest('.chip');
    if (chip) { input.value = chip.dataset.q; livePreview(); input.focus(); return; }
    if (e.target.closest('#btn-random')) {
      input.value = Math.floor(Math.random() * STUDENT_ID_RANGE[1]) + 1;
      livePreview();
      return;
    }
    if (e.target.closest('#btn-admin')) { navigate('#/admin'); return; }
    if (e.target.closest('#btn-enter')) {
      const n = parseInt(input.value, 10);
      if (!Number.isInteger(n) || n < STUDENT_ID_RANGE[0] || n > STUDENT_ID_RANGE[1]) {
        input.classList.add('invalid');
        toast('studentId 超出范围', { level: 'warn', detail: `需在 ${STUDENT_ID_RANGE[0]}~${STUDENT_ID_RANGE[1]}，或先到管理面板执行 gen-data` });
        return;
      }
      input.classList.remove('invalid');
      setStudentId(n);
      toast(`已切换身份 ${studentNo(n)}`, { level: 'success' });
      navigate(`#/${next}`);
    }
  });
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') container.querySelector('#btn-enter').click(); });
}
