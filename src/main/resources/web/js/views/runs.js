import { h, fmt, toast } from '../dom.js';
import { api } from '../api.js';
import { navigate } from '../app.js';
import { STATUS } from '../charts.js';

export async function renderRuns(el) {
  const runs = await api.runs();
  el.append(h('div', { class: 'page-head' },
    h('div', {}, h('div', { class: 'eyebrow' }, 'Step 4'), h('h1', {}, 'Reports'),
      h('p', {}, 'Every run is saved as JSON under the data directory, so results can be reopened, shared and compared.')),
    h('a', { class: 'btn', href: '#/demo' }, 'Open demo report')));

  if (!runs.length) {
    el.append(h('div', { class: 'card empty' }, 'No runs yet. ', h('a', { href: '#/run' }, 'Start one →'), ' or explore the ',
      h('a', { href: '#/demo' }, 'demo report'), '.'));
    return;
  }
  el.append(h('div', { class: 'table-wrap' }, h('table', {},
    h('thead', {}, h('tr', {}, ['Started', 'Suite', 'Status', 'Models and pass rate', 'Tests', ''].map((x) => h('th', {}, x)))),
    h('tbody', {}, runs.map((r) => h('tr', { class: 'clickable', onclick: () => navigate('report/' + r.id) },
      h('td', { class: 'muted' }, fmt.date(r.startedAt)),
      h('td', {}, r.suiteName),
      h('td', {}, statusBadge(r.status)),
      h('td', {}, h('div', { class: 'stack', style: { gap: '4px' } }, (r.models || []).map((m) => h('div', { class: 'row', style: { gap: '8px' } },
        h('span', { class: 'mono small' }, m.model),
        h('span', { style: { color: m.passRate >= 0.9 ? STATUS.pass : m.passRate >= 0.7 ? STATUS.error : STATUS.fail, fontWeight: 600 } },
          m.passed + m.failed ? fmt.pct(m.passRate) : '–'))))),
      h('td', { class: 'num' }, `${r.completed}/${r.total}`),
      h('td', {}, r.status === 'RUNNING' ? null : h('button', { class: 'btn btn-ghost btn-sm', title: 'Delete report', onclick: async (e) => {
        e.stopPropagation();
        if (!confirm('Delete this report?')) return;
        try { await api.deleteRun(r.id); e.target.closest('tr').remove(); } catch (err) { toast(err.message, 'error'); }
      } }, '✕'))))))));
}

export function statusBadge(status) {
  const cls = { COMPLETED: 'badge-pass', RUNNING: 'badge-verified pulse', CANCELLED: 'badge-error', FAILED: 'badge-fail' }[status] || '';
  return h('span', { class: 'badge ' + cls }, status.toLowerCase());
}
