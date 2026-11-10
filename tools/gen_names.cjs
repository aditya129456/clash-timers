// Regenerates app/src/main/assets/names.json from the `clash-of-clans-data` npm package.
// Usage: download + unpack the tarball (no npm install needed), then
//   node tools/gen_names.cjs <path-to-unpacked>/package app/src/main/assets/names.json
const fs = require('fs'), path = require('path');
const [pkgDir, outFile] = process.argv.slice(2);
const version = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8')).version;
const home = path.join(pkgDir, 'data', 'home');
const names = {};
function scan(o) {
  if (Array.isArray(o)) return o.forEach(scan);
  if (!o || typeof o !== 'object') return;
  if (typeof o.dataId === 'number' && typeof o.name === 'string') {
    if (names[o.dataId] && names[o.dataId] !== o.name) throw new Error(`conflict ${o.dataId}`);
    names[o.dataId] = o.name;
  }
  for (const k in o) if (k !== 'upgrades' && k !== 'levels') scan(o[k]);
}
(function walk(d) {
  for (const f of fs.readdirSync(d).sort()) {
    const p = path.join(d, f);
    if (fs.statSync(p).isDirectory()) walk(p);
    else if (p.endsWith('.json')) scan(JSON.parse(fs.readFileSync(p, 'utf8')));
  }
})(home);
const sorted = Object.fromEntries(Object.keys(names).map(Number).sort((a, b) => a - b).map(k => [k, names[k]]));
const out = { source: `clash-of-clans-data@${version} (data/home)`, names: sorted };
fs.writeFileSync(outFile, JSON.stringify(out, null, 1) + '\n');
console.log(`wrote ${Object.keys(sorted).length} names to ${outFile}`);
