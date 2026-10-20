import { h, clear, fmt, toast, appendAll } from '../dom.js';
import { api } from '../api.js';
import { app } from '../app.js';
import { donut, barRows, histogram, legend, meter, seriesColor, STATUS } from '../charts.js';
import { categoryName, behaviorBadge } from './generate.js';
import { statusBadge } from './runs.js';

/** Shown wherever a pass rate could not be measured because nothing was graded. Never rendered as 0%. */
const NA = 'N/A';
const graded = (m) => (m.passed || 0) + (m.failed || 0);
const rateText = (x, digits) => (x == null ? NA : fmt.pct(x, digits));
const ciText = (m) => (m.passRateLow == null ? 'not enough data' : `95% CI ${fmt.pct(m.passRateLow, 0)}–${fmt.pct(m.passRateHigh, 0)}`);
const notGradedText = (m) => `${fmt.int(m.errors)} error${m.errors === 1 ? '' : 's'} · ${fmt.int(m.skipped || 0)} skipped`;

const EVIDENCE = {
  DETERMINISTIC: ['verified', 'badge-verified', 'Verified by code: parsing, schema validation and exact comparison.'],
  AI_JUDGE: ['AI judge', 'badge-ai', 'Relied on an AI judge. Useful, but not ground truth.'],
  HEURISTIC: ['heuristic', 'badge-heuristic', 'Relied on a word-overlap heuristic because no judge was configured.'],
};

export async function renderReport(el, id) {
  if (id === 'demo') {
    draw(el, await api.demo());
    return;
  }
  let report = await api.run(id, false);
  if (report.status !== 'RUNNING') {
    draw(el, await api.run(id, true));
    return;
  }
  let stopped = false;
  app.cleanup = () => { stopped = true; };
  const live = h('div');
  el.append(live);
  while (!stopped) {
    drawLive(live, report);
    await new Promise((r) => setTimeout(r, 1200));
    if (stopped) return;
    try {
      report = await api.run(id, false);
    } catch (e) {
      toast(e.message, 'error');
    }
    if (report.status !== 'RUNNING') {
      clear(el);
      draw(el, await api.run(id, true));
      return;
    }
  }
}

function drawLive(el, r) {
  clear(el);
  const pct = r.total ? r.completed / r.total : 0;
  el.append(
    h('div', { class: 'page-head' },
      h('div', {}, h('div', { class: 'eyebrow' }, 'Running'), h('h1', {}, r.suiteName),
        h('p', {}, `${r.settings.targets.join(' vs ')} · ${r.settings.repeats} repeat(s) · budget $${r.settings.maxCostUsd}`)),
      h('button', { class: 'btn btn-danger', onclick: async () => {
        try { await api.cancelRun(r.id); toast('Cancelling: in-flight calls will finish first'); } catch (e) { toast(e.message, 'error'); }
      } }, 'Cancel run')),
    h('div', { class: 'card stack' },
      h('div', { class: 'row' }, h('span', { class: 'spinner' }), h('strong', {}, `${r.completed} of ${r.total} calls complete`),
        h('span', { class: 'spacer' }), h('span', { class: 'muted' }, r.message || fmt.pct(pct, 0))),
      h('div', { class: 'progress' }, h('div', { style: { width: pct * 100 + '%' } })),
      h('div', { class: 'grid grid-4' }, r.models.map((m, i) => h('div', { class: 'kpi' },
        h('div', { class: 'kpi-label row', style: { gap: '6px' } }, h('span', { class: 'swatch', style: { background: seriesColor(i) } }), m.model),
        h('div', { class: 'kpi-value' }, graded(m) ? fmt.pct(m.passRate) : NA),
        h('div', { class: 'kpi-sub' }, `${m.passed} passed · ${m.failed} failed · ${notGradedText(m)}`))))),
    h('div', { class: 'section' }, h('h2', {}, 'Latest results'),
      h('div', { class: 'table-wrap' }, h('table', {}, h('tbody', {}, [...r.results].reverse().map((x) => h('tr', {},
        h('td', {}, verdictBadge(x.evaluation.verdict)),
        h('td', { class: 'mono small' }, x.model),
        h('td', {}, categoryName(x.category)),
        h('td', { class: 'input-cell' }, h('div', { class: 'truncate' }, x.input)),
        h('td', { class: 'num muted' }, fmt.ms(x.latencyMs)))))))));
}

