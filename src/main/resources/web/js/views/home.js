import { h } from '../dom.js';
import { app } from '../app.js';

const FLOW = [
  ['Define task', 'Your system prompt, the JSON shape you expect, and a few seed examples with correct answers.'],
  ['Generate tests', 'An LLM writes paraphrases, typos, slang, negations, ambiguity and injection attempts. Seeded rules add reproducible mutations.'],
  ['Run tests', 'Every input goes to the real model or your own endpoint, with rate limits, retries and a hard budget.'],
  ['Analyze failures', 'Responses are parsed, schema-validated and diffed field by field. An AI judge is used only where code cannot decide.'],
  ['Read the report', 'Pass rates with confidence intervals, failure clusters, side-by-side diffs and prompt fixes.'],
];

export function renderHome(el) {
  const hasKey = (app.status?.presets || []).some((p) => p.available && p.keyEnv && p.provider !== 'ENDPOINT');
  el.append(
    h('section', { class: 'hero' },
      h('div', { class: 'eyebrow' }, 'Fuzz testing for LLM applications'),
      h('h1', {}, 'Find out how your AI app breaks before your users do.'),
      h('p', {}, 'Prompts that work on carefully written input often fail on typos, slang, negation or ambiguity. '
        + 'PromptFuzzer generates hundreds of adversarial variations of your examples, runs them against a real model, '
        + 'and verifies every answer deterministically wherever it can.'),
      h('div', { class: 'row', style: { marginTop: '22px' } },
        h('a', { class: 'btn btn-primary', href: '#/task' }, 'Start a new test'),
        h('a', { class: 'btn', href: '#/demo' }, 'Explore the demo report'),
        hasKey ? null : h('span', { class: 'muted small' }, 'No API key detected. The demo works without one, and Gemini or Groq give free keys.'))),
    h('div', { class: 'flow' }, FLOW.map(([title, text], i) => h('div', { class: 'flow-step' },
      h('div', { class: 'n' }, `STEP ${i + 1}`), h('h3', {}, title), h('p', {}, text)))),
    h('div', { class: 'grid grid-3 section' },
      card('Deterministic first', 'JSON parsing, schema validation and structural diffs decide most verdicts. '
        + 'Each verdict says whether it was verified by code, accepted by an AI judge, or by a heuristic.'),
      card('Honest about uncertainty', 'Pass rates carry 95% Wilson intervals, repeated runs measure consistency, '
        + 'and AI-proposed expectations are flagged for review.'),
      card('Safe by default', 'Generated inputs are fenced as untrusted data, rendered as text only, and every run '
        + 'has request and cost ceilings. API keys never leave environment variables.')),
  );
}

function card(title, text) {
  return h('div', { class: 'card' }, h('h3', {}, title), h('p', { class: 'text-2', style: { marginTop: '6px' } }, text));
}
