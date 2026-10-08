/* ==========================================================================
 * views/courses.js · 课程广场
 * 区块 A：GET /api/courses 探测区（Stage 2 契约保留 —— 当前未注册命中 code 500 显示降级卡；
 *          后端接口上线后本区域自动切换为真实课程卡片网格，前端零改动）
 * 区块 B：手动选课模式（当前主路径，POST /api/selection 已实现的完整暴露）
 * 区块 C：选课回执（完整 19 位雪花 ID —— safeParse 精度验证的证据）
 * ========================================================================== */

import { listCourses, selectCourse } from '../api.js';
import { el, esc, toast, handleError, withLoading, skeletonCards, setConnected } from '../ui.js';
import { getState, markMineDirty, memory, invalidateCourses, COURSE_ID_RANGE } from '../store.js';
import { ApiError } from '../api.js';

let lastReceipt = null; // 本次会话最近一次选课回执

export function render(container) {
  const { studentId } = getState();

  container.innerHTML = el`
    <div class="view-head">
      <h1 class="title">课程广场</h1>
      <p class="subtitle">共 50 门课 · 每课容量 100，数据由 gen-data 生成（studentId=${esc(studentId)}）</p>
    </div>

    <div class="section">
      <div id="list-region">
        <div class="course-grid">${skeletonCards(3)}</div>
      </div>
    </div>

    <div class="section">
      <div class="card" id="manual-card">
        <div class="row" style="justify-content:space-between; margin-bottom:var(--sp-4)">
          <h3>手动选课模式 <span class="badge badge-accent">按 ID 直达</span></h3>
        </div>
        <p class="sm ink-2" style="margin-bottom:var(--sp-4)">
          不想在列表里翻找时，可直接按 ID 选课。courseId 范围 ${COURSE_ID_RANGE[0]}~${COURSE_ID_RANGE[1]}（gen-data 约定）。
        </p>
        <div class="row" style="align-items:flex-end; flex-wrap:wrap">
          <div class="field grow" style="min-width:180px">
            <label for="cid-input">courseId</label>
            <input id="cid-input" class="input mono" type="text" inputmode="numeric" placeholder="1 ~ 50">
          </div>
          <div class="field">
            <label>studentId（只读，来自身份）</label>
            <input class="input mono" value="${esc(studentId)}" readonly style="width:160px">
          </div>
          <button class="btn btn-primary" id="btn-select" type="button" style="height:41px">选课</button>
        </div>
      </div>
    </div>

    <div class="section" id="receipt-region">
      ${lastReceipt ? renderReceipt(lastReceipt) : ''}
    </div>`;

  bindManual(container);
  loadListRegion(container);

  return () => { /* 无长生命周期资源 */ };
}

/* ---------- 区块 C 渲染 ---------- */
function renderReceipt(r) {
  return el`
    <div class="receipt" title="POST /api/selection 的 data（雪花 ID，safeParse 后为完整字符串）">
      <span class="r-title">选课成功 · 回执</span>
      <div class="r-row"><span>selectionId</span><b class="r-id">${esc(r.selectionId)}</b></div>
      <div class="r-row"><span>courseId</span><b>${esc(r.courseId)}</b></div>
      <div class="r-row"><span>studentId</span><b>${esc(r.studentId)}</b></div>
      <div class="r-row"><span>本地时间</span><b>${esc(r.time)}</b></div>
    </div>`;
}

/* ---------- 区块 B：手动选课 ---------- */
function bindManual(container) {
  const input = container.querySelector('#cid-input');
  const btn = container.querySelector('#btn-select');

  const doSelect = async () => {
    const cid = parseInt(input.value, 10);
    if (!Number.isInteger(cid) || cid < COURSE_ID_RANGE[0] || cid > COURSE_ID_RANGE[1]) {
      input.classList.add('invalid');
      toast('courseId 无效', { level: 'warn', detail: `范围 ${COURSE_ID_RANGE[0]}~${COURSE_ID_RANGE[1]}（或先到管理面板 gen-data）` });
      return;
    }
    input.classList.remove('invalid');
    const { studentId } = getState();
    try {
      const selectionId = await withLoading(btn, () => selectCourse(studentId, cid));
      setConnected(true);
      lastReceipt = { selectionId, courseId: cid, studentId, time: new Date().toLocaleString('zh-CN') };
      container.querySelector('#receipt-region').innerHTML = renderReceipt(lastReceipt);
      toast('选课成功', { level: 'success', detail: `courseId=${cid}` });
      markMineDirty();
    } catch (err) {
      if (err instanceof ApiError && err.kind === 'network') setConnected(false);
      handleError(err);
    }
  };

  btn.addEventListener('click', doSelect);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') doSelect(); });
}

/* ---------- 区块 A：列表探测 / 真实渲染 ---------- */
async function loadListRegion(container) {
  const region = container.querySelector('#list-region');
  try {
    const list = await listCourses();
    setConnected(true);
    memory.courses = list;
    renderRealGrid(region, list);
  } catch (err) {
    if (err instanceof ApiError && err.kind === 'not-implemented') {
      renderStub(region, err.hint);
    } else {
      if (err instanceof ApiError && err.kind === 'network') setConnected(false);
      renderStubError(region, err, () => loadListRegion(container));
    }
  }
}

