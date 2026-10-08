/* ==========================================================================
 * views/admin.js · 管理面板
 * 健康卡（/actuator/health 非 Result 结构，单独解析）
 * gen-data 危险区（键入 RESET 强确认）· warmup 降级卡（Stage 2）
 * ========================================================================== */

import { getHealth, genData, warmup, ApiError } from '../api.js';
import { el, esc, toast, confirmDialog, handleError, withLoading, setConnected } from '../ui.js';
import { markMineDirty, invalidateCourses } from '../store.js';

let lastGenSummary = null; // 本会话最近一次 gen-data 结果（仅内存，不落 localStorage）

export async function render(container) {
  container.innerHTML = el`
    <div class="view-head">
      <h1 class="title">管理面板</h1>
      <p class="subtitle">本面板对应后端 <code class="mono">/api/admin/**</code> 与 actuator，<strong>无任何鉴权</strong>：本地开发/压测用途，公网部署前必须关闭（见 AdminController 注释）。</p>
    </div>

    <div class="stack" style="gap:var(--sp-5)">
      <div class="card hoverable" id="health-card">
        <div class="row" style="justify-content:space-between">
          <div class="row-tight">
            <span class="health-dot unknown" id="h-dot"></span>
            <h3>服务健康</h3>
            <span class="muted sm mono" id="h-status">检测中…</span>
          </div>
          <button class="btn btn-ghost btn-sm" id="btn-health" type="button">刷新</button>
        </div>
        <p class="xs muted" style="margin-top:var(--sp-2)">GET /actuator/health 不带 Result 包装，status=UP 即 MySQL/Redis 连接正常。</p>
      </div>

      <div class="card" id="gen-card"></div>

      <div class="stub-card">
        <div class="stub-head">
          <span class="stub-path">POST /api/admin/warmup</span>
          <span class="badge badge-neutral">契约已预留</span>
          <span class="badge badge-accent">等待后端 Stage 2</span>
        </div>
        <p style="margin-bottom:var(--sp-3)">Redis 预热（课程名额灌入）属于 Stage 2 规划。预热机制上线后，若在未预热状态选课收到 <code class="mono">1007 选课尚未开放</code> 属预期行为。</p>
        <button class="btn btn-secondary btn-sm" id="btn-warmup" type="button">执行 warmup（探测）</button>
      </div>
    </div>`;

  drawGenCard(container);

  container.querySelector('#btn-health').addEventListener('click', () => checkHealth(container));
  container.querySelector('#btn-warmup').addEventListener('click', async (e) => {
    try { await withLoading(e.target, warmup); toast('warmup 成功', { level: 'success' }); }
    catch (err) { handleError(err); } // 当前 404 → "该接口尚未实现，等待后端 Stage 2"
  });

  await checkHealth(container);
  return () => {};
}

async function checkHealth(container) {
  const dot = container.querySelector('#h-dot');
  const txt = container.querySelector('#h-status');
  try {
    const status = await getHealth();
    dot.className = `health-dot ${status === 'UP' ? 'up' : 'down'}`;
    txt.textContent = status;
    setConnected(true);
  } catch (err) {
    dot.className = 'health-dot down';
    txt.textContent = '不可达';
    if (err instanceof ApiError && err.kind === 'network') setConnected(false);
  }
}

/* ---------- gen-data 卡 + 危险区 ---------- */
function drawGenCard(container) {
  const card = container.querySelector('#gen-card');
  card.innerHTML = el`
    <div class="row" style="justify-content:space-between; margin-bottom:var(--sp-3)">
      <h3>压测数据生成</h3>
      <span class="muted sm mono">POST /api/admin/gen-data</span>
    </div>
    ${lastGenSummary ? el`
      <div class="stat-grid" style="margin-bottom:var(--sp-4)">
        <div class="stat"><div class="num">${esc(lastGenSummary.students)}</div><div class="label">students</div></div>
        <div class="stat"><div class="num">${esc(lastGenSummary.courses)}</div><div class="label">courses</div></div>
        <div class="stat"><div class="num">${esc(lastGenSummary.schedules)}</div><div class="label">schedules</div></div>
        <div class="stat"><div class="num">${esc(lastGenSummary.capacityPerCourse)}</div><div class="label">capacity / 课</div></div>
        <div class="stat"><div class="num mono">${esc(lastGenSummary.costMs)} ms</div><div class="label">costMs</div></div>
        <div class="stat"><div class="num mono" style="font-size:var(--fs-sm)">${esc(lastGenSummary.idRange)}</div><div class="label">idRange</div></div>
      </div>` : el`
      <p class="sm ink-2">重建 5000 学生 / 50 门课 / 时段（固定种子 20260926，结果可复现）。ID 连续小整数便于 curl / JMeter CSV / redis-cli 肉眼写死。</p>`}

    <div class="danger-zone" style="margin-top:var(--sp-4)">
      <h3 class="dz-title" style="margin-bottom:var(--sp-2)">危险操作：重置全部数据</h3>
      <ul class="consequence-list sm">
        <li>清空全部 <b>selection</b> 选课记录（不可恢复）</li>
        <li>重建 course_schedule / course / student 三表</li>
        <li>所有人「我的课表」将变为空，压测基线数据归零</li>
      </ul>
      <button class="btn btn-danger" id="btn-gen" type="button" style="margin-top:var(--sp-4)">执行 gen-data</button>
    </div>`;

  card.querySelector('#btn-gen').addEventListener('click', async (e) => {
    const ok = await confirmDialog({
      title: '重置全部压测数据？',
      danger: true,
      confirmText: '执行重置',
      requireText: 'RESET',
      bodyHtml: el`<ul class="consequence-list">
        <li>清空所有选课记录（selection 表），不可恢复</li>
        <li>重建 5000 学生、50 门课、时段</li>
        <li>执行期间接口短暂不可用，前端会显示加载遮罩</li>
      </ul>`,
    });
    if (!ok) return;
    const btn = card.querySelector('#btn-gen');
    try {
      const summary = await withLoading(btn, genData);
      setConnected(true);
      lastGenSummary = summary;
      markMineDirty();
      invalidateCourses();
      drawGenCard(container);
      toast('数据已重置', { level: 'success', detail: '所有课表已清空；student 1~5000 / course 1~50 重建完成' });
    } catch (err) {
      if (err instanceof ApiError && err.kind === 'network') setConnected(false);
      handleError(err);
    }
  });
}