function draw(el, report) {
  const models = report.models;
  const state = { model: models.length > 1 ? 'all' : models[0]?.model, filters: { verdict: 'FAIL', model: '', category: '', failure: '', q: '' } };
  if (!report.results.some((r) => r.evaluation.verdict === 'FAIL')) state.filters.verdict = '';
  const byId = new Map(report.results.map((r) => [r.id, r]));
  const colorOf = (model) => seriesColor(Math.max(0, models.findIndex((m) => m.model === model)));

  const duration = report.finishedAt && report.startedAt ? (new Date(report.finishedAt) - new Date(report.startedAt)) / 1000 : null;
  appendAll(el,
    h('div', { class: 'page-head' },
      h('div', {},
        h('div', { class: 'eyebrow' }, report.demo ? 'Demo report' : 'Reliability report'),
        h('h1', { class: 'row', style: { gap: '12px' } }, report.suiteName, statusBadge(report.status, report.models)),
        h('p', {}, `${fmt.date(report.startedAt)}${duration != null ? ` · ${Math.round(duration)} s` : ''} · ${report.settings.targets.length} target(s) · `
          + `${report.settings.repeats} repeat(s) · judge: ${report.settings.judgeModel || 'none'}`)),
      h('div', { class: 'row' },
        report.demo ? null : h('a', { class: 'btn', href: `#/run?suite=${report.suiteId}` }, 'Run again'),
        h('button', { class: 'btn', onclick: () => download(report) }, 'Export JSON'))),
    report.demo ? h('div', { class: 'banner' }, h('span', { class: 'icon' }, '◆'),
      h('div', {}, h('strong', {}, 'Sample data. '), report.message)) : null,
    !report.demo ? runBanner(report) : null,
  );

  const body = h('div');
  el.append(body);

  const explorer = h('div', { class: 'section', id: 'explorer' });

  function render() {
    clear(body);
    if (models.length > 1) {
      body.append(h('div', { class: 'chips', style: { marginBottom: '14px' } },
        h('button', { class: 'chip' + (state.model === 'all' ? ' on' : ''), onclick: () => { state.model = 'all'; render(); } }, 'All models'),
        models.map((m, i) => h('button', { class: 'chip' + (state.model === m.model ? ' on' : ''), onclick: () => { state.model = m.model; render(); } },
          h('span', { class: 'swatch', style: { background: seriesColor(i), marginRight: '6px' } }), m.model))));
    }
    const selected = state.model === 'all' ? combine(models) : models.find((m) => m.model === state.model);
    body.append(kpis(selected, report));
    body.append(h('div', { class: 'grid grid-2 section' }, outcomeCard(selected), categoryCard(models, state.model, colorOf)));
    if (models.length > 1) body.append(comparison(models, report));
    body.append(h('div', { class: 'grid grid-2 section' }, latencyCard(report, models, state.model), failureTypeCard(selected)));
    body.append(analysisSection(report, byId, (failure) => {
      state.filters = { ...state.filters, verdict: 'FAIL', failure, model: state.model === 'all' ? '' : state.model };
      drawExplorer();
      explorer.scrollIntoView({ behavior: 'smooth' });
    }, (id) => openDrawer(byId.get(id), report)));
    body.append(explorer);
    drawExplorer();
  }

  function drawExplorer() {
    clear(explorer);
    const f = state.filters;
    const rows = report.results.filter((r) =>
      (!f.verdict || r.evaluation.verdict === f.verdict)
      && (!f.model || r.model === f.model)
      && (!f.category || r.category === f.category)
      && (!f.failure || r.evaluation.failureType === f.failure)
      && (!f.q || (r.input + ' ' + (r.rawOutput || '')).toLowerCase().includes(f.q.toLowerCase())));
    const sel = (key, options, label) => h('select', { onchange: (e) => { f[key] = e.target.value; drawExplorer(); } },
      h('option', { value: '' }, label), options.map(([v, l]) => h('option', { value: v, selected: f[key] === v }, l)));
    const categories = [...new Set(report.results.map((r) => r.category))];
    const failures = [...new Set(report.results.map((r) => r.evaluation.failureType).filter(Boolean))];
    const shown = rows.slice(0, 300);
    explorer.append(
      h('h2', {}, 'Test results'),
      h('div', { class: 'filters' },
        sel('verdict', [['PASS', 'Passed'], ['FAIL', 'Failed'], ['ERROR', 'Errors'], ['SKIPPED', 'Skipped']], 'All verdicts'),
        models.length > 1 ? sel('model', models.map((m) => [m.model, m.model]), 'All models') : null,
        sel('category', categories.map((c) => [c, categoryName(c)]), 'All categories'),
        sel('failure', failures.map((x) => [x, failureLabel(report, x)]), 'All failure types'),
        h('input', { type: 'text', placeholder: 'Search input or output…', value: f.q,
          oninput: (e) => { f.q = e.target.value; clearTimeout(drawExplorer.t); drawExplorer.t = setTimeout(drawExplorer, 200); } }),
        h('span', { class: 'count-note' }, `${rows.length} result${rows.length === 1 ? '' : 's'}${rows.length > shown.length ? `, first ${shown.length} shown` : ''}`)),
      rows.length ? h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['Verdict', models.length > 1 ? 'Model' : null, 'Category', 'Input', 'Why', 'Evidence', 'Latency']
          .filter(Boolean).map((x) => h('th', { class: x === 'Latency' ? 'num' : null }, x)))),
        h('tbody', {}, shown.map((r) => h('tr', { class: 'clickable', onclick: () => openDrawer(r, report) },
          h('td', {}, verdictBadge(r.evaluation.verdict)),
          models.length > 1 ? h('td', { class: 'mono small nowrap' }, h('span', { class: 'swatch', style: { background: colorOf(r.model), marginRight: '6px' } }), r.model) : null,
          h('td', { class: 'small' }, categoryName(r.category), r.repeat > 0 ? h('span', { class: 'muted' }, ` · run ${r.repeat + 1}`) : null),
          h('td', { class: 'input-cell' }, h('div', { class: 'truncate mono small' }, r.input)),
          h('td', { class: 'small text-2', style: { maxWidth: '320px' } },
            h('div', { class: 'truncate' }, r.evaluation.verdict === 'PASS' ? '—' : r.evaluation.summary)),
          h('td', {}, evidenceBadge(r.evaluation.verification)),
          h('td', { class: 'num small muted' }, r.rawOutput == null ? '–' : fmt.ms(r.latencyMs))))))) : h('div', { class: 'card empty' }, 'No results match these filters.'));
  }

  render();
}

