// Builds the widget icons from the clash-of-clans-data package.
//
// For every entry in tools/icon_checklist.txt it picks one image from the package, trims the
// transparent edges, pads it to a square, resizes it to 64x64 and writes
// app/src/main/assets/icons/<name>.webp. Then it writes tools/icons_missing.txt and
// tools/icon_sheet.html (every icon next to its name, for checking by eye).
//
// Usage (needs the `sharp` npm package somewhere on NODE_PATH):
//   node tools/gen_icons.cjs <path-to-unpacked>/package
//
// Which image is used:
//   - Heroes, troops, spells, siege machines, pets: the item's `icon.png` (the portrait the game
//     shows in the lab / hero screens; these items have one icon, not one per level).
//   - Buildings, traps, guardians, Town Hall: the `normal` image of the highest level that has one.
//   - Crafted defenses: the image for the highest effective level. Modules have no art of their
//     own; the widget falls back to the crafted defense's icon, so no file is written.
//   - Widget artwork: potions, Builder Bite and the helpers from images/magic-items and
//     images/home/other/helpers.
const fs = require('fs'), path = require('path');
const sharp = require('sharp');

const ROOT = path.resolve(__dirname, '..');
const pkgDir = path.resolve(process.argv[2] || '');
if (!fs.existsSync(path.join(pkgDir, 'data', 'home'))) {
  console.error('usage: node tools/gen_icons.cjs <path-to-clash-of-clans-data>/package');
  process.exit(1);
}
const version = JSON.parse(fs.readFileSync(path.join(pkgDir, 'package.json'), 'utf8')).version;
const OUT_DIR = path.join(ROOT, 'app', 'src', 'main', 'assets', 'icons');
const CHECKLIST = path.join(ROOT, 'tools', 'icon_checklist.txt');
const MISSING = path.join(ROOT, 'tools', 'icons_missing.txt');
const SHEET = path.join(ROOT, 'tools', 'icon_sheet.html');
const SIZE = 64;

// ---- 1. The checklist: [{file, name, section}] ----
function readChecklist() {
  const lines = fs.readFileSync(CHECKLIST, 'utf8').split(/\r?\n/);
  const out = [];
  let section = null;
  for (let i = 0; i < lines.length; i++) {
    if (/^-+$/.test(lines[i + 1] || '') && lines[i].trim()) { section = lines[i].trim(); i++; continue; }
    const m = lines[i].match(/^(\S+)\.(?:png|webp)\s+(.+)$/);
    if (m && section) out.push({ base: m[1], name: m[2].trim(), section });
  }
  return out;
}

// ---- 2. dataId → image path, from the package's data JSON ----
const lastLevelImage = levels => {
  for (let i = (levels || []).length - 1; i >= 0; i--) {
    const img = levels[i].images;
    if (img && img.normal) return { rel: img.normal, level: levels[i].level };
  }
  return null;
};

function indexPackage() {
  const byId = new Map();
  const modules = new Map(); // module dataId → parent dataId
  (function walk(d) {
    for (const f of fs.readdirSync(d).sort()) {
      const p = path.join(d, f);
      if (fs.statSync(p).isDirectory()) { walk(p); continue; }
      if (!p.endsWith('.json')) continue;
      for (const o of [].concat(JSON.parse(fs.readFileSync(p, 'utf8')))) {
        if (!o || typeof o.dataId !== 'number') continue;
        let pick = null;
        if (o.images && !Array.isArray(o.images) && o.images.icon) {
          pick = { rel: o.images.icon, how: 'icon' };
        } else if (Array.isArray(o.images) && o.images.length) {
          const best = o.images.filter(x => x.normal)
            .sort((a, b) => (b.toEffectiveLevel ?? b.fromEffectiveLevel ?? 0) - (a.toEffectiveLevel ?? a.fromEffectiveLevel ?? 0))[0];
          if (best) pick = { rel: best.normal, how: `effective level ${best.fromEffectiveLevel}-${best.toEffectiveLevel}` };
        } else {
          const l = lastLevelImage(o.levels);
          if (l) pick = { rel: l.rel, how: `level ${l.level}` };
          else if (o.images && o.images.normal) pick = { rel: o.images.normal, how: 'normal' };
        }
        byId.set(o.dataId, pick);
        for (const m of o.modules || []) modules.set(m.dataId, o.dataId);
      }
    }
  })(path.join(pkgDir, 'data', 'home'));
  return { byId, modules };
}