/* Stage 2 未上线：降级卡（虚线 = 与实心数据卡的形态区分） */
function renderStub(region, hint) {
  region.innerHTML = el`
    <div class="stub-card">
      <div class="stub-head">
        <span class="stub-path">GET /api/courses</span>
        <span class="badge badge-neutral">契约已预留</span>
        <span class="badge badge-accent">等待后端 ${esc(hint)}</span>
      </div>
      <p>课程列表接口（L1+L2 缓存版）属于 Stage 2 规划，后端尚未注册该 mapping，
         实测表现为命中 <code class="mono">GlobalExceptionHandler</code> 兜底（HTTP 200 + code 500"系统异常"），前端据此判定"未实现"。
         接口上线后本区域将自动渲染课程卡片网格（含搜索、余量进度条、一键选课），前端代码无需改动。
         现阶段请走下方<strong>手动选课模式</strong>完成选课闭环。</p>
    </div>`;
}

function renderStubError(region, err, retryFn) {
  region.innerHTML = el`
    <div class="stub-card" style="border-style:solid">
      <div class="stub-head"><span class="stub-path">GET /api/courses</span><span class="badge badge-danger">加载失败</span></div>
      <p>${esc(err.message || String(err))}</p>
      <button class="btn btn-ghost btn-sm" id="btn-retry-list" style="margin-top:var(--sp-3)">重试</button>
    </div>`;
  const b = region.querySelector('#btn-retry-list');
  if (b) b.addEventListener('click', () => retryFn());
}

/* Stage 2 上线后的真实网格（搜索 + 仅看有余额 + 选课按钮） */
function renderRealGrid(region, list) {
  region.innerHTML = el`
    <div class="row" style="justify-content:space-between; margin-bottom:var(--sp-4); flex-wrap:wrap">
      <h2 class="section">课程列表 <span class="badge badge-ok">接口已上线</span></h2>
      <div class="toolbar" style="margin-bottom:0">
        <input class="input" id="q" placeholder="搜索名称 / 课号 / 教师" style="width:220px">
        <label class="switch"><input type="checkbox" id="only-avail"> 仅看有余额</label>
      </div>
    </div>
    <div class="course-grid" id="grid"></div>`;
  const grid = region.querySelector('#grid');
  const q = region.querySelector('#q');
  const only = region.querySelector('#only-avail');

  const draw = () => {
    const kw = q.value.trim().toLowerCase();
    const items = list.filter((c) => {
      if (only.checked && c.selectedCount >= c.capacity) return false;
      if (!kw) return true;
      return [c.name, c.courseCode, c.teacher].some((s) => String(s).toLowerCase().includes(kw));
    });
    grid.innerHTML = items.length ? items.map(courseCard).join('')
      : '<p class="muted sm" style="grid-column:1/-1;padding:var(--sp-5);text-align:center">没有匹配的课程</p>';
  };
  q.addEventListener('input', draw);
  only.addEventListener('change', draw);
  draw();

  grid.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-select]');
    if (!btn) return;
    const cid = parseInt(btn.dataset.select, 10);
    const { studentId } = getState();
    try {
      const selectionId = await withLoading(btn, () => selectCourse(studentId, cid));
      setConnected(true);
      lastReceipt = { selectionId, courseId: cid, studentId, time: new Date().toLocaleString('zh-CN') };
      region.parentElement.querySelector('#receipt-region').innerHTML = renderReceipt(lastReceipt);
      toast('选课成功', { level: 'success', detail: `courseId=${cid}` });
      markMineDirty();
    } catch (err) {
      if (err instanceof ApiError && err.kind === 'network') setConnected(false);
      handleError(err);
    }
  });
}

function courseCard(c) {
  const pct = Math.min(100, Math.round((c.selectedCount / c.capacity) * 100));
  const fillCls = pct >= 100 ? 'danger' : pct >= 70 ? 'warn' : '';
  const full = c.selectedCount >= c.capacity;
  return el`
    <div class="course-card ${full ? 'full' : ''}">
      <div class="card-head">
        <span class="course-name">${esc(c.name)}</span>
        <span class="code-badge">${esc(c.courseCode)}</span>
      </div>
      <div class="meta">
        <span>${esc(c.teacher)}</span>
        <span class="badge badge-accent">${esc(c.credit)} 学分</span>
        <span class="muted">id=${esc(c.id)}</span>
      </div>
      <div class="progress-row">
        <div class="progress"><div class="fill ${fillCls}" style="width:${pct}%"></div></div>
        <span class="progress-count">${esc(c.selectedCount)}/${esc(c.capacity)}</span>
      </div>
      <button class="btn ${full ? 'btn-secondary' : 'btn-primary'} btn-sm" data-select="${esc(c.id)}" ${full ? 'disabled' : ''}>
        ${full ? '已满' : '选课'}
      </button>
    </div>`;
}