function combine(models) {
  const sum = (k) => models.reduce((a, m) => a + (m[k] || 0), 0);
  const passed = sum('passed');
  const failed = sum('failed');
  const byFailureType = {};
  models.forEach((m) => Object.entries(m.byFailureType || {}).forEach(([k, v]) => { byFailureType[k] = (byFailureType[k] || 0) + v; }));
  const priced = models.some((m) => m.costUsd != null);
  return {
    model: 'all', total: sum('total'), passed, failed, errors: sum('errors'), skipped: sum('skipped'),
    passRate: passed + failed ? passed / (passed + failed) : null,
    verifiedPasses: sum('verifiedPasses'), judgedPasses: sum('judgedPasses'), heuristicPasses: sum('heuristicPasses'),
    byFailureType, inputTokens: sum('inputTokens'), outputTokens: sum('outputTokens'),
    costUsd: priced ? models.reduce((a, m) => a + (m.costUsd || 0), 0) : null,
    latency: { p50: Math.max(...models.map((m) => m.latency.p50)), p95: Math.max(...models.map((m) => m.latency.p95)) },
    consistency: null, combined: true,
  };
}

function passColorOf(rate) {
  if (rate == null) return STATUS.muted;
  return rate >= 0.9 ? STATUS.pass : rate >= 0.7 ? STATUS.error : STATUS.fail;
}

