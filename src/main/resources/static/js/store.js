/* ==========================================================================
 * store.js · 最小状态层
 * 持久化仅 2 个 key（服务端是唯一事实源，课程/选课数据不落地）：
 *   skekill:studentId —— 身份
 *   skekill:lastHash  —— 刷新后回到原视图
 * mineDirty：选课/退选/gen-data 成功后置脏，视图进入时按需重拉。
 * ========================================================================== */

const KEY_ID = 'skekill:studentId';
const KEY_HASH = 'skekill:lastHash';

export const STUDENT_ID_RANGE = [1, 5000];
export const COURSE_ID_RANGE = [1, 50];
export const CREDIT_LIMIT = 30;

const listeners = new Set();
const state = {
  studentId: loadId(),
  mineDirty: true,
};

function loadId() {
  const raw = localStorage.getItem(KEY_ID);
  const n = parseInt(raw, 10);
  return Number.isInteger(n) && n >= STUDENT_ID_RANGE[0] && n <= STUDENT_ID_RANGE[1] ? n : null;
}

export function getState() { return { ...state }; }
export function hasIdentity() { return state.studentId !== null; }

export function studentNo(id = state.studentId) {
  return id == null ? '-' : `S${String(id).padStart(5, '0')}`;
}

export function setStudentId(id) {
  const n = parseInt(id, 10);
  if (!Number.isInteger(n) || n < STUDENT_ID_RANGE[0] || n > STUDENT_ID_RANGE[1]) {
    throw new RangeError(`studentId 需在 ${STUDENT_ID_RANGE[0]}~${STUDENT_ID_RANGE[1]}`);
  }
  state.studentId = n;
  state.mineDirty = true;
  localStorage.setItem(KEY_ID, String(n));
  emit();
}

export function clearIdentity() {
  state.studentId = null;
  state.mineDirty = true;
  localStorage.removeItem(KEY_ID);
  emit();
}

export function markMineDirty() {
  state.mineDirty = true;
  emit();
}
export function consumeMineDirty() {
  const d = state.mineDirty;
  state.mineDirty = false;
  return d;
}

/* 视图内内存缓存（不进 localStorage）：课程列表探测结果 */
export const memory = { courses: null };
export function invalidateCourses() { memory.courses = null; }

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}
function emit() { listeners.forEach((fn) => { try { fn(getState()); } catch (e) { console.error(e); } }); }

/* 最后路由记忆 */
window.addEventListener('hashchange', () => localStorage.setItem(KEY_HASH, location.hash));
export function getLastHash() { return localStorage.getItem(KEY_HASH); }
