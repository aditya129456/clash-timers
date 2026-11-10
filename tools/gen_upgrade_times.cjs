// Regenerates app/src/main/assets/upgrade_times.json from the `clash-of-clans-data` npm package.
// Usage (same unpacked tarball as gen_names.cjs, no npm install needed):
//   node tools/gen_upgrade_times.cjs <path-to-unpacked>/package app/src/main/assets/upgrade_times.json
//
// Output: { "source": "...", "times": { "<dataId>": { "<level>": seconds, ... }, ... } }
// The time stored under level N is the time to upgrade TO level N (level 1 = build time), so the
// app looks up `lvl + 1` from the export. Crafted-defense modules are keyed by the module's dataId.
const fs = require('fs'), path = require('path');
const [pkgDir, outFile] = process.argv.slice(2);
const version = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8')).version;
const home = path.join(pkgDir, 'data', 'home');

// Upgrades with no builder/lab/pet timer: walls are instant, equipment is done at the Blacksmith.
const SKIP_DIRS = new Set(['walls', 'hero-equipment']);
const TIME_KEYS = ['buildTime', 'upgradeTime', 'researchTime'];

const secs = t => ((t.days * 24 + t.hours) * 60 + t.minutes) * 60 + (t.seconds || 0);
const times = {};
const warnings = [];

function put(id, level, t) {
  if (typeof id !== 'number' || typeof level !== 'number' || !t) return;
  const byLevel = (times[id] = times[id] || {});
  const s = secs(t);
  if (level in byLevel) {
    if (byLevel[level] !== s) warnings.push(`${id} lvl ${level}: kept ${byLevel[level]} s, ignored ${s} s`);
    return;
  }
  byLevel[level] = s;
}

function levelTime(l) {
  for (const k of TIME_KEYS) if (l[k]) return l[k];
  return null;
}

function scanFile(o) {
  if (Array.isArray(o)) return o.forEach(scanFile);
  if (!o || typeof o !== 'object' || typeof o.dataId !== 'number') return;
  // Supercharge levels (marked `supercharge`) restart at 1 after the normal levels; they are a
  // separate track the export doesn't distinguish yet, so only normal levels are kept.
  if (Array.isArray(o.levels)) for (const l of o.levels) if (l.supercharge === undefined) put(o.dataId, l.level, levelTime(l));
  if (Array.isArray(o.modules)) {
    for (const m of o.modules) for (const u of m.upgrades || []) put(m.dataId, u.level, levelTime(u));
  }
}

(function walk(d) {
  for (const f of fs.readdirSync(d).sort()) {
    const p = path.join(d, f);
    if (fs.statSync(p).isDirectory()) { if (!SKIP_DIRS.has(f)) walk(p); }
    else if (p.endsWith('.json')) scanFile(JSON.parse(fs.readFileSync(p, 'utf8')));
  }
})(home);

const sorted = Object.fromEntries(Object.keys(times).map(Number).sort((a, b) => a - b).map(k => [k, times[k]]));
const out = { source: `clash-of-clans-data@${version} (data/home)`, times: sorted };
fs.writeFileSync(outFile, JSON.stringify(out) + '\n');
warnings.forEach(w => console.log('note:', w));
console.log(`wrote upgrade times for ${Object.keys(sorted).length} IDs to ${outFile}`);