function kpis(m, report) {
  const measured = graded(m) > 0;
  const passColor = passColorOf(m.passRate);
  return h('div', { class: 'kpis' },
    kpi('Tests executed', fmt.int(m.total), `${fmt.int(graded(m))} graded · ${notGradedText(m)} (not counted)`),
    h('div', { class: 'kpi' }, h('div', { class: 'kpi-label' }, 'Pass rate'),
      h('div', { class: 'kpi-value', style: { color: passColor } }, rateText(m.passRate)),
      !measured ? h('div', { class: 'kpi-sub' }, 'not enough data: no response was graded')
        : m.combined ? h('div', { class: 'kpi-sub' }, 'across all models')
          : [meter(m.passRate, m.passRateLow, m.passRateHigh, passColor), h('div', { class: 'kpi-sub' }, ciText(m))]),
    kpi('Passed / failed', `${m.passed} / ${m.failed}`, m.passed ? `${fmt.pct(m.verifiedPasses / m.passed, 0)} of passes verified by code` : 'no passes to verify'),
    !(m.combined ? m.latency.p50 : m.latency.max)
      ? kpi('Latency p50', '–', 'no responses received')
      : kpi('Latency p50', fmt.ms(m.latency.p50), `p95 ${fmt.ms(m.latency.p95)}`),
    kpi('Estimated cost', fmt.usd(m.costUsd), `${fmt.tokens(m.inputTokens)} in · ${fmt.tokens(m.outputTokens)} out`
      + (report.judge?.costUsd != null && m.combined ? ` · judge ${fmt.usd(report.judge.costUsd)}` : '')),
    m.combined ? null : kpi('Consistency', m.consistency == null ? '–' : fmt.pct(m.consistency, 0),
      m.consistency == null ? 'run with repeats > 1 to measure' : 'tests with identical output on every repeat'));
}

function kpi(label, value, sub) {
  return h('div', { class: 'kpi' }, h('div', { class: 'kpi-label' }, label), h('div', { class: 'kpi-value' }, value),
    sub ? h('div', { class: 'kpi-sub' }, sub) : null);
}

function outcomeCard(m) {
  const segments = [
    { label: 'Passed: verified by code', value: m.verifiedPasses, color: STATUS.pass },
    { label: 'Passed: AI judge', value: m.judgedPasses, color: '#5fbf5f' },
    { label: 'Passed: heuristic', value: m.heuristicPasses, color: '#9ad49a' },
    { label: 'Failed', value: m.failed, color: STATUS.fail },
    { label: 'Error (not graded)', value: m.errors, color: STATUS.error },
    { label: 'Skipped (not sent)', value: m.skipped || 0, color: STATUS.muted },
  ];
  return h('div', { class: 'card' },
    h('div', { class: 'card-head' }, h('h3', {}, 'Outcomes'), h('span', { class: 'hint' }, 'and the evidence behind each pass')),
    h('div', { class: 'donut-wrap' },
      donut({ segments, center: rateText(m.passRate, 0), sub: graded(m) ? 'pass rate' : 'nothing graded' }),
      h('div', { class: 'donut-legend' }, segments.filter((x) => x.value > 0 || x.label === 'Failed').map((x) => h('div', { class: 'legend-item' },
        h('span', { class: 'swatch', style: { background: x.color } }), x.label, h('span', { class: 'val' }, fmt.int(x.value)))))));
}

function categoryCard(models, selected, colorOf) {
  const shown = selected === 'all' ? models : models.filter((m) => m.model === selected);
  const categories = new Set();
  shown.forEach((m) => Object.keys(m.byCategory || {}).forEach((c) => categories.add(c)));
  const order = (app.categories || []).map((c) => c.id);
  const rows = [...categories].sort((a, b) => order.indexOf(a) - order.indexOf(b)).map((c) => ({
    label: categoryName(c),
    values: shown.map((m) => {
      const s = m.byCategory[c];
      if (!s) return { value: 0, text: '–', color: colorOf(m.model) };
      return { value: s.passRate ?? 0, text: rateText(s.passRate, 0), color: colorOf(m.model),
        tip: [`${categoryName(c)} · ${m.model}`,
          `${s.passed} passed, ${s.failed} failed${s.errors ? `, ${s.errors} errors` : ''}${s.skipped ? `, ${s.skipped} skipped` : ''}`,
          s.passRate == null ? 'Pass rate N/A: nothing in this category was graded' : `Pass rate ${fmt.pct(s.passRate)}`] };
    }),
  }));
  return h('div', { class: 'card' },
    h('div', { class: 'card-head' }, h('h3', {}, 'Pass rate by category'), h('span', { class: 'hint' }, 'Where the model breaks')),
    shown.length > 1 ? legend(shown.map((m) => ({ label: m.model, color: colorOf(m.model) }))) : null,
    barRows(rows));
}