// Widget artwork that isn't keyed by a game ID.
const ARTWORK = {
  builder_potion: 'images/magic-items/potions/builder-potion.png',
  research_potion: 'images/magic-items/potions/research-potion.png',
  pet_potion: 'images/magic-items/potions/pet-potion.png',
  builder_bite: 'images/magic-items/snacks/builder-bite.png',
  apprentice: 'images/home/other/helpers/builders-apprentice/normal.png',
  lab_assistant: 'images/home/other/helpers/lab-assistant/normal.png',
};
const ARTWORK_MISSING_WHY = {
  goblin: 'No Goblin Builder art in the package (images/home/troops/goblin is the troop, not the builder). The GOBLIN tag shows text only.',
  clock: 'No clock icon in the package; the widget keeps its built-in gold clock drawable.',
};

// ---- 3. Image processing ----
async function makeIcon(srcAbs, outAbs) {
  // Trim fully transparent edges (alpha threshold 8 avoids keeping faint halos).
  const { data, info } = await sharp(srcAbs).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const { width: w, height: h } = info;
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (data[(y * w + x) * 4 + 3] > 8) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  }
  if (x1 < 0) throw new Error('image is fully transparent');
  const cw = x1 - x0 + 1, ch = y1 - y0 + 1, side = Math.max(cw, ch);
  const cropped = await sharp(data, { raw: info }).extract({ left: x0, top: y0, width: cw, height: ch })
    .extend({
      top: Math.floor((side - ch) / 2), bottom: Math.ceil((side - ch) / 2),
      left: Math.floor((side - cw) / 2), right: Math.ceil((side - cw) / 2),
      background: { r: 0, g: 0, b: 0, alpha: 0 },
    })
    .png().toBuffer();
  await sharp(cropped)
    .resize(SIZE, SIZE, { kernel: 'lanczos3' })
    .webp({ quality: 90, alphaQuality: 100, effort: 6, smartSubsample: true })
    .toFile(outAbs);
  return { srcW: w, srcH: h, cropW: cw, cropH: ch };
}

const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

