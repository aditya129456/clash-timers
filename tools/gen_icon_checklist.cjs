// Writes tools/icon_checklist.txt: every icon file the widget can load from
// app/src/main/assets/icons/, with the name it stands for.
// Usage: node tools/gen_icon_checklist.cjs <path-to-unpacked>/package tools/icon_checklist.txt
//
// Job icons are looked up by the game `data` ID (e.g. 1000027.webp = Inferno Tower). For a
// crafted-defense module the widget tries the module ID, then the crafted defense's ID.
const fs = require('fs'), path = require('path');
const [pkgDir, outFile] = process.argv.slice(2);
const version = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8')).version;
const home = path.join(pkgDir, 'data', 'home');

// Directory → section title. Only things with an upgrade timer (walls are instant, equipment is
// upgraded at the Blacksmith without a timer, helpers have their own files below).
const SECTIONS = [
  ['town-hall', 'Town Hall (builder)'],
  ['defenses', 'Defenses (builder)'],
  ['crafted-defenses', 'Crafted defenses and their modules (builder)'],
  ['traps', 'Traps (builder)'],
  ['army-buildings', 'Army buildings (builder)'],
  ['resource-buildings', 'Resource buildings (builder)'],
  ['other', 'Other buildings (builder)'],
  ['heroes', 'Heroes (builder)'],
  ['guardians', 'Guardians (builder)'],
  ['troops', 'Troops (lab)'],
  ['spells', 'Spells (lab)'],
  ['siege-machines', 'Siege machines (lab)'],
  ['pets', 'Pets (pet house)'],
];
const HELPER_IDS = new Set([93000000, 93000001, 93000002, 93000003]);

function collect(dir) {
  const out = [];
  (function walk(d) {
    for (const f of fs.readdirSync(d).sort()) {
      const p = path.join(d, f);
      if (fs.statSync(p).isDirectory()) { walk(p); continue; }
      if (!p.endsWith('.json')) continue;
      const items = [].concat(JSON.parse(fs.readFileSync(p, 'utf8')));
      for (const o of items) {
        if (!o || typeof o.dataId !== 'number' || HELPER_IDS.has(o.dataId)) continue;
        out.push([o.dataId, o.name]);
        for (const m of o.modules || []) out.push([m.dataId, `${o.name} ${m.name} (module)`]);
      }
    }
  })(path.join(home, dir));
  return out;
}

const pad = s => (s + '.webp').padEnd(22);
const lines = [
  'Clash Timers widget icons',
  '=========================',
  '',
  'Icons live in app/src/main/assets/icons/ with exactly these names. tools/gen_icons.cjs makes them',
  '(64x64 WebP) from the clash-of-clans-data images; tools/icons_missing.txt lists the gaps and',
  'tools/icon_sheet.html shows every icon. A hand-made square <name>.png also works (.webp wins).',
  'Any missing file is shown as placeholder initials (e.g. "IT" for Inferno Tower).',
  `Names from clash-of-clans-data@${version}. Regenerate: node tools/gen_icon_checklist.cjs <package> tools/icon_checklist.txt`,
  '',
  'Widget artwork',
  '--------------',
  `${pad('builder_potion')}Builder Potion (Builders section button)`,
  `${pad('builder_bite')}Builder Bite (Builders section button)`,
  `${pad('research_potion')}Research Potion (Lab section button)`,
  `${pad('pet_potion')}Pet Potion (Pets section button)`,
  `${pad('apprentice')}Builder's Apprentice (helper badge on a builder row)`,
  `${pad('lab_assistant')}Lab Assistant (helper badge on the lab row)`,
  `${pad('goblin')}Goblin Builder (inside the GOBLIN tag)`,
  `${pad('clock')}Clock (before each countdown)`,
  '',
];
let total = 8;
for (const [dir, title] of SECTIONS) {
  const items = collect(dir);
  if (!items.length) continue;
  lines.push(title, '-'.repeat(title.length));
  for (const [id, name] of items) lines.push(`${pad(String(id))}${name}`);
  lines.push('');
  total += items.length;
}
fs.writeFileSync(outFile, lines.join('\n'));
console.log(`wrote ${total} icon names to ${outFile}`);