function comparison(models, report) {
  const best = (key, dir) => {
    const vals = models.map((m) => (typeof key === 'function' ? key(m) : m[key])).filter((v) => v != null);
    return dir > 0 ? Math.max(...vals) : Math.min(...vals);
  };
  const metrics = [
    ['Pass rate', (m) => m.passRate, (v, m) => (v == null ? NA : `${fmt.pct(v)}  (${fmt.pct(m.passRateLow, 0)}–${fmt.pct(m.passRateHigh, 0)})`), 1],
    ['Verified passes', (m) => (m.passed ? m.verifiedPasses / m.passed : null), (v) => fmt.pct(v, 0), 1],
    ['Consistency', (m) => m.consistency, (v) => (v == null ? '–' : fmt.pct(v, 0)), 1],
    ['Latency p50', (m) => m.latency.p50, (v) => fmt.ms(v), -1],
    ['Latency p95', (m) => m.latency.p95, (v) => fmt.ms(v), -1],
    ['Estimated cost', (m) => m.costUsd, (v) => fmt.usd(v), -1],
    ['Cost per passed test', (m) => (m.costUsd != null && m.passed ? m.costUsd / m.passed : null), (v) => (v == null ? 'n/a' : '$' + v.toFixed(5)), -1],
    ['Errors', (m) => m.errors, (v) => fmt.int(v), -1],
    ['Skipped', (m) => m.skipped || 0, (v) => fmt.int(v), -1],
  ];
  return h('div', { class: 'section' }, h('h2', {}, 'Model comparison'),
    h('p', { class: 'muted small', style: { marginBottom: '10px' } }, 'Identical inputs for every model. Overlapping 95% intervals mean the difference may be noise; add tests or repeats to separate them.'),
    h('div', { class: 'table-wrap' }, h('table', { class: 'compare' },
      h('thead', {}, h('tr', {}, h('th', {}, 'Metric'), models.map((m, i) => h('th', { class: 'num' },
        h('span', { class: 'swatch', style: { background: seriesColor(i), marginRight: '6px' } }), m.model)))),
      h('tbody', {}, metrics.map(([label, get, show, dir]) => {
        const b = models.length > 1 ? best(get, dir) : null;
        return h('tr', {}, h('td', { class: 'text-2' }, label), models.map((m) => {
          const v = get(m);
          return h('td', { class: 'num' + (v != null && v === b ? ' best' : '') }, show(v, m));
        }));
      })))));
}

function latencyCard(report, models, selected) {
  const shown = selected === 'all' ? models : models.filter((m) => m.model === selected);
  const series = shown.map((m) => ({
    name: m.model, color: seriesColor(models.indexOf(m)),
    values: report.results.filter((r) => r.model === m.model && r.rawOutput != null).map((r) => r.latencyMs),
  }));
  return h('div', { class: 'card' },
    h('div', { class: 'card-head' }, h('h3', {}, 'Response time distribution'), h('span', { class: 'hint' }, 'responses per latency bucket')),
    series.length > 1 ? legend(series.map((s) => ({ label: s.name, color: s.color }))) : null,
    histogram(series));
}

function failureTypeCard(m) {
  const entries = Object.entries(m.byFailureType || {}).sort((a, b) => b[1] - a[1]);
  const max = Math.max(1, ...entries.map((e) => e[1]));
  return h('div', { class: 'card' },
    h('div', { class: 'card-head' }, h('h3', {}, 'Failure types'), h('span', { class: 'hint' }, 'classified by deterministic rules')),
    entries.length ? barRows(entries.map(([type, n]) => ({
      label: failureLabel(null, type),
      values: [{ value: n, max, text: String(n), color: STATUS.fail, tip: [failureLabel(null, type), `${n} failure${n === 1 ? '' : 's'}`] }],
    }))) : h('div', { class: 'empty' }, noFailuresText(m)));
}

/** Empty-state text for "no classified failures", which only means success when something was graded. */
function noFailuresText(m) {
  if (!graded(m)) return 'Nothing to classify: no response was graded. See Run problems below.';
  if (m.errors || m.skipped) return `No model failures among ${fmt.int(graded(m))} graded responses, but ${notGradedText(m)} were not graded.`;
  return 'No failures among the graded responses.';
}

function runBanner(report) {
  const outcome = report.analysis?.outcome;
  if (outcome === 'NOTHING_GRADED') {
    return h('div', { class: 'banner bad' }, h('span', { class: 'icon' }, '!'),
      h('div', {}, h('strong', {}, 'No results to evaluate. '), report.message || 'No response could be graded, so no pass rate was measured.'));
  }
  if (report.status === 'COMPLETED_WITH_ERRORS' || report.status === 'FAILED') {
    return h('div', { class: 'banner' }, h('span', { class: 'icon' }, '!'),
      h('div', {}, h('strong', {}, report.status === 'FAILED' ? 'Run failed. ' : 'Finished with errors. '), report.message || ''));
  }
  return report.message ? h('div', { class: 'banner info' }, h('span', { class: 'icon' }, 'i'), h('div', {}, report.message)) : null;
}

