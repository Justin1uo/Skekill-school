/* ==========================================================================
 * api.js · 统一 fetch 封装
 *
 * 后端事实（决定本文件所有设计，勿改）：
 * 1. 所有业务失败也返回 HTTP 200，结果只体现在 body 的 code —— 必须判 code!==0
 * 2. ★实测修正：未注册路径不会返回 404！Boot 3.2 的 NoResourceFoundException
 *    被 GlobalExceptionHandler 的 Exception.class 兜底捕获 → HTTP 200 + code 500
 *    （msg="系统异常"）。因此"接口未实现"的判定依据是：
 *    带 reserved 标记的契约保留接口收到 code 500 → not-implemented（附 Stage 提示）。
 *    代价：这些接口实现后若真出 500，会被误读为"未实现"——Stage 落地时后端返回
 *    真实 Result，前端此处按 reserved 名单收敛（已上线的接口摘除标记，如 listCourses）。
 *    （404/405 判定仍保留，作为兜底路径的次要信号。）
 * 3. POST /api/selection 的 data 是 19 位雪花 Long，JSON.parse 按 double 处理
 *    会丢末 3~4 位精度 —— 对该响应先做定向正则字符串化（safeParse）再解析。
 *    其余接口 id 均为 1~5000 小整数，无精度风险，不走 safeParse。
 * ========================================================================== */

/** 业务/网络/未实现 三态错误 */
export class ApiError extends Error {
  /**
   * @param {'biz'|'not-implemented'|'network'|'http'} kind
   */
  constructor(kind, { code = null, msg = '', path = '', hint = '' } = {}) {
    super(msg || kind);
    this.kind = kind;
    this.code = code;
    this.path = path;
    this.hint = hint; // 未实现接口的 Stage 提示
  }
}

/**
 * 雪花 ID 安全解析：把 JSON 文本中 16 位以上的裸整数值转成字符串再 parse。
 * 锚定「前置是 : 或 [ 或 , ，后随 , } ] 或空白」的长数字，
 * 对本项目全部响应形状无副作用（小整数不受影响）。
 */
function safeParse(text) {
  return JSON.parse(text.replace(/([:,[]\s*)(\d{16,})(\s*[,}\]])/g, '$1"$2"$3'));
}

/**
 * 统一请求。成功返回 Result.data；失败抛 ApiError。
 * @param opts.reserved 契约保留接口的 Stage 标签（如 'Stage 2'）。
 *        带此标记时，404/405 或 code=500"系统异常"都判定为 not-implemented。
 */
async function request(path, { method = 'GET', body = null, bigIntSafe = false, reserved = null } = {}) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    // fetch 抛 TypeError = 后端没启动 / 网络断
    throw new ApiError('network', { msg: '无法连接后端', path });
  }
  if (res.status === 404 || res.status === 405) {
    if (reserved) throw new ApiError('not-implemented', { path, hint: reserved });
    throw new ApiError('http', { msg: `HTTP ${res.status}`, path });
  }
  if (!res.ok) {
    throw new ApiError('http', { msg: `HTTP ${res.status}`, path });
  }
  const text = await res.text();
  let json;
  try {
    json = bigIntSafe ? safeParse(text) : JSON.parse(text);
  } catch {
    throw new ApiError('http', { msg: '响应不是合法 JSON', path });
  }
  if (json.code === undefined) {
    throw new ApiError('http', { msg: '响应格式异常（非 Result 结构）', path });
  }
  if (json.code !== 0) {
    // ★ 关键：HTTP 200 也可能是业务失败
    // ★ 实测修正：未注册路径 = 200 + code 500"系统异常"（GlobalExceptionHandler 兜底），
    //   reserved 接口据此判定"未实现"
    if (reserved && json.code === 500 && json.msg === '系统异常') {
      throw new ApiError('not-implemented', { path, hint: reserved });
    }
    throw new ApiError('biz', { code: json.code, msg: json.msg, path });
  }
  return json.data;
}

/* ---------- 错误码 → 用户文案（蓝图 ErrorCode 全量，含 v1 不可达的 1004~1007） ---------- */
export const ERROR_TEXT = {
  400:  ['参数错误', 'warn',  (msg) => msg || '请求参数不合法'],
  500:  ['服务器内部错误', 'error', () => '请查看后端日志定位'],
  1001: ['课程不存在', 'error', () => '课程 ID 无效，可选范围 1~50'],
  1002: ['名额已满', 'warn',  () => '该课程 100 个名额已抢完'],
  1003: ['重复选课', 'warn',  () => '你已选过这门课（判重/唯一索引拦截）'],
  1004: ['上课时间冲突', 'warn', () => '与已选课程时段重叠（Stage 3 生效）'],
  1005: ['学分超限', 'warn',  () => '总学分将超过 30 上限（Stage 3 生效）'],
  1006: ['系统繁忙，请稍后重试', 'warn', () => '触发限流/抢锁失败，快速失败是预期设计'],
  1007: ['选课尚未开放', 'info', () => 'Redis 未预热，请等待后端 Stage 2 的 warmup'],
};

/** 业务错误 → {title, level, detail}；未知 code 兜底，永不显示 undefined */
export function describeBizError(err) {
  const t = ERROR_TEXT[err.code];
  if (t) return { title: t[0], level: t[1], detail: t[2](err.msg) };
  return { title: `业务拒绝（code=${err.code}）`, level: 'error', detail: err.msg || '' };
}

/* ==========================================================================
 * 契约全量方法 —— 已实现 + 蓝图保留（注释标 Stage）。
 * change 的字段名是按蓝图语义自拟的，后端 Stage 3 落地若不同只改这一行。
 * ========================================================================== */

// —— 已实现 ——
export const selectCourse = (studentId, courseId) =>
  request('/api/selection', { method: 'POST', body: { studentId, courseId }, bigIntSafe: true });
export const getMyCourses = (studentId) =>
  request(`/api/selection/mine?studentId=${encodeURIComponent(studentId)}`);
export const genData = () =>
  request('/api/admin/gen-data', { method: 'POST' });
// 课程列表：后端已上线朴素 DB 版（Stage 2 只换内部缓存路径、契约不变），故摘掉 reserved 标记，
// 真实故障（code 500）将按 biz 错误处理而非误报"未实现"
export const listCourses = () => request('/api/courses');

// actuator/health 不是 Result 结构，单独解析
export async function getHealth() {
  try {
    const res = await fetch('/actuator/health');
    const json = await res.json();
    return json.status; // "UP" / "DOWN" ...
  } catch {
    throw new ApiError('network', { msg: '无法连接后端', path: '/actuator/health' });
  }
}

// —— 契约保留（当前后端未注册 → 200+code500 被判定为 not-implemented）——
export const getCourse = (id) => request(`/api/courses/${id}`, { reserved: 'Stage 2' });
export const warmup = () => request('/api/admin/warmup', { method: 'POST', reserved: 'Stage 2' });
export const dropCourse = (courseId) => request(`/api/selection/${courseId}`, { method: 'DELETE', reserved: 'Stage 3（蓝图标注可砍）' }); // Stage 3 可砍
export const changeCourse = (dropId, addId) =>
  request('/api/selection/change', { method: 'POST', body: { dropCourseId: dropId, addCourseId: addId }, reserved: 'Stage 3（蓝图标注可砍）' }); // Stage 3 可砍
