import { h, clear, toast } from '../dom.js';
import { api } from '../api.js';
import { store } from '../store.js';
import { navigate } from '../app.js';

export function emptyTask() {
  return {
    name: '', description: '', systemPrompt: '', outputSchema: null, clarificationOutput: null, refusalOutput: null,
    semanticFields: [], ignoredFields: [], examples: [{ input: '', expectedOutput: null }],
    comparison: { caseInsensitiveStrings: true, numericTolerance: 1e-6, unorderedArrays: true, nullEqualsMissing: true, strictFormatting: true },
  };
}

export function currentTask() {
  return store.get('task') || null;
}

/** Returns problems with the task; empty when it can be used. */
export function validateTask(t) {
  const problems = [];
  if (!t) return ['No task defined yet.'];
  if (!t.name?.trim()) problems.push('Give the task a name.');
  if (!t.systemPrompt?.trim()) problems.push('Add the system prompt being tested.');
  if (!t.examples?.length) problems.push('Add at least one seed example.');
  (t.examples || []).forEach((e, i) => {
    if (!e.input?.trim()) problems.push(`Seed ${i + 1} needs an input.`);
    if (e.expectedOutput == null) problems.push(`Seed ${i + 1} needs a valid expected JSON output.`);
  });
  return problems;
}

export async function renderTask(el) {
  let task = currentTask() || emptyTask();
  const examples = await api.examples().catch(() => []);
  const save = () => store.set('task', task);

  const errorsBox = h('div');
  const form = h('div', { class: 'stack' });

  function rebuild() {
    clear(form);
    form.append(
      h('div', { class: 'grid grid-2' },
        textField('Task name', task.name, (v) => { task.name = v; save(); }, 'e.g. Smart home command parser'),
        textField('Description', task.description, (v) => { task.description = v; save(); },
          'What the app does. Given to the test generator and judge for context.')),
      h('label', { class: 'field' }, h('span', { class: 'lbl' }, 'System prompt under test'),
        h('small', {}, 'The instructions the target model receives. Every test input is sent as the user message.'),
        area(task.systemPrompt, 9, (v) => { task.systemPrompt = v; save(); })),
      h('div', { class: 'grid grid-3' },
        jsonField('Output JSON schema', task.outputSchema, (v) => { task.outputSchema = v; save(); },
          'Subset of JSON Schema: type, properties, required, enum, additionalProperties, ranges, pattern.', 8),
        jsonField('Clarification pattern', task.clarificationOutput, (v) => { task.clarificationOutput = v; save(); },
          'Fields a response must contain to count as asking for clarification, e.g. {"action": "clarify"}. Empty: the AI judge decides.', 3),
        jsonField('Refusal pattern', task.refusalOutput, (v) => { task.refusalOutput = v; save(); },
          'Fields a response must contain to count as a refusal, e.g. {"action": "reject"}. Empty: the AI judge decides.', 3)),
      h('div', { class: 'grid grid-2' },
        textField('Free-text fields', task.semanticFields.join(', '), (v) => { task.semanticFields = list(v); save(); },
          'Fields compared by meaning (AI judge or heuristic) rather than exact value, e.g. title, question.'),
        textField('Ignored fields', task.ignoredFields.join(', '), (v) => { task.ignoredFields = list(v); save(); },
          'Fields excluded from comparison entirely.')),
      comparisonOptions(),
      seedsSection(),
    );
  }

  function comparisonOptions() {
    const c = task.comparison;
    const opt = (key, label) => h('label', { class: 'check' },
      h('input', { type: 'checkbox', checked: !!c[key], onchange: (e) => { c[key] = e.target.checked; save(); } }), label);
    return h('div', { class: 'card' },
      h('div', { class: 'card-head' }, h('h3', {}, 'Comparison rules'), h('span', { class: 'hint' }, 'How strictly outputs are compared')),
      h('div', { class: 'row', style: { gap: '22px' } },
        opt('strictFormatting', 'Require raw JSON (no markdown fences or prose)'),
        opt('caseInsensitiveStrings', 'Case-insensitive strings'),
        opt('unorderedArrays', 'Arrays compared as sets'),
        opt('nullEqualsMissing', 'null equals missing')));
  }

  function seedsSection() {
    const rows = h('div', { class: 'examples-list' });
    task.examples.forEach((ex, i) => {
      rows.append(h('div', { class: 'example-row' },
        h('label', { class: 'field' }, h('span', { class: 'lbl' }, `Seed ${i + 1} input`),
          area(ex.input, 3, (v) => { ex.input = v; save(); }, 'mono-off')),
        jsonField('Expected output', ex.expectedOutput, (v) => { ex.expectedOutput = v; save(); }, null, 3),
        h('button', { class: 'btn btn-ghost btn-sm', style: { marginTop: '26px' }, title: 'Remove seed',
          disabled: task.examples.length === 1, onclick: () => { task.examples.splice(i, 1); save(); rebuild(); } }, '✕')));
    });
    return h('div', { class: 'card' },
      h('div', { class: 'card-head' }, h('h3', {}, 'Seed examples'),
        h('span', { class: 'hint' }, 'Known-good input/output pairs. Every generated test derives from one of these.')),
      rows,
      h('button', { class: 'btn btn-sm', style: { marginTop: '12px' },
        onclick: () => { task.examples.push({ input: '', expectedOutput: null }); save(); rebuild(); } }, '+ Add seed'));
  }

  const picker = h('select', { onchange: (e) => {
    const ex = examples.find((x) => x.id === e.target.value);
    if (!ex) return;
    task = structuredClone(ex);
    save();
    rebuild();
    toast(`Loaded example “${ex.name}”`, 'success');
  } }, h('option', { value: '' }, 'Load an example task…'), examples.map((x) => h('option', { value: x.id }, x.name)));

  el.append(
    h('div', { class: 'page-head' },
      h('div', {}, h('div', { class: 'eyebrow' }, 'Step 1'), h('h1', {}, 'Define the task'),
        h('p', {}, 'Describe the AI application under test. Structured JSON output is supported today; the evaluator compares it field by field.')),
      h('div', { class: 'row' }, picker,
        h('button', { class: 'btn btn-ghost', onclick: () => { task = emptyTask(); save(); rebuild(); } }, 'Clear'),
        h('button', { class: 'btn btn-primary', onclick: () => {
          const problems = validateTask(task);
          clear(errorsBox);
          if (problems.length) {
            errorsBox.append(h('div', { class: 'banner' }, h('span', { class: 'icon' }, '!'),
              h('div', {}, problems.map((p) => h('div', {}, p)))));
            return;
          }
          navigate('generate');
        } }, 'Next: generate tests →'))),
    errorsBox,
    form);
  rebuild();
}