function analysisSection(report, byId, onShow, onOpen) {
  const a = report.analysis;
  if (!a) return h('div');
  const wrap = h('div', { class: 'section' });
  if (a.issues?.length) wrap.append(issuesSection(a.issues, byId, onOpen));
  wrap.append(h('h2', {}, 'Failure analysis'));
  if (!a.clusters.length) {
    const text = {
      ALL_PASSED: 'Every test passed. Try harder categories or more cases per category.',
      PASSED_WITH_ISSUES: 'Every graded response passed, but some calls errored or were skipped (see Run problems), so this is not a full pass.',
      NOTHING_GRADED: 'No model failures to analyse because no response was graded. Fix the run problems above and run again.',
    }[a.outcome] || 'No failures were classified.';
    wrap.append(h('div', { class: 'card empty' }, text));
    return wrap;
  }
  if (a.aiInsights) wrap.append(aiPanel(a.aiInsights));
  wrap.append(h('div', { class: 'grid grid-2' }, a.clusters.map((c) => h('div', { class: 'cluster' },
    h('div', { class: 'cluster-head' }, h('span', { class: 'cluster-count bad' }, c.count), h('h3', {}, c.label),
      h('span', { class: 'spacer' }), h('span', { class: 'muted small' }, `${fmt.pct(c.share, 0)} of failures`)),
    h('p', {}, c.description),
    h('div', { class: 'chips', style: { marginTop: '8px' } },
      Object.entries(c.categories).map(([k, n]) => h('span', { class: 'badge' }, `${k} · ${n}`)),
      Object.keys(c.byModel).length > 1 ? Object.entries(c.byModel).map(([k, n]) => h('span', { class: 'badge mono' }, `${k} · ${n}`)) : null),
    h('div', { class: 'suggestion' }, h('strong', {}, 'Suggested fix '), h('span', { class: 'badge', style: { marginRight: '6px' } }, 'rule-based'), c.suggestion),
    h('div', { class: 'stack', style: { gap: '6px', marginTop: '10px' } },
      c.exampleIds.slice(0, 3).map((id) => {
        const r = byId.get(id);
        return r ? h('a', { href: 'javascript:void(0)', class: 'small mono truncate', onclick: () => onOpen(id) }, '↳ ' + r.input) : null;
      })),
    h('div', { style: { marginTop: '10px' } }, h('button', { class: 'btn btn-sm', onclick: () => onShow(c.type) }, `Show all ${c.count} →`))))));
  return wrap;
}

/** Call errors and skipped calls: problems with the run, not evidence about the model. */
function issuesSection(issues, byId, onOpen) {
  return h('div', { style: { marginBottom: '24px' } },
    h('h2', {}, 'Run problems'),
    h('p', { class: 'muted small', style: { marginBottom: '10px' } }, 'These calls produced nothing to grade, so they are excluded from every pass rate.'),
    h('div', { class: 'grid grid-2' }, issues.map((g) => h('div', { class: 'cluster' },
      h('div', { class: 'cluster-head' }, h('span', { class: 'cluster-count ' + (g.skipped ? 'muted-count' : 'warn') }, g.count), h('h3', {}, g.label),
        h('span', { class: 'spacer' }), h('span', { class: 'badge ' + (g.skipped ? 'badge-skipped' : 'badge-error') }, g.skipped ? 'not sent' : 'call error')),
      h('p', {}, g.description),
      Object.keys(g.byModel).length > 1 ? h('div', { class: 'chips', style: { marginTop: '8px' } },
        Object.entries(g.byModel).map(([k, n]) => h('span', { class: 'badge mono' }, `${k} · ${n}`))) : null,
      g.firstMessage ? h('pre', { class: 'code small', style: { marginTop: '10px', whiteSpace: 'pre-wrap' } }, g.firstMessage) : null,
      h('div', { class: 'stack', style: { gap: '6px', marginTop: '10px' } },
        g.exampleIds.slice(0, 2).map((id) => {
          const r = byId.get(id);
          return r ? h('a', { href: 'javascript:void(0)', class: 'small mono truncate', onclick: () => onOpen(id) }, '↳ ' + r.input) : null;
        }))))));
}

