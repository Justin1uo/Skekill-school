/* ==========================================================================
 * views/mine.js · 我的课表
 * stat tiles + 数据表格 + 退选（Stage 3 契约保留，当前 404 → 降级 toast）
 * 诚实注脚：selectedCount 在 Stage 3 后由 MQ 异步落库，可能短暂落后于 Redis 实际余量。
 * ========================================================================== */

import { getMyCourses, dropCourse, changeCourse, ApiError } from '../api.js';
import { el, esc, toast, handleError, withLoading, setConnected } from '../ui.js';
import { getState, markMineDirty, CREDIT_LIMIT } from '../store.js';
import { navigate } from '../router.js';

export async function render(container) {
  const { studentId } = getState();

  container.innerHTML = el`
    <div class="view-head row" style="justify-content:space-between; flex-wrap:wrap">
      <div>
        <h1 class="title">我的课表</h1>
        <p class="subtitle">GET /api/selection/mine · studentId=${esc(studentId)}</p>
      </div>
      <div class="row-tight">
        <button class="btn btn-ghost btn-sm" id="btn-refresh" type="button">↻ 刷新</button>
        <button class="btn btn-secondary btn-sm" id="btn-change" type="button">换课（退A选B）</button>
      </div>
    </div>
    <div id="mine-body">
      <div class="stack">
        <div class="skeleton line" style="height:72px"></div>
        <div class="skeleton card" style="height:220px"></div>
      </div>
    </div>`;

  const body = container.querySelector('#mine-body');

  // 退选：委托绑定一次（draw 重渲染不叠加监听）。当前接口 404 → 真实降级提示，不做假成功。
  body.addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-drop]');
    if (!btn) return;
    const cid = parseInt(btn.dataset.drop, 10);
    try {
      await withLoading(btn, () => dropCourse(cid));
      toast('退选成功', { level: 'success' });
      markMineDirty();
    } catch (err) {
      handleError(err);
    }
  });

  const load = async () => {
    try {
      const list = await getMyCourses(studentId);
      setConnected(true);
      draw(body, list);
    } catch (err) {
      if (err instanceof ApiError && err.kind === 'network') setConnected(false);
      body.innerHTML = el`<div class="inline-error"><span>${esc(err.message)}</span>
        <button class="btn btn-ghost btn-sm" id="btn-retry">重试</button></div>`;
      body.querySelector('#btn-retry').addEventListener('click', load);
    }
  };

  container.querySelector('#btn-refresh').addEventListener('click', load);
  container.querySelector('#btn-change').addEventListener('click', async () => {
    // 换课接口是蓝图 Stage 3 的可砍项：不渲染半吊子表单，只给出真实的降级提示
    try {
      await changeCourse(0, 0); // 当前必然 404，走 not-implemented 降级
    } catch (err) {
      if (err instanceof ApiError && err.kind === 'not-implemented') {
        toast('换课接口尚未实现', { level: 'info', detail: '等待后端 Stage 3（蓝图标注可砍）' });
      } else {
        handleError(err);
      }
    }
  });

  await load();
  return () => {};
}

function draw(body, list) {
  const totalCredit = list.reduce((s, c) => s + (c.credit || 0), 0);
  const now = new Date().toLocaleTimeString('zh-CN');

  if (!list.length) {
    body.innerHTML = el`
      <div class="empty-state">
        <h3>课表还是空的</h3>
        <p>该身份（studentId=${esc(getState().studentId)}）尚未选任何课程。GET /api/selection/mine 返回空数组。</p>
        <button class="btn btn-primary" id="btn-go" type="button">去选课</button>
      </div>`;
    body.querySelector('#btn-go').addEventListener('click', () => navigate('#/courses'));
    return;
  }

  body.innerHTML = el`
    <div class="stack" style="gap:var(--sp-5)">
      <div class="stat-grid">
        <div class="stat"><div class="num">${list.length}</div><div class="label">已选课程</div></div>
        <div class="stat"><div class="num">${totalCredit}<span class="muted" style="font-size:var(--fs-h3)"> / ${CREDIT_LIMIT}</span></div><div class="label">总学分（上限 ${CREDIT_LIMIT} · Stage 3 起强制校验 1005）</div></div>
        <div class="stat"><div class="num mono">${esc(now)}</div><div class="label">数据时间（每次进入/刷新重拉，服务端为唯一事实源）</div></div>
      </div>

      <div class="table-wrap">
        <table class="data">
          <thead><tr>
            <th>课程号</th><th>名称</th><th>教师</th><th>学分</th>
            <th>容量占用</th><th>状态</th><th></th>
          </tr></thead>
          <tbody>
            ${list.map((c) => {
              const pct = Math.min(100, Math.round((c.selectedCount / c.capacity) * 100));
              const fillCls = pct >= 100 ? 'danger' : pct >= 70 ? 'warn' : '';
              return el`<tr>
                <td data-label="课程号"><span class="mono">${esc(c.courseCode)}</span></td>
                <td data-label="名称">${esc(c.name)}</td>
                <td data-label="教师">${esc(c.teacher)}</td>
                <td data-label="学分" class="num">${esc(c.credit)}</td>
                <td data-label="容量" class="num">
                  <div class="mini-progress"><div class="progress-row">
                    <div class="progress" style="width:72px"><div class="fill ${fillCls}" style="width:${pct}%"></div></div>
                    <span class="progress-count">${esc(c.selectedCount)}/${esc(c.capacity)}</span>
                  </div></div>
                </td>
                <td data-label="状态"><span class="badge ${c.status === 1 ? 'badge-ok' : 'badge-danger'}">${c.status === 1 ? '开放' : '关闭'}</span></td>
                <td data-label="" style="text-align:right">
                  <button class="btn btn-ghost btn-sm" data-drop="${esc(c.id)}">退选</button>
                </td>
              </tr>`;
            }).join('')}
          </tbody>
        </table>
      </div>

      <p class="xs muted">注：<code class="mono">selectedCount</code> 在 Stage 3 后由 RabbitMQ 异步累加落库，允许短暂落后于 Redis 中的实际名额（最终一致性边界）。</p>
    </div>`;
}
