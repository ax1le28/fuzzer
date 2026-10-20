import { api } from './api.js';
import { h, clear, toast } from './dom.js';
import { renderHome } from './views/home.js';
import { renderTask } from './views/task.js';
import { renderGenerate } from './views/generate.js';
import { renderRun } from './views/run.js';
import { renderRuns } from './views/runs.js';
import { renderReport } from './views/report.js';

export const app = {
  status: null,
  categories: [],
  cleanup: null,
};

const routes = {
  '': renderHome,
  task: renderTask,
  generate: renderGenerate,
  run: renderRun,
  runs: renderRuns,
  report: renderReport,
  demo: (el) => renderReport(el, 'demo'),
};

export function navigate(path) {
  location.hash = '#/' + path;
}

async function route() {
  const [name, ...rest] = location.hash.replace(/^#\/?/, '').split('/');
  const [base, query] = name.split('?');
  const params = new URLSearchParams(query || '');
  const view = document.getElementById('view');
  if (app.cleanup) { app.cleanup(); app.cleanup = null; }
  document.querySelectorAll('.nav a').forEach((a) => {
    const r = a.dataset.route;
    a.classList.toggle('active', r === (base || 'home') || (base === 'report' && r === 'runs'));
  });
  clear(view);
  const render = routes[base] || renderHome;
  try {
    await render(view, decodeURIComponent(rest.join('/')), params);
  } catch (e) {
    console.error(e);
    view.append(h('div', { class: 'banner' }, h('span', { class: 'icon' }, '!'), h('div', {}, e.message)));
  }
  view.focus({ preventScroll: true });
  window.scrollTo(0, 0);
}

function renderKeyStatus() {
  const el = clear(document.getElementById('key-status'));
  const ok = app.status?.providers?.ANTHROPIC;
  el.append(
    h('div', { class: ok ? 'key-ok' : 'key-missing' }, ok ? '● Anthropic key found' : '● No API key: demo only'),
    h('div', { style: { marginTop: '4px' } },
      `Budget ceiling $${app.status?.limits?.maxCostUsd ?? '?'} per run`));
}

async function boot() {
  try {
    [app.status, app.categories] = await Promise.all([api.status(), api.categories()]);
  } catch (e) {
    toast('Cannot reach the PromptFuzzer server: ' + e.message, 'error');
  }
  renderKeyStatus();
  window.addEventListener('hashchange', route);
  route();
}

boot();
