// Thin client for the local PromptFuzzer API.

async function request(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { error: text }; }
  if (!res.ok) throw new Error((data && data.error) || `${res.status} ${res.statusText}`);
  return data;
}

export const api = {
  status: () => request('GET', '/api/status'),
  categories: () => request('GET', '/api/categories'),
  examples: () => request('GET', '/api/examples'),
  demo: () => request('GET', '/api/demo'),
  suites: () => request('GET', '/api/suites'),
  suite: (id) => request('GET', `/api/suites/${encodeURIComponent(id)}`),
  saveSuite: (suite) => request('PUT', `/api/suites/${encodeURIComponent(suite.id)}`, suite),
  deleteSuite: (id) => request('DELETE', `/api/suites/${encodeURIComponent(id)}`),
  generate: (req) => request('POST', '/api/suites/generate', req),
  estimate: (req) => request('POST', '/api/runs/estimate', req),
  startRun: (req) => request('POST', '/api/runs', req),
  runs: () => request('GET', '/api/runs'),
  run: (id, results = false) => request('GET', `/api/runs/${encodeURIComponent(id)}${results ? '?results=true' : ''}`),
  cancelRun: (id) => request('POST', `/api/runs/${encodeURIComponent(id)}/cancel`, {}),
  deleteRun: (id) => request('DELETE', `/api/runs/${encodeURIComponent(id)}`),
};
