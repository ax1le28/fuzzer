import { h, clear } from './dom.js';
import { app } from './app.js';

// Provider presets come from /api/status; this fallback only matters if the server is unreachable.
const FALLBACK = [{ id: 'anthropic', label: 'Anthropic', provider: 'ANTHROPIC', keyEnv: 'ANTHROPIC_API_KEY', models: ['claude-opus-5-5'], available: false }];
let pickerCount = 0;

export function presets() {
  return app.status?.presets || FALLBACK;
}

/** The preset a saved config belongs to. Hand-written OpenAI-compatible configs map to "openai". */
export function presetFor(cfg) {
  const all = presets();
  if (cfg.provider === 'ANTHROPIC') return all.find((p) => p.id === 'anthropic');
  if (cfg.provider === 'ENDPOINT') return all.find((p) => p.id === 'endpoint');
  const url = (cfg.baseUrl || '').replace(/\/+$/, '');
  return all.find((p) => p.provider === 'OPENAI_COMPATIBLE' && p.id !== 'openai' && p.baseUrl === url)
    || all.find((p) => p.id === 'openai');
}

export function defaultModelConfig() {
  const t = app.status?.defaultTarget;
  return t ? { provider: t.provider, model: t.model, baseUrl: t.baseUrl, apiKeyEnv: t.apiKeyEnv } : { provider: 'ANTHROPIC', model: 'claude-opus-5-5' };
}

/** Applies a preset to a config in place, keeping the model when it still fits. */
export function applyPreset(cfg, preset) {
  const keepModel = presetFor(cfg)?.id === preset.id;
  for (const k of ['baseUrl', 'apiKeyEnv', 'effort', 'name', 'responsePointer']) delete cfg[k];
  cfg.provider = preset.provider;
  if (preset.provider !== 'ENDPOINT') cfg.model = keepModel ? cfg.model : (preset.models[0] || '');
  if (preset.baseUrl && preset.provider !== 'ANTHROPIC') cfg.baseUrl = preset.baseUrl;
  if (preset.provider === 'OPENAI_COMPATIBLE') cfg.apiKeyEnv = preset.keyEnv || 'none';
}

/** Whether a config can run with the keys the server has; null when that cannot be known (custom key variable). */
export function keyProblem(cfg) {
  const p = presetFor(cfg);
  if (!p || p.available) return null;
  if (p.id === 'openai' || p.id === 'endpoint') {
    if ((cfg.apiKeyEnv || p.keyEnv) !== p.keyEnv) return null;
    if (p.id === 'endpoint') return null; // endpoints may not need auth
    if (/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])[:/]/.test(cfg.baseUrl || '')) return null;
  }
  return `${p.keyEnv} is not set. Export it and restart PromptFuzzer to use ${p.label}.`;
}

/** Request payload for a config: drops UI-only and empty fields. */
export function toRequest(cfg) {
  const out = {};
  for (const [k, v] of Object.entries(cfg)) if (v !== '' && v != null) out[k] = v;
  return out;
}

/**
 * Provider, model and connection fields for one model config, edited in place. `onChange` fires on every edit.
 */
export function modelPicker(cfg, onChange, { endpoint = false, effort = true } = {}) {
  const box = h('div', { class: 'stack', style: { gap: '6px', flex: '1', minWidth: '0' } });
  const listId = 'models-' + (++pickerCount);

  const draw = () => {
    clear(box);
    const preset = presetFor(cfg);
    const options = presets().filter((p) => endpoint || p.provider !== 'ENDPOINT');
    const set = (key) => (e) => { cfg[key] = e.target.value; onChange(); };
    const fields = [
      field('Provider', h('select', { onchange: (e) => { applyPreset(cfg, options.find((p) => p.id === e.target.value)); draw(); onChange(); } },
        options.map((p) => h('option', { value: p.id, selected: p.id === preset.id }, p.label + (p.available || p.id === 'openai' ? '' : ' · key not set'))))),
    ];
    if (cfg.provider === 'ENDPOINT') {
      fields.push(
        field('Name', h('input', { type: 'text', value: cfg.name || '', placeholder: 'my-app', oninput: set('name') })),
        field('URL (receives {"input": …})', h('input', { type: 'url', value: cfg.baseUrl || '', placeholder: 'http://localhost:8000/parse', oninput: set('baseUrl') })),
        field('Reply JSON pointer', h('input', { type: 'text', value: cfg.responsePointer || '', placeholder: '/output', oninput: set('responsePointer') })));
    } else {
      fields.push(field('Model', h('input', { type: 'text', value: cfg.model || '', list: listId, placeholder: preset.models[0] || 'model id', oninput: set('model') })));
      if (preset.id === 'openai') {
        fields.push(
          field('Base URL', h('input', { type: 'url', value: cfg.baseUrl || '', placeholder: 'https://api.openai.com/v1', oninput: set('baseUrl') })),
          field('Key variable', h('input', { type: 'text', value: cfg.apiKeyEnv || '', placeholder: 'OPENAI_API_KEY or none', oninput: set('apiKeyEnv') })));
      } else if (cfg.provider === 'ANTHROPIC' && effort) {
        fields.push(field('Effort', h('select', { onchange: (e) => { cfg.effort = e.target.value || null; onChange(); } },
          ['', 'low', 'medium', 'high'].map((v) => h('option', { value: v, selected: (cfg.effort || '') === v }, v || 'model default')))));
      }
    }
    const columns = fields.length === 4 ? '230px 1fr 1fr 150px' : fields.length === 3 ? '230px 1fr 150px' : '230px 1fr';
    box.append(h('div', { class: 'grid', style: { gridTemplateColumns: columns, alignItems: 'end', gap: '10px' } }, fields),
      h('datalist', { id: listId }, preset.models.map((m) => h('option', { value: m }))));
    const problem = keyProblem(cfg);
    box.append(problem ? h('div', { class: 'field-error' }, problem) : h('div', { class: 'muted small' }, preset.note));
  };
  draw();
  return box;
}

function field(label, control) {
  return h('label', { class: 'field' }, h('span', { class: 'lbl' }, label), control);
}