(async () => {
  const entries = readChecklist();
  const { byId, modules } = indexPackage();
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const made = [], missing = [], fallback = [];
  for (const e of entries) {
    let rel = null, how = '', why = '';
    if (/^\d+$/.test(e.base)) {
      const id = Number(e.base);
      if (modules.has(id)) {
        fallback.push({ ...e, parent: modules.get(id) });
        continue;
      }
      const pick = byId.get(id);
      if (pick) { rel = pick.rel; how = pick.how; } else why = byId.has(id) ? 'The package has no image for it.' : 'Not in the package data.';
    } else if (ARTWORK[e.base]) {
      rel = ARTWORK[e.base]; how = 'artwork';
    } else {
      why = ARTWORK_MISSING_WHY[e.base] || 'No matching image in the package.';
    }
    const src = rel && path.join(pkgDir, rel);
    if (src && !fs.existsSync(src)) { why = `Listed image ${rel} is not in the package.`; rel = null; }
    if (!rel) { missing.push({ ...e, why }); continue; }
    const file = `${e.base}.webp`;
    try {
      const dims = await makeIcon(src, path.join(OUT_DIR, file));
      made.push({ ...e, file, rel, how, ...dims, bytes: fs.statSync(path.join(OUT_DIR, file)).size });
    } catch (err) {
      missing.push({ ...e, why: `Could not process ${rel}: ${err.message}` });
    }
  }

  // Old PNGs with the same names would be shadowed; remove stale .webp files not in the checklist.
  const wanted = new Set(made.map(m => m.file));
  for (const f of fs.readdirSync(OUT_DIR)) {
    if (f.endsWith('.webp') && !wanted.has(f)) fs.unlinkSync(path.join(OUT_DIR, f));
  }

  // ---- icons_missing.txt ----
  const pad = s => s.padEnd(22);
  const txt = [
    'Clash Timers widget icons still missing',
    '=======================================',
    '',
    `Generated by tools/gen_icons.cjs from clash-of-clans-data@${version}.`,
    `${made.length} of ${entries.length} checklist entries have an icon in app/src/main/assets/icons/.`,
    'To fill a gap, put a square PNG or WebP with the file name below in that folder and rebuild',
    '(the widget loads <name>.webp first, then <name>.png).',
    '',
    `Missing (${missing.length})`,
    '-'.repeat(`Missing (${missing.length})`.length),
    ...(missing.length ? missing.map(m => `${pad(m.base + '.webp')}${m.name} [${m.section}]\n${' '.repeat(22)}${m.why}`) : ['(none)']),
    '',
    `Covered by a fallback, no file needed (${fallback.length})`,
    '-'.repeat(`Covered by a fallback, no file needed (${fallback.length})`.length),
    'Crafted-defense modules have no art of their own. The widget tries the module ID, then the',
    'crafted defense, so these rows show the defense icon. Add a file only to override that.',
    ...fallback.map(f => `${pad(f.base + '.webp')}${f.name} -> uses ${f.parent}.webp`),
    '',
  ].join('\n');
  fs.writeFileSync(MISSING, txt);

  // ---- icon_sheet.html ----
  const sections = [...new Set(entries.map(e => e.section))];
  const madeByBase = new Map(made.map(m => [m.base, m]));
  const missByBase = new Map(missing.map(m => [m.base, m]));
  const fbByBase = new Map(fallback.map(m => [m.base, m]));
  const cell = e => {
    const m = madeByBase.get(e.base);
    if (m) {
      return `<figure class="ok" title="${esc(m.rel)} (${esc(m.how)}), trimmed ${m.cropW}x${m.cropH} of ${m.srcW}x${m.srcH}, ${m.bytes} bytes">
  <div class="tiles"><img class="big" data-icon="${esc(m.file)}" alt=""><span class="row"><img class="small" data-icon="${esc(m.file)}" alt=""></span></div>
  <figcaption><b>${esc(e.name)}</b><code>${esc(m.file)}</code><small>${esc(m.how)}</small></figcaption></figure>`;
    }
    const f = fbByBase.get(e.base);
    if (f) {
      const p = madeByBase.get(String(f.parent));
      const img = p ? `<img class="big dim" data-icon="${esc(p.file)}" alt="">` : '<div class="big hole">?</div>';
      return `<figure class="fb"><div class="tiles">${img}</div>
  <figcaption><b>${esc(e.name)}</b><code>${esc(e.base)}.webp</code><small>uses ${esc(f.parent)}.webp</small></figcaption></figure>`;
    }
    const x = missByBase.get(e.base);
    return `<figure class="miss"><div class="tiles"><div class="big hole">missing</div></div>
  <figcaption><b>${esc(e.name)}</b><code>${esc(e.base)}.webp</code><small>${esc(x ? x.why : '')}</small></figcaption></figure>`;
  };
  const html = `<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Clash Timers icon sheet</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  body { font: 14px/1.4 system-ui, sans-serif; margin: 24px; background: #f4efe8; color: #2a1d13; }
  h1 { margin: 0 0 4px; } p.meta { margin: 0 0 20px; color: #6b5640; }
  h2 { margin: 28px 0 10px; font-size: 16px; border-bottom: 2px solid #c9b79f; padding-bottom: 4px; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
  figure { margin: 0; padding: 10px; background: #fff; border: 1px solid #ddd0bd; border-radius: 10px; display: flex; flex-direction: column; align-items: center; text-align: center; }
  figure.miss { background: #fff1ee; border-color: #e5a99c; }
  figure.fb { background: #f7f4ee; border-style: dashed; }
  .tiles { display: flex; align-items: center; gap: 10px; height: 72px; }
  .big { width: 64px; height: 64px; image-rendering: auto; background: repeating-conic-gradient(#eee 0 25%, #fff 0 50%) 0 0 / 12px 12px; border-radius: 4px; }
  .dim { opacity: .55; }
  .row { display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 36px; border-radius: 6px; background: linear-gradient(#7a5639, #5a3f2b); }
  .small { width: 24px; height: 24px; }
  .hole { display: flex; align-items: center; justify-content: center; color: #b4543e; font-size: 12px; border: 2px dashed #e5a99c; background: none; }
  figcaption { margin-top: 6px; display: flex; flex-direction: column; gap: 2px; }
  code { font-size: 11px; color: #6b5640; } small { font-size: 11px; color: #8a7660; }
  .legend span { display: inline-block; margin-right: 14px; }
</style></head><body>
<h1>Clash Timers icon sheet</h1>
<p class="meta">${made.length} icons made, ${missing.length} missing, ${fallback.length} covered by the crafted-defense fallback.
Source: clash-of-clans-data@${esc(version)}. Each icon is shown at 64 px on a checkerboard and at the widget's 24 dp on the wood colour.
Hover a tile to see the source image. Regenerate with <code>node tools/gen_icons.cjs &lt;package&gt;</code>.</p>
${sections.map(s => `<h2>${esc(s)}</h2>\n<div class="grid">\n${entries.filter(e => e.section === s).map(cell).join('\n')}\n</div>`).join('\n')}
<script>
// Each icon is embedded once and shared by every <img data-icon> that shows it.
const ICONS = ${JSON.stringify(Object.fromEntries(made.map(m => [m.file, fs.readFileSync(path.join(OUT_DIR, m.file)).toString('base64')])))};
for (const img of document.querySelectorAll('img[data-icon]')) img.src = 'data:image/webp;base64,' + ICONS[img.dataset.icon];
</script>
</body></html>
`;
  fs.writeFileSync(SHEET, html);

  const kb = made.reduce((a, m) => a + m.bytes, 0) / 1024;
  console.log(`made ${made.length} icons (${kb.toFixed(1)} KiB), missing ${missing.length}, fallback ${fallback.length}`);
  for (const m of missing) console.log(`  missing ${m.base}: ${m.name} — ${m.why}`);
})().catch(e => { console.error(e); process.exit(1); });
