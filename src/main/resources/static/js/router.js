/* ==========================================================================
 * router.js · hash 路由 + 守卫
 * 路由表：#/identity #/courses #/mine #/admin #/styleguide
 * 守卫：courses/mine 要求 store 中有合法 studentId，否则弹回 identity?next=
 * ========================================================================== */

const routes = new Map();       // name -> {render, guard}
let currentCleanup = null;      // 视图销毁钩子（路由切换即重建，无 keep-alive）

export function register(name, render, guard = null) {
  routes.set(name, { render, guard });
}

export function navigate(hash) {
  location.hash = hash;
}

function parseHash() {
  const raw = (location.hash || '').replace(/^#\/?/, '');
  const [path, query] = raw.split('?');
  const params = new URLSearchParams(query || '');
  return { name: path || defaultRoute(), params };
}

/** 根路径默认落点：有身份 → courses，无 → identity */
let defaultResolver = () => 'identity';
export function setDefaultRouteResolver(fn) { defaultResolver = fn; }
function defaultRoute() { return defaultResolver(); }

async function onHashChange() {
  const { name, params } = parseHash();
  const route = routes.get(name);
  if (!route) { navigate('#/courses'); return; }

  if (route.guard && !route.guard(params)) return; // 守卫内部已 redirect

  if (currentCleanup) { try { currentCleanup(); } catch { /* noop */ } currentCleanup = null; }
  const app = document.getElementById('app');
  app.innerHTML = '';
  const cleanup = await route.render(app, params);
  currentCleanup = typeof cleanup === 'function' ? cleanup : null;

  document.dispatchEvent(new CustomEvent('route:changed', { detail: { name } }));
}

export function startRouter() {
  window.addEventListener('hashchange', onHashChange);
  onHashChange();
}

/** 守卫辅助：不通过则跳 identity 并带上 next 回跳参数 */
export function requireIdentity(params, guardFn) {
  if (guardFn()) return true;
  const next = parseHash().name;
  navigate(`#/identity?next=${encodeURIComponent(next)}`);
  return false;
}