function list(v) {
  return v.split(',').map((x) => x.trim()).filter(Boolean);
}

function textField(label, value, onInput, help) {
  return h('label', { class: 'field' }, h('span', { class: 'lbl' }, label),
    h('input', { type: 'text', value: value || '', oninput: (e) => onInput(e.target.value) }),
    help ? h('small', {}, help) : null);
}

function area(value, rows, onInput) {
  return h('textarea', { rows, spellcheck: 'false', oninput: (e) => onInput(e.target.value) }, value || '');
}

/** A textarea holding JSON; reports parse errors inline and only stores valid values. */
function jsonField(label, value, onValid, help, rows = 4) {
  const err = h('div', { class: 'field-error' });
  const ta = h('textarea', { rows, spellcheck: 'false' }, value == null ? '' : JSON.stringify(value, null, 2));
  ta.addEventListener('input', () => {
    const text = ta.value.trim();
    if (!text) { ta.classList.remove('invalid'); err.textContent = ''; onValid(null); return; }
    try {
      const parsed = JSON.parse(text);
      ta.classList.remove('invalid');
      err.textContent = '';
      onValid(parsed);
    } catch (e) {
      ta.classList.add('invalid');
      err.textContent = 'Invalid JSON: ' + e.message;
    }
  });
  return h('label', { class: 'field' }, h('span', { class: 'lbl' }, label), ta, err, help ? h('small', {}, help) : null);
}