function aiPanel(ai) {
  if (ai.error) {
    return h('div', { class: 'card ai-panel', style: { marginBottom: '16px' } },
      h('div', { class: 'row' }, h('span', { class: 'badge badge-ai' }, 'AI-generated'), h('span', { class: 'muted' }, ai.error)));
  }
  return h('div', { class: 'card ai-panel stack', style: { marginBottom: '16px' } },
    h('div', { class: 'row' }, h('span', { class: 'badge badge-ai' }, 'AI-generated analysis'),
      h('span', { class: 'muted small' }, `by ${ai.model}. Advisory: verify before applying.`)),
    ai.summary ? h('p', {}, ai.summary) : null,
    ai.recommendations?.length ? h('div', { class: 'stack' }, ai.recommendations.map((r) => h('div', { class: 'suggestion' },
      h('strong', {}, r.title), ' ', (r.addresses || []).map((x) => h('span', { class: 'badge', style: { marginRight: '4px' } }, x)),
      h('div', { style: { marginTop: '4px' } }, r.detail)))) : null,
    ai.promptAdditions ? h('div', {}, h('div', { class: 'small muted', style: { marginBottom: '6px' } }, 'Suggested additions to the system prompt'),
      h('pre', { class: 'mono' }, ai.promptAdditions)) : null);
}

function openDrawer(r, report) {
  if (!r) return;
  const root = clear(document.getElementById('drawer-root'));
  const close = () => { clear(root); document.removeEventListener('keydown', onKey); };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  const ev = r.evaluation;
  const diffKeys = new Set((ev.differences || []).map((d) => topKey(d.path)));
  const expected = r.expectedBehavior === 'MATCH_EXPECTED' ? r.expectedOutput
    : r.expectedBehavior === 'ASK_CLARIFICATION' ? report.suite?.task?.clarificationOutput : report.suite?.task?.refusalOutput;

  root.append(
    h('div', { class: 'drawer-backdrop', onclick: close }),
    h('aside', { class: 'drawer', role: 'dialog', 'aria-label': 'Test result' },
      h('div', { class: 'row' }, verdictBadge(ev.verdict), evidenceBadge(ev.verification), h('span', { class: 'spacer' }),
        h('button', { class: 'btn btn-ghost', onclick: close, 'aria-label': 'Close' }, '✕')),
      h('h2', { style: { marginTop: '10px' } }, ev.summary),
      h('div', { class: 'kv', style: { marginTop: '14px' } },
        kv('Model', r.model), kv('Category', categoryName(r.category)), kv('Expected behaviour', behaviorBadge(r.expectedBehavior)),
        kv('Latency', r.rawOutput == null ? '–' : fmt.ms(r.latencyMs)), kv('Tokens', `${fmt.tokens(r.inputTokens)} in · ${fmt.tokens(r.outputTokens)} out`),
        kv('Cost', fmt.usd(r.costUsd)), kv('Stop reason', r.stopReason || '–'), kv('Repeat', String(r.repeat + 1))),
      h('h3', {}, 'Input sent to the model'),
      h('pre', { class: 'code' }, r.input),
      r.expectationSource === 'AI_PROPOSED' ? h('div', { class: 'banner info', style: { marginTop: '10px' } }, h('span', { class: 'icon' }, 'i'),
        h('div', {}, 'The expected output for this test was proposed by the generator because the variation changes the meaning. Check that it is right.')) : null,
      h('div', { class: 'grid grid-2' },
        h('div', {}, h('h3', {}, r.expectedBehavior === 'MATCH_EXPECTED' ? 'Expected output' : 'Expected pattern'),
          expected ? jsonBlock(expected, diffKeys) : h('pre', { class: 'code' }, r.expectedBehavior === 'REFUSE' ? 'A refusal' : 'A clarifying question')),
        h('div', {}, h('h3', {}, 'Actual output (parsed)'),
          ev.parsed ? jsonBlock(ev.parsed, diffKeys) : h('pre', { class: 'code' }, ev.verdict === 'SKIPPED' ? '— not sent —' : r.rawOutput == null ? '— no response —' : '— no JSON found —'))),
      ev.differences?.length ? [h('h3', {}, 'Field differences'), h('div', { class: 'checks' }, ev.differences.map((d) => h('div', { class: 'check-item' },
        h('span', { class: 'check-icon fail' }, '≠'), h('div', {}, h('div', { class: 'mono small' }, d.path),
          h('div', { class: 'check-detail' }, describeDiff(d))), h('span', { class: 'badge' }, d.kind.toLowerCase().replace('_', ' ')))))] : null,
      h('h3', {}, 'Checks'),
      ev.checks.length ? h('div', { class: 'checks' }, ev.checks.map((c) => h('div', { class: 'check-item' },
        h('span', { class: 'check-icon ' + (c.passed ? 'pass' : 'fail') }, c.passed ? '✓' : '✕'),
        h('div', {}, h('div', {}, c.name), h('div', { class: 'check-detail' }, c.detail),
          c.confidence != null ? h('div', { class: 'muted small' }, `judge confidence ${fmt.pct(c.confidence, 0)} (self-reported, uncalibrated)`) : null),
        evidenceBadge(c.method)))) : h('div', { class: 'muted' }, r.error || 'No checks ran.'),
      h('h3', {}, 'Raw response'),
      h('pre', { class: 'code' }, r.rawOutput ?? r.error ?? ''),
      ...(rationale(report, r))));
}

