// Small, dependency-free SVG/HTML charts. Every mark has a hover tooltip and every multi-series chart a
// legend, so identity is never carried by colour alone.
import { h, s, tooltip, fmt } from './dom.js';

// Categorical slots, dark-surface steps, fixed order (never cycled).
export const SERIES = ['#3987e5', '#d95926', '#199e70', '#c98500'];
export const STATUS = { pass: '#0ca30c', fail: '#d03b3b', error: '#fab219', muted: '#5f5e5a' };

export function seriesColor(i) {
  return SERIES[i % SERIES.length];
}

export function legend(items) {
  return h('div', { class: 'legend' },
    items.map((it) => h('span', { class: 'legend-item' },
      h('span', { class: 'swatch', style: { background: it.color } }), it.label)));
}

/** Donut with a centre figure. segments: [{label, value, color}] */
export function donut({ segments, center, sub, size = 168 }) {
  const total = segments.reduce((a, b) => a + b.value, 0) || 1;
  const r = size / 2 - 12;
  const c = size / 2;
  const svg = s('svg', { viewBox: `0 0 ${size} ${size}`, width: size, height: size, class: 'donut', role: 'img',
    'aria-label': segments.map((x) => `${x.label}: ${x.value}`).join(', ') });
  svg.append(s('circle', { cx: c, cy: c, r, fill: 'none', stroke: 'var(--grid)', 'stroke-width': 16 }));
  let angle = -Math.PI / 2;
  for (const seg of segments) {
    if (seg.value <= 0) continue;
    const frac = seg.value / total;
    const sweep = frac * Math.PI * 2;
    const path = frac >= 0.9999
      ? s('circle', { cx: c, cy: c, r, fill: 'none', stroke: seg.color, 'stroke-width': 16 })
      : s('path', { d: arc(c, r, angle, angle + sweep), fill: 'none', stroke: seg.color, 'stroke-width': 16,
        'stroke-linecap': 'butt', class: 'mark' });
    tooltip(path, [seg.label, `${fmt.int(seg.value)} (${fmt.pct(frac)})`]);
    svg.append(path);
    // 2px surface gap between segments.
    if (segments.filter((x) => x.value > 0).length > 1) {
      svg.append(s('path', { d: arc(c, r, angle + sweep - 0.012, angle + sweep + 0.012), fill: 'none',
        stroke: 'var(--surface)', 'stroke-width': 18 }));
    }
    angle += sweep;
  }
  svg.append(s('text', { x: c, y: c + 4, 'text-anchor': 'middle', class: 'donut-center' }, center));
  if (sub) svg.append(s('text', { x: c, y: c + 24, 'text-anchor': 'middle', class: 'donut-sub' }, sub));
  return svg;
}

function arc(c, r, a0, a1) {
  const x0 = c + r * Math.cos(a0), y0 = c + r * Math.sin(a0);
  const x1 = c + r * Math.cos(a1), y1 = c + r * Math.sin(a1);
  const large = a1 - a0 > Math.PI ? 1 : 0;
  return `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`;
}

/**
 * Horizontal bars, one row per label, one bar per series.
 * rows: [{label, values: [{value, max?, text, tip: [..], color}]}]
 */
export function barRows(rows, { max = 1, onClick } = {}) {
  return h('div', { class: 'bar-rows' }, rows.map((row) => h('div', {
    class: 'bar-row' + (onClick ? ' clickable' : ''), onclick: onClick ? () => onClick(row) : null,
  },
  h('div', { class: 'bar-label', title: row.label }, row.label),
  h('div', { class: 'bar-stack' }, row.values.map((v) => {
    const width = Math.max(0, Math.min(1, v.value / (v.max ?? max))) * 100;
    const bar = h('div', { class: 'bar-track' },
      h('div', { class: 'bar-fill', style: { width: width + '%', background: v.color } }));
    const wrap = h('div', { class: 'bar-line' }, bar, h('span', { class: 'bar-value' }, v.text));
    if (v.tip) tooltip(wrap, v.tip);
    return wrap;
  })))));
}

/** Latency histogram for one or more series. series: [{name, color, values: [ms]}] */
export function histogram(series, { width = 560, height = 190, bins = 14 } = {}) {
  const all = series.flatMap((x) => x.values);
  const svg = s('svg', { viewBox: `0 0 ${width} ${height}`, class: 'histogram', role: 'img',
    'aria-label': 'Latency distribution', preserveAspectRatio: 'none' });
  if (!all.length) return h('div', { class: 'empty' }, 'No latency data yet.');
  const pad = { l: 36, r: 8, t: 10, b: 28 };
  const lo = 0;
  const hi = niceCeil(percentileOf(all, 0.98) || 1);
  const step = hi / bins;
  const counts = series.map((x) => {
    const c = new Array(bins).fill(0);
    x.values.forEach((v) => { c[Math.min(bins - 1, Math.floor((v - lo) / step))]++; });
    return c;
  });
  const ymax = Math.max(1, ...counts.flat());
  const iw = width - pad.l - pad.r;
  const ih = height - pad.t - pad.b;
  const groupW = iw / bins;
  const barW = Math.max(2, (groupW - 2) / series.length - 2);

  for (let t = 0; t <= 4; t++) {
    const y = pad.t + ih - (ih * t) / 4;
    svg.append(s('line', { x1: pad.l, x2: width - pad.r, y1: y, y2: y, class: t === 0 ? 'axis' : 'grid' }));
    svg.append(s('text', { x: pad.l - 6, y: y + 4, 'text-anchor': 'end', class: 'tick' }, Math.round((ymax * t) / 4)));
  }
  for (let b = 0; b <= bins; b += Math.ceil(bins / 6)) {
    const x = pad.l + b * groupW;
    svg.append(s('text', { x, y: height - 8, 'text-anchor': 'middle', class: 'tick' }, fmt.ms(b * step)));
  }
  counts.forEach((c, si) => c.forEach((n, b) => {
    if (!n) return;
    const bh = (ih * n) / ymax;
    const x = pad.l + b * groupW + 1 + si * (barW + 2);
    const rect = s('rect', { x, y: pad.t + ih - bh, width: barW, height: bh, rx: 2, fill: series[si].color, class: 'mark' });
    tooltip(rect, [series[si].name, `${fmt.ms(b * step)} – ${fmt.ms((b + 1) * step)}`, `${n} response${n === 1 ? '' : 's'}`]);
    svg.append(rect);
  }));
  return svg;
}

function percentileOf(values, p) {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];
}

function niceCeil(x) {
  const mag = Math.pow(10, Math.floor(Math.log10(x)));
  const n = x / mag;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
}

/** A proportion meter with a confidence interval whisker. */
export function meter(value, low, high, color) {
  const el = h('div', { class: 'meter' },
    h('div', { class: 'meter-fill', style: { width: value * 100 + '%', background: color } }),
    low != null ? h('div', { class: 'meter-ci', style: { left: low * 100 + '%', width: Math.max(0.5, (high - low) * 100) + '%' } }) : null);
  if (low != null) tooltip(el, ['Pass rate', fmt.pct(value), `95% interval ${fmt.pct(low)} – ${fmt.pct(high)}`]);
  return el;
}
