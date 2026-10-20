import { h, clear, toast, fmt, appendAll } from '../dom.js';
import { api } from '../api.js';
import { app, navigate } from '../app.js';
import { store } from '../store.js';
import { currentTask, validateTask } from './task.js';
import { modelPicker, defaultModelConfig, keyProblem, toRequest } from '../models.js';

export async function renderGenerate(el, _rest, params) {
  const task = currentTask();
  const problems = validateTask(task);
  const categories = (app.categories || []).filter((c) => c.id !== 'BASELINE');
  const state = store.get('genOptions') || {
    categories: categories.map((c) => c.id), perCategory: 4, includeRules: true, seed: 42,
    generator: defaultModelConfig(),
  };
  if (!state.generator) state.generator = state.model ? { provider: 'ANTHROPIC', model: state.model } : defaultModelConfig();
  delete state.model;
  const canGenerate = () => !keyProblem(state.generator);
  const saveState = () => store.set('genOptions', state);

  const suiteBox = h('div', { class: 'section' });
  const existing = await api.suites().catch(() => []);

  el.append(h('div', { class: 'page-head' },
    h('div', {}, h('div', { class: 'eyebrow' }, 'Step 2'), h('h1', {}, 'Generate test cases'),
      h('p', {}, 'A generator model writes variations of your seed examples, one batch per category. Every case is validated against your schema before it is accepted, and seeded rules add mutations that are reproducible without any API call.')),
    existing.length ? h('select', { style: { width: 'auto' }, onchange: (e) => e.target.value && showSuite(e.target.value) },
      h('option', { value: '' }, 'Open a saved suite…'),
      existing.map((s) => h('option', { value: s.id }, `${s.name} · ${s.cases} tests · ${fmt.date(s.createdAt)}`))) : null));

  if (problems.length) {
    el.append(h('div', { class: 'banner' }, h('span', { class: 'icon' }, '!'),
      h('div', {}, h('div', {}, 'Define a complete task first.'), problems.map((p) => h('div', { class: 'muted' }, p)),
        h('a', { href: '#/task' }, 'Go to step 1 →'))));
  } else {
    el.append(optionsCard());
  }
  el.append(suiteBox);
  if (params.get('suite')) showSuite(params.get('suite'));

  function optionsCard() {
    const chips = h('div', { class: 'chips' });
    const drawChips = () => {
      clear(chips);
      categories.forEach((c) => chips.append(h('button', {
        class: 'chip' + (state.categories.includes(c.id) ? ' on' : ''), title: c.description,
        onclick: () => {
          state.categories = state.categories.includes(c.id) ? state.categories.filter((x) => x !== c.id) : [...state.categories, c.id];
          saveState(); drawChips(); updateSummary();
        },
      }, c.name)));
    };
    drawChips();

    const perLabel = h('span', { class: 'lbl' });
    const summary = h('div', { class: 'muted small' });
    const updateSummary = () => {
      perLabel.textContent = `Cases per category: ${state.perCategory}`;
      const hasKey = canGenerate();
      const ai = hasKey ? state.categories.length * state.perCategory : 0;
      const rules = state.includeRules ? task.examples.length * 4 : 0;
      summary.textContent = `≈ ${ai + rules} tests: ${ai} AI-generated (${hasKey ? state.categories.length : 0} generator calls) + ${rules} rule-based.`;
    };
    updateSummary();

    const btn = h('button', { class: 'btn btn-primary', onclick: generate }, 'Generate suite');
    const generatorPicker = modelPicker(state.generator, () => { saveState(); updateSummary(); }, { effort: true });

    async function generate() {
      btn.disabled = true;
      const original = btn.textContent;
      clear(btn).append(h('span', { class: 'spinner' }), ' Generating…');
      try {
        const suite = await api.generate({
          task,
          generator: canGenerate() && state.perCategory > 0 ? toRequest(state.generator) : null,
          categories: canGenerate() ? state.categories : [],
          perCategory: canGenerate() ? state.perCategory : 0,
          includeRules: state.includeRules,
          seed: Number(state.seed) || 42,
        });
        store.set('lastSuite', suite.id);
        toast(`Generated ${suite.cases.length} test cases`, 'success');
        history.replaceState(null, '', `#/generate?suite=${suite.id}`);
        drawSuite(suite);
      } catch (e) {
        toast('Generation failed: ' + e.message, 'error');
      } finally {
        btn.disabled = false;
        btn.textContent = original;
      }
    }

    return h('div', { class: 'card stack' },
      h('div', { class: 'card-head' }, h('h3', {}, `Task: ${task.name}`),
        h('span', { class: 'hint' }, `${task.examples.length} seed example${task.examples.length === 1 ? '' : 's'}`)),
      h('div', {}, h('div', { class: 'lbl', style: { marginBottom: '8px', fontWeight: 550 } }, 'Generator model'), generatorPicker,
        h('div', { class: 'muted small', style: { marginTop: '4px' } }, 'Without a usable generator only rule-based tests are created. Free options: Gemini or Groq with a free key, or Ollama on your computer.')),
      h('div', {}, h('div', { class: 'lbl', style: { marginBottom: '8px', fontWeight: 550 } }, 'Categories'), chips),
      h('div', { class: 'grid grid-2' },
        h('label', { class: 'field' }, perLabel,
          h('input', { type: 'range', min: 1, max: 10, value: state.perCategory,
            oninput: (e) => { state.perCategory = Number(e.target.value); saveState(); updateSummary(); } })),
        h('label', { class: 'field' }, h('span', { class: 'lbl' }, 'Mutation seed'),
          h('input', { type: 'number', value: state.seed, oninput: (e) => { state.seed = e.target.value; saveState(); } }),
          h('small', {}, 'Same seed, same rule-based mutations.'))),
      h('div', { class: 'row' },
        h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: state.includeRules,
          onchange: (e) => { state.includeRules = e.target.checked; saveState(); updateSummary(); } }),
        'Include seed baselines and rule-based mutations (typos, casing, irrelevant context)'),
        h('span', { class: 'spacer' }), summary, btn));
  }

  async function showSuite(id) {
    try {
      drawSuite(await api.suite(id));
    } catch (e) {
      toast(e.message, 'error');
    }
  }

  function drawSuite(suite) {
    clear(suiteBox);
    let dirty = false;
    const saveBtn = h('button', { class: 'btn', disabled: true, onclick: async () => {
      try {
        suite = await api.saveSuite(suite);
        dirty = false;
        saveBtn.disabled = true;
        toast('Suite saved', 'success');
      } catch (e) { toast(e.message, 'error'); }
    } }, 'Save changes');
    const counts = {};
    suite.cases.forEach((c) => { counts[c.category] = (counts[c.category] || 0) + 1; });
    const review = suite.cases.filter((c) => c.expectationSource === 'AI_PROPOSED').length;
    const tbody = h('tbody');
    const draw = () => {
      clear(tbody);
      suite.cases.forEach((c, i) => tbody.append(h('tr', {},
        h('td', { class: 'num muted' }, i + 1),
        h('td', {}, categoryName(c.category)),
        h('td', { class: 'input-cell' }, h('div', { class: 'mono' }, c.input),
          c.rationale ? h('div', { class: 'muted small', style: { marginTop: '4px' } }, c.rationale) : null),
        h('td', {}, behaviorBadge(c.expectedBehavior),
          c.expectedOutput ? h('div', { class: 'mono small muted', style: { marginTop: '4px', maxWidth: '280px', overflowWrap: 'anywhere' } },
            JSON.stringify(c.expectedOutput)) : null),
        h('td', {}, h('div', { class: 'stack', style: { gap: '4px' } },
          h('span', { class: 'badge' + (c.origin === 'AI_GENERATED' ? ' badge-ai' : '') }, originLabel(c.origin)),
          c.expectationSource === 'AI_PROPOSED' ? h('span', { class: 'badge badge-review', title: 'The generator changed the expected output for this variation. Check it is right.' }, 'review expectation') : null)),
        h('td', {}, h('button', { class: 'btn btn-ghost btn-sm', title: 'Remove test', onclick: () => {
          suite.cases.splice(i, 1); dirty = true; saveBtn.disabled = false; draw();
        } }, '✕')))));
    };
    draw();

    const warnings = suite.generation?.warnings || [];
    appendAll(suiteBox,
      h('div', { class: 'card-head' },
        h('div', {}, h('h2', {}, suite.name), h('div', { class: 'muted small' },
          `${suite.cases.length} tests · generator ${suite.generation?.generatorModel || 'none (rule-based)'} · seed ${suite.generation?.seed ?? '–'}`
          + (suite.generation?.costUsd != null ? ` · generation cost ≈ ${fmt.usd(suite.generation.costUsd)}` : ''))),
        h('div', { class: 'row' }, saveBtn,
          h('button', { class: 'btn btn-primary', onclick: () => {
            if (dirty) { toast('Save your changes first', 'error'); return; }
            store.set('lastSuite', suite.id);
            navigate('run?suite=' + suite.id);
          } }, 'Next: run this suite →'))),
      h('div', { class: 'chips', style: { marginBottom: '12px' } },
        Object.entries(counts).map(([k, n]) => h('span', { class: 'badge' }, `${categoryName(k)} · ${n}`))),
      review ? h('div', { class: 'banner info' }, h('span', { class: 'icon' }, 'i'),
        h('div', {}, `${review} test${review === 1 ? '' : 's'} carry an expected output proposed by the generator because the variation changes the meaning. Review them before trusting the results.`)) : null,
      warnings.length ? h('details', { class: 'card', style: { marginBottom: '12px' } },
        h('summary', { class: 'muted' }, `${warnings.length} generation warning${warnings.length === 1 ? '' : 's'}`),
        h('ul', { class: 'small text-2' }, warnings.map((w) => h('li', {}, w)))) : null,
      h('div', { class: 'table-wrap' }, h('table', {},
        h('thead', {}, h('tr', {}, ['#', 'Category', 'Input', 'Expected', 'Source', ''].map((x) => h('th', {}, x)))),
        tbody)));
  }
}

export function categoryName(id) {
  return (app.categories || []).find((c) => c.id === id)?.name || id;
}

export function behaviorBadge(b) {
  const map = { MATCH_EXPECTED: ['Match output', ''], ASK_CLARIFICATION: ['Ask to clarify', 'badge-review'], REFUSE: ['Refuse', 'badge-fail'] };
  const [label, cls] = map[b] || [b, ''];
  return h('span', { class: 'badge ' + cls }, label);
}

function originLabel(o) {
  return { SEED: 'seed', RULE_BASED: 'rule-based', AI_GENERATED: 'AI-generated' }[o] || o;
}