function rationale(report, r) {
  const test = report.suite?.cases?.find((c) => c.id === r.testId);
  if (!test?.rationale) return [];
  return [h('h3', {}, 'Why this expectation'), h('p', { class: 'text-2' }, test.rationale)];
}

function jsonBlock(value, diffKeys) {
  const pre = h('pre', { class: 'code' });
  const text = JSON.stringify(value, null, 2);
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    text.split('\n').forEach((line) => {
      const m = line.match(/^ {2}"([^"]+)":/);
      pre.append(h('span', { class: m && diffKeys.has(m[1]) ? 'diff' : 'ok' }, line), m && diffKeys.has(m[1]) ? '' : '\n');
    });
  } else {
    pre.textContent = text;
  }
  return pre;
}

function topKey(path) {
  const m = /^\$\.([^.[]+)/.exec(path || '');
  return m ? m[1] : '$';
}

function describeDiff(d) {
  const show = (v) => (v === undefined || v === null ? 'nothing' : JSON.stringify(v));
  switch (d.kind) {
    case 'MISSING': return `Expected ${show(d.expected)}, but the field is missing.`;
    case 'UNEXPECTED': return `Unexpected value ${show(d.actual)} not present in the expected output.`;
    case 'TYPE_MISMATCH': return `Expected ${show(d.expected)} but got ${show(d.actual)} (different JSON type).`;
    default: return `Expected ${show(d.expected)} but got ${show(d.actual)}.`;
  }
}

function kv(k, v) {
  return h('div', {}, h('div', { class: 'k' }, k), h('div', { class: 'v' }, v));
}

function verdictBadge(v) {
  return h('span', { class: 'badge badge-' + v.toLowerCase() }, { PASS: '✓ pass', FAIL: '✕ fail', ERROR: '! error', SKIPPED: '– skipped' }[v] || v);
}

function evidenceBadge(method) {
  if (!method) return h('span', { class: 'muted small' }, '–');
  const [label, cls, title] = EVIDENCE[method];
  return h('span', { class: 'badge ' + cls, title }, label);
}

const FAILURE_LABELS = {
  UNEXPECTED_REFUSAL: 'Unexpected refusal', TRUNCATED: 'Truncated output', INVALID_STRUCTURE: 'Invalid output structure',
  PROMPT_INJECTION: 'Followed injected instructions', AMBIGUITY_HANDLING: 'Ambiguity handling',
  NEGATION_MISUNDERSTANDING: 'Negation misunderstanding', MISSING_INFORMATION: 'Missing information',
  HALLUCINATED_INFORMATION: 'Hallucinated information', INSTRUCTION_MISINTERPRETATION: 'Incorrect interpretation',
  SEMANTIC_MISMATCH: 'Free-text mismatch', FORMATTING: 'Formatting error',
};

function failureLabel(_report, type) {
  return FAILURE_LABELS[type] || type;
}

function download(report) {
  const blob = new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' });
  const a = h('a', { href: URL.createObjectURL(blob), download: `promptfuzzer-${report.id}.json` });
  document.body.append(a);
  a.click();
  a.remove();
}
