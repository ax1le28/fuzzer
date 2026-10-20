import { h, clear, toast, fmt, appendAll } from '../dom.js';
import { api } from '../api.js';
import { app, navigate } from '../app.js';
import { store } from '../store.js';
import { seriesColor } from '../charts.js';
import { modelPicker, defaultModelConfig, presetFor, keyProblem, toRequest } from '../models.js';

export async function renderRun(el, _rest, params) {
  const suites = await api.suites().catch(() => []);
  const limits = app.status?.limits || { maxCostUsd: 2 };
  const cfg = store.get('runOptions') || {
    targets: [defaultModelConfig()],
    repeats: 1, concurrency: 4, maxOutputTokens: 4096, useJudge: true, judge: defaultModelConfig(), aiAnalysis: true,
    maxCostUsd: Math.min(1, limits.maxCostUsd),
  };
  if (!cfg.judge) cfg.judge = cfg.judgeModel ? { provider: 'ANTHROPIC', model: cfg.judgeModel } : defaultModelConfig();
  delete cfg.judgeModel;
  cfg.suiteId = params.get('suite') || store.get('lastSuite') || suites[0]?.id;
  const save = () => store.set('runOptions', { ...cfg, suiteId: undefined });

  el.append(h('div', { class: 'page-head' },
    h('div', {}, h('div', { class: 'eyebrow' }, 'Step 3'), h('h1', {}, 'Run the suite'),
      h('p', {}, 'Each test input is sent as the user message with your system prompt. Add up to four targets to compare models on identical inputs.'))));

  if (!suites.length) {
    el.append(h('div', { class: 'banner info' }, h('span', { class: 'icon' }, 'i'),
      h('div', {}, 'No saved suites yet. ', h('a', { href: '#/generate' }, 'Generate one first →'))));
    return;
  }

  const estimateBox = h('div', { class: 'stack' });
  const keyWarning = h('div', { class: 'field-error' });
  const targetsBox = h('div', { class: 'stack' });
  let timer;
  const refresh = () => { save(); clearTimeout(timer); timer = setTimeout(estimate, 250); };

  function drawTargets() {
    clear(targetsBox);
    cfg.targets.forEach((t, i) => {
      targetsBox.append(h('div', { class: 'row', style: { alignItems: 'flex-start', gap: '10px', flexWrap: 'nowrap' } },
        h('span', { class: 'swatch', style: { background: seriesColor(i), marginTop: '36px' } }),
        modelPicker(t, refresh, { endpoint: true }),
        h('button', { class: 'btn btn-ghost btn-sm', title: 'Remove target', disabled: cfg.targets.length === 1, style: { marginTop: '26px' },
          onclick: () => { cfg.targets.splice(i, 1); drawTargets(); refresh(); } }, '✕')));
    });
    if (cfg.targets.length < 4) {
      targetsBox.append(h('div', {}, h('button', { class: 'btn btn-sm', onclick: () => {
        cfg.targets.push(nextTarget()); drawTargets(); refresh();
      } }, '+ Compare another model')));
    }
  }

  /** Another model from the same provider as the last target, so a comparison is one click. */
  function nextTarget() {
    const last = cfg.targets[cfg.targets.length - 1];
    const used = new Set(cfg.targets.map((t) => t.model));
    const model = (presetFor(last)?.models || []).find((m) => !used.has(m)) || '';
    return { ...last, model };
  }

  function request() {
    return {
      suiteId: cfg.suiteId,
      targets: cfg.targets.map(toRequest),
      repeats: Number(cfg.repeats), concurrency: Number(cfg.concurrency), maxOutputTokens: Number(cfg.maxOutputTokens),
      judge: cfg.useJudge ? toRequest(cfg.judge) : null,
      aiAnalysis: cfg.useJudge && cfg.aiAnalysis,
      maxCostUsd: Number(cfg.maxCostUsd),
    };
  }

  async function estimate() {
    clear(estimateBox);
    const models = [...cfg.targets, ...(cfg.useJudge ? [cfg.judge] : [])];
    keyWarning.textContent = models.map(keyProblem).find(Boolean) || '';
    try {
      const e = await api.estimate(request());
      appendAll(estimateBox,
        h('div', { class: 'kv' },
          kv('Target calls', fmt.int(e.calls)),
          kv('Judge calls (max)', fmt.int(e.judgeCalls)),
          kv('Input tokens', fmt.tokens(e.inputTokens)),
          kv('Typical cost', e.typicalUsd == null ? 'unknown' : fmt.usd(e.typicalUsd)),
          kv('Worst case', e.worstUsd == null ? 'unknown' : fmt.usd(e.worstUsd))),
        e.unpriced.length ? h('div', { class: 'muted small' }, `No per-token price is known for ${e.unpriced.join(', ')}, so it is not in the estimate. `
          + `On a free tier such as Gemini or Groq these calls cost nothing, and every run stays capped at ${limits.maxRequests ?? 600} requests.`) : null,
        h('div', { class: 'muted small' }, `Estimates assume ~3.5 characters per token. The run stops sending requests once $${cfg.maxCostUsd} is spent.`),
        e.typicalUsd != null && e.typicalUsd > cfg.maxCostUsd
          ? h('div', { class: 'field-error' }, 'The typical cost exceeds your budget: the run will stop early and skip remaining tests.') : null);
    } catch (err) {
      appendAll(estimateBox,h('div', { class: 'field-error' }, err.message));
    }
  }

  const startBtn = h('button', { class: 'btn btn-primary', onclick: async () => {
    startBtn.disabled = true;
    try {
      const run = await api.startRun(request());
      navigate('report/' + run.id);
    } catch (e) {
      toast('Could not start: ' + e.message, 'error');
      startBtn.disabled = false;
    }
  } }, 'Start run');

  el.append(
    h('div', { class: 'grid', style: { gridTemplateColumns: 'minmax(0, 2fr) minmax(280px, 1fr)', alignItems: 'start' } },
      h('div', { class: 'stack' },
        h('div', { class: 'card' }, field('Test suite', h('select', { onchange: (e) => { cfg.suiteId = e.target.value; store.set('lastSuite', cfg.suiteId); refresh(); } },
          suites.map((s) => h('option', { value: s.id, selected: s.id === cfg.suiteId }, `${s.name} · ${s.cases} tests · ${fmt.date(s.createdAt)}`))))),
        h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Targets'),
          h('span', { class: 'hint' }, 'API keys are read from environment variables only')), targetsBox),
        h('div', { class: 'card' }, h('div', { class: 'card-head' }, h('h3', {}, 'Execution')),
          h('div', { class: 'grid grid-4' },
            numberField('Repeats', cfg.repeats, 1, 5, (v) => { cfg.repeats = v; refresh(); }, 'Measures consistency across identical calls.'),
            numberField('Concurrency', cfg.concurrency, 1, 8, (v) => { cfg.concurrency = v; refresh(); }, `Rate limit: ${app.status?.limits?.requestsPerMinute ?? '?'} req/min.`),
            numberField('Max output tokens', cfg.maxOutputTokens, 256, 32000, (v) => { cfg.maxOutputTokens = v; refresh(); }, 'Thinking models reason within this budget.'),
            numberField('Budget (USD)', cfg.maxCostUsd, 0.01, limits.maxCostUsd, (v) => { cfg.maxCostUsd = v; refresh(); }, `Server ceiling: $${limits.maxCostUsd}.`, 0.05))),
        h('div', { class: 'card stack' }, h('div', { class: 'card-head' }, h('h3', {}, 'Evaluation'),
          h('span', { class: 'hint' }, 'Deterministic checks always run first')),
          h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: cfg.useJudge, onchange: (e) => { cfg.useJudge = e.target.checked; refresh(); } }),
            'Use an AI judge for free-text fields and for clarification or refusal checks without a JSON pattern'),
          h('div', { class: 'lbl', style: { fontWeight: 550 } }, 'Judge model'),
          modelPicker(cfg.judge, refresh, { effort: false }),
          h('label', { class: 'check' },
            h('input', { type: 'checkbox', checked: cfg.aiAnalysis, onchange: (e) => { cfg.aiAnalysis = e.target.checked; save(); } }),
            'Write an AI failure analysis after the run (uses the judge model)'),
          h('div', { class: 'muted small' }, 'Without a judge, free-text fields fall back to a word-overlap heuristic and are labelled as such.'))),
      h('div', { class: 'card stack', style: { position: 'sticky', top: '20px' } },
        h('h3', {}, 'Cost estimate'), estimateBox, startBtn, keyWarning)));
  drawTargets();
  estimate();
}

function field(label, control) {
  return h('label', { class: 'field' }, h('span', { class: 'lbl' }, label), control);
}

function numberField(label, value, min, max, onChange, help, step = 1) {
  return h('label', { class: 'field' }, h('span', { class: 'lbl' }, label),
    h('input', { type: 'number', value, min, max, step, oninput: (e) => {
      const v = Math.min(max, Math.max(min, Number(e.target.value)));
      if (!Number.isNaN(v)) onChange(v);
    } }), help ? h('small', {}, help) : null);
}

function kv(k, v) {
  return h('div', {}, h('div', { class: 'k' }, k), h('div', { class: 'v' }, v));
}
