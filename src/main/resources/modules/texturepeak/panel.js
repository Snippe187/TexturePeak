// Seite "Texturen": Inventar (wie das Kreativ-Inventar, mit Rechtsklick-Menue), Eintraege, Faehigkeiten, Archiv und Pixel-Editor.
export default async function mount(root, ctx) {
  const { h } = ctx;
  const L = (de, en) => ctx.t({ de, en });
  let meta, entries = [], archive = [], tab = 'inventory', open = null, editor = null, dlg = null;
  // Inventar: Standard-Bilder (van), Server-Pack (pk), eigene Aenderungen (ovr)
  let van = null, pk = { ok: false, files: new Set(), sha: '', name: '' }, ovr = new Set(), rev = Date.now(), vanPoll = null;
  let layer = 'both', cat = 'blocks', guiCat = 'all', q = '', onlyGame = true, onlyChanged = false, limit = 360, pick = null;

  const rel = async () => { entries = await ctx.api('entries'); archive = await ctx.api('archive'); };
  async function call(path, method, body, ok) {
    try { await ctx.api(path, { method, body }); if (ok) ctx.toast(ok, 'good'); await rel(); return true; }
    catch (e) { if (e.message) ctx.toast(e.message, 'bad'); return false; }
  }
  const texUrl = (id, arch) => '/api/texturepeak/texture/' + id + '?archived=' + (arch ? 1 : 0) + '&t=' + Date.now();
  const pretty = n => n.replace(/[_/]/g, ' ');
  const kb = n => n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : n > 1024 ? (n / 1024).toFixed(1) + ' KB' : n + ' B';

  // ---------- Quellen: Standard, Server-Pack, eigene Aenderungen ----------
  const key = (k, n) => k + '/' + n;
  const packPath = (k, n) => 'assets/minecraft/textures/' + k + '/' + n + '.png';
  const vurl = (k, n) => '/api/texturepeak/vanilla/' + k + '/' + n;
  const ourl = (k, n) => '/api/texturepeak/override/' + k + '/' + n + '?v=' + rev;
  const purl = (k, n) => '/api/pack/file?variant=standard&path=' + encodeURIComponent(packPath(k, n)) + '&v=' + pk.sha + '-' + rev;
  const hasPack = (k, n) => pk.ok && pk.files.has(packPath(k, n));
  const hasOvr = (k, n) => ovr.has(key(k, n));
  const hasVan = (k, n) => !!van && (van[k] || []).includes(n);
  /** Das Bild so, wie es im Spiel aussieht: eigene Aenderung, sonst Server-Pack, sonst Standard. */
  const gameUrl = (k, n) => hasOvr(k, n) ? ourl(k, n) : (layer !== 'standard' && hasPack(k, n)) ? purl(k, n) : vurl(k, n);
  const fetchJson = async u => { const r = await fetch(u, { credentials: 'same-origin' }); if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); };

  async function loadVanilla() { van = await ctx.api('vanilla'); }
  async function loadOverrides() { try { ovr = new Set(await ctx.api('overrides')); } catch { ovr = new Set(); } }
  async function loadPack() {
    pk = { ok: false, files: new Set(), sha: '', name: '' };
    try {
      const [files, status] = await Promise.all([fetchJson('/api/pack/files?variant=standard'), fetchJson('/api/pack/status')]);
      pk.files = new Set(files.map(f => f.path));
      pk.sha = (status.variants.find(v => v.variant === 'standard') || {}).live || '';
      pk.ok = true;
    } catch { /* kein Zugriff auf das Pack-Modul oder noch nicht gebaut: nur Standard anzeigen */ }
    try { const lib = await fetchJson('/api/pack/library'); const a = lib.packs.find(p => p.id === lib.active); pk.name = a ? a.name : ''; } catch { /* egal */ }
  }
  async function reloadSources() { rev = Date.now(); await Promise.all([loadOverrides(), loadPack()]); }

  async function startDownload() {
    try { van = await ctx.api('vanilla/download', { method: 'POST', body: '' }); } catch (e) { ctx.toast(e.message, 'bad'); return; }
    clearInterval(vanPoll);
    vanPoll = setInterval(async () => {
      try { await loadVanilla(); } catch { return; }
      if (!van.busy) { clearInterval(vanPoll); if (van.ready) ctx.toast(L('Standard-Texturen geladen', 'Default textures loaded'), 'good'); else if (van.error) ctx.toast(van.error, 'bad'); }
      if (tab === 'inventory' && !editor) render();
    }, 2000);
    render();
  }

  // ---------- Hilfen fuer Bilder ----------
  async function loadImg(src) { const img = new Image(); img.crossOrigin = 'use-credentials'; img.src = src; await img.decode(); return img; }
  /** Erstes Bild einer (evtl. animierten, hochkant gestapelten) Textur als quadratisches Canvas. */
  async function firstFrame(src) {
    const img = await loadImg(src), w = img.naturalWidth, c = document.createElement('canvas');
    c.width = c.height = w;
    const g = c.getContext('2d'); g.imageSmoothingEnabled = false; g.drawImage(img, 0, 0, w, w, 0, 0, w, w);
    return c;
  }
  const canvasB64 = c => c.toDataURL('image/png').split(',')[1];

  // ---------- Pixel-Editor (jede Form, auch GUI-Bilder) ----------
  const PALETTE = ['#000000', '#1d2b53', '#7e2553', '#008751', '#ab5236', '#5f574f', '#c2c3c7', '#fff1e8', '#ff004d', '#ffa300', '#ffec27', '#00e436', '#29adff', '#83769c', '#ff77a8', '#ffccaa', '#00000000'];
  function newEditor(w, hh, img, target) {
    const e = { w, h: hh, cells: new Uint8ClampedArray(w * hh * 4), color: '#ff004d', tool: 'pen', undo: [], redo: [], zoom: Math.max(2, Math.min(32, Math.floor(560 / Math.max(w, hh)))), target: target || { type: 'new' }, material: null, label: '' };
    if (img) { const c = document.createElement('canvas'); c.width = w; c.height = hh; const g = c.getContext('2d'); g.imageSmoothingEnabled = false; g.drawImage(img, 0, 0, w, hh); e.cells.set(g.getImageData(0, 0, w, hh).data); }
    return e;
  }
  const hex2rgba = hx => hx.length > 7 ? [0, 0, 0, 0] : [parseInt(hx.slice(1, 3), 16), parseInt(hx.slice(3, 5), 16), parseInt(hx.slice(5, 7), 16), 255];
  const rgba2hex = (c, i) => c[i + 3] === 0 ? '#00000000' : '#' + [c[i], c[i + 1], c[i + 2]].map(v => v.toString(16).padStart(2, '0')).join('');

  function editorView() {
    const e = editor, W = e.w, H = e.h, canvas = h('canvas', { class: 'pix', width: W * e.zoom, height: H * e.zoom, style: 'max-width:100%' });
    const g = canvas.getContext('2d');
    const draw = () => {
      canvas.width = W * e.zoom; canvas.height = H * e.zoom;
      g.clearRect(0, 0, canvas.width, canvas.height);
      const px = document.createElement('canvas'); px.width = W; px.height = H; px.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(e.cells), W, H), 0, 0);
      g.imageSmoothingEnabled = false; g.drawImage(px, 0, 0, canvas.width, canvas.height);
      if (e.zoom >= 10) { g.strokeStyle = 'rgba(128,128,128,.25)'; g.lineWidth = 1; for (let i = 1; i < W; i++) { g.beginPath(); g.moveTo(i * e.zoom + .5, 0); g.lineTo(i * e.zoom + .5, canvas.height); g.stroke(); } for (let i = 1; i < H; i++) { g.beginPath(); g.moveTo(0, i * e.zoom + .5); g.lineTo(canvas.width, i * e.zoom + .5); g.stroke(); } }
    };
    const snap = () => { e.undo.push(new Uint8ClampedArray(e.cells)); if (e.undo.length > 60) e.undo.shift(); e.redo = []; };
    const put = (x, y, col) => { if (x < 0 || y < 0 || x >= W || y >= H) return; e.cells.set(col, (y * W + x) * 4); };
    const at = ev => { const r = canvas.getBoundingClientRect(); return [Math.floor((ev.clientX - r.left) / r.width * W), Math.floor((ev.clientY - r.top) / r.height * H)]; };
    const fill = (x, y, col) => {
      const i0 = (y * W + x) * 4, target = [...e.cells.slice(i0, i0 + 4)];
      if (target.every((v, k) => v === col[k])) return;
      const stack = [[x, y]];
      while (stack.length) { const [cx, cy] = stack.pop(); if (cx < 0 || cy < 0 || cx >= W || cy >= H) continue; const i = (cy * W + cx) * 4; if (!target.every((v, k) => e.cells[i + k] === v)) continue; e.cells.set(col, i); stack.push([cx + 1, cy], [cx - 1, cy], [cx, cy + 1], [cx, cy - 1]); }
    };
    let down = false;
    const apply = ev => {
      const [x, y] = at(ev);
      if (x < 0 || y < 0 || x >= W || y >= H) return;
      if (e.tool === 'pick') { e.color = rgba2hex(e.cells, (y * W + x) * 4); e.tool = 'pen'; render(); return; }
      if (e.tool === 'fill') fill(x, y, hex2rgba(e.color));
      else put(x, y, e.tool === 'erase' ? [0, 0, 0, 0] : hex2rgba(e.color));
      draw();
    };
    canvas.addEventListener('pointerdown', ev => { canvas.setPointerCapture(ev.pointerId); down = true; snap(); apply(ev); });
    canvas.addEventListener('pointermove', ev => { if (down && (e.tool === 'pen' || e.tool === 'erase')) apply(ev); });
    canvas.addEventListener('pointerup', () => { down = false; });
    draw();

    const toBlob = () => new Promise(res => { const c = document.createElement('canvas'); c.width = W; c.height = H; c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(e.cells), W, H), 0, 0); c.toBlob(res, 'image/png'); });
    const tools = [['pen', L('Stift', 'Pen')], ['erase', L('Radierer', 'Eraser')], ['fill', L('Fuellen', 'Fill')], ['pick', L('Pipette', 'Picker')]];
    const colorInput = h('input', { type: 'color', value: e.color.length > 7 ? '#000000' : e.color, style: 'width:56px;padding:2px', oninput: ev => { e.color = ev.target.value; } });
    const swap = k => { const from = k === 'undo' ? e.undo : e.redo, to = k === 'undo' ? e.redo : e.undo; if (!from.length) return; to.push(new Uint8ClampedArray(e.cells)); e.cells = from.pop(); draw(); };
    const flip = horizontal => { snap(); const n = new Uint8ClampedArray(e.cells.length); for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) n.set(e.cells.slice((y * W + x) * 4, (y * W + x) * 4 + 4), ((horizontal ? y : H - 1 - y) * W + (horizontal ? W - 1 - x : x)) * 4); e.cells = n; draw(); };
    const tg = e.target, strip = H > W && H % W === 0 ? H / W : 0;

    return h('div', { class: 'card tp-pop' },
      h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, L('Pixel-Editor', 'Pixel editor') + ' ' + W + '×' + H + (e.label ? ' – ' + e.label : '')), h('span', { class: 'grow' }), h('button', { class: 'px-btn small ghost', onclick: () => { editor = null; render(); } }, L('Schliessen', 'Close'))),
      strip > 1 ? h('p', { class: 'sub' }, L('Das ist eine Animation mit ' + strip + ' Bildern untereinander. Du bearbeitest das ganze Bild.', 'This is an animation with ' + strip + ' frames stacked. You edit the whole image.')) : null,
      tg.type === 'override' ? h('p', { class: 'sub' }, L('Gespeichert wird als Aenderung im Server-Pack: Das Bild ersetzt diese Textur fuer alle Spieler. Die Groesse bleibt am besten gleich.', 'It is saved as a change in the server pack: the image replaces this texture for all players. Better keep the size.')) : null,
      h('div', { class: 'px-edit', style: 'margin-top:12px' },
        h('div', { style: 'overflow:auto;max-height:70vh' }, canvas),
        h('div', {},
          h('div', { class: 'row' }, tools.map(([k, l]) => h('button', { class: 'px-btn small ' + (e.tool === k ? 'active' : ''), onclick: () => { e.tool = k; render(); } }, l))),
          h('div', { class: 'row', style: 'margin-top:8px' }, colorInput,
            h('button', { class: 'px-btn small', onclick: () => swap('undo') }, '↶ ' + L('Zurueck', 'Undo')), h('button', { class: 'px-btn small', onclick: () => swap('redo') }, '↷'),
            h('button', { class: 'px-btn small', onclick: () => { snap(); e.cells.fill(0); draw(); } }, L('Leeren', 'Clear'))),
          h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { class: 'px-btn small', onclick: () => flip(true) }, '⇋ ' + L('Spiegeln', 'Flip')), h('button', { class: 'px-btn small', onclick: () => flip(false) }, '⇅'),
            h('button', { class: 'px-btn small', onclick: () => { e.zoom = Math.max(1, e.zoom - 1); draw(); } }, '−'), h('span', { class: 'sub' }, 'Zoom ' + e.zoom + '×'), h('button', { class: 'px-btn small', onclick: () => { e.zoom = Math.min(40, e.zoom + 1); draw(); } }, '+')),
          h('div', { class: 'swatches' }, PALETTE.map(c => h('div', { class: 'sw' + (c === e.color ? ' on' : ''), title: c, style: c.length > 7 ? 'background:repeating-conic-gradient(#888 0 25%,#ccc 0 50%) 0 0/8px 8px' : 'background:' + c, onclick: () => { e.color = c; if (c.length <= 7) colorInput.value = c; render(); } }))),
          h('div', { class: 'row', style: 'margin-top:14px' },
            tg.type === 'entry' ? h('button', { class: 'px-btn primary', onclick: async () => { const b = await toBlob(); if (await call('entries/' + tg.id + '/texture', 'POST', await b.arrayBuffer(), L('Textur gespeichert', 'Texture saved'))) { editor = null; render(); } } }, L('Speichern', 'Save')) : null,
            tg.type === 'override' ? h('button', { class: 'px-btn primary', onclick: async () => {
              try { const b = await toBlob(); await ctx.api('override/' + tg.kind + '/' + tg.name, { method: 'PUT', body: await b.arrayBuffer() }); ctx.toast(L('Im Server-Pack gespeichert. Spieler bekommen es sofort.', 'Saved in the server pack. Players get it right away.'), 'good'); await reloadSources(); editor = null; render(); }
              catch (err) { ctx.toast(err.message, 'bad'); }
            } }, L('Im Server-Pack speichern', 'Save in the server pack')) : null,
            h('button', { class: 'px-btn', onclick: async () => { const b = await toBlob(); h('a', { href: URL.createObjectURL(b), download: (tg.name || tg.id || 'textur').replace(/\//g, '_') + '.png' }).click(); } }, L('Als PNG laden', 'Download PNG')),
            h('button', { class: 'px-btn', onclick: () => importPicker.click() }, L('PNG einsetzen', 'Import PNG'))),
          tg.type === 'new' ? newEntryForm(toBlob) : null)));
  }

  function newEntryForm(toBlob) {
    const id = h('input', { placeholder: 'kennung_klein' }), name = h('input', { placeholder: L('Name im Spiel (Amboss)', 'Name in game (anvil)') });
    const mat = h('input', { placeholder: 'DIAMOND_HELMET', list: 'mats', value: editor.material || '' });
    return h('div', {}, h('datalist', { id: 'mats' }, meta.materials.map(m => h('option', { value: m }))),
      h('label', {}, L('Kennung', 'Id')), id, h('label', {}, L('Gegenstand', 'Item type')), mat, h('label', {}, L('Name', 'Name')), name,
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'px-btn primary', onclick: async () => {
        const blob = await toBlob(); const bytes = new Uint8Array(await blob.arrayBuffer()); let bin = ''; for (let i = 0; i < bytes.length; i += 8192) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192)); const b64 = btoa(bin);
        if (await call('entries', 'POST', { id: id.value, material: mat.value.trim().toUpperCase(), name: name.value, png: b64 }, L('Eintrag angelegt', 'Entry created'))) { editor = null; tab = 'entries'; render(); }
      } }, L('Als neuen Eintrag anlegen', 'Create as new entry'))));
  }

  async function startEntryEditor(id, arch) {
    if (!id) { editor = newEditor(16, 16, null, { type: 'new' }); render(); return; }
    const img = await loadImg(texUrl(id, arch));
    editor = newEditor(img.naturalWidth, img.naturalHeight, img, { type: arch ? 'new' : 'entry', id }); editor.label = id; render();
  }
  /** Eine Standard-Textur im Editor oeffnen (aus dem Server-Pack oder Standard, je nachdem, was im Spiel zu sehen ist). */
  async function startOverrideEditor(it) {
    try {
      const img = await loadImg(gameUrl(it.kind, it.name));
      editor = newEditor(img.naturalWidth, img.naturalHeight, img, { type: 'override', kind: it.kind, name: it.name }); editor.label = pretty(it.name);
      pick = null; render(); window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch { ctx.toast(L('Das Bild konnte nicht geladen werden.', 'The image could not be loaded.'), 'bad'); }
  }
  async function importFile(file) {
    const img = new Image(); img.src = URL.createObjectURL(file);
    try { await img.decode(); } catch { ctx.toast(L('Kein Bild', 'Not an image'), 'bad'); return; }
    if (editor && editor.target.type === 'override') { const t = editor.target, lab = editor.label; editor = newEditor(img.naturalWidth, img.naturalHeight, img, t); editor.label = lab; render(); return; }
    if (![16, 32, 64, 128, 256, 512].includes(img.naturalWidth) || img.naturalWidth !== img.naturalHeight) { ctx.toast(L('Das Bild muss quadratisch sein: 16, 32, 64, 128, 256 oder 512 Pixel.', 'The image must be square: 16, 32, 64, 128, 256 or 512 px.'), 'bad'); return; }
    const tg = editor ? editor.target : { type: 'new' };
    editor = newEditor(img.naturalWidth, img.naturalHeight, img, tg); render();
  }

  // ---------- Inventar ----------
  const TOOLS = /(_sword|_pickaxe|_axe|_shovel|_hoe)$|^(bow|crossbow|trident|mace|shield|fishing_rod|carrot_on_a_stick|warped_fungus_on_a_stick|shears|flint_and_steel|brush|spyglass|compass|clock|lead|name_tag|bucket|water_bucket|lava_bucket)$/;
  const ARMOR = /(_helmet|_chestplate|_leggings|_boots|_horse_armor)$|^(elytra|saddle|turtle_helmet|wolf_armor)/;
  const FOOD = /^(apple|golden_apple|enchanted_golden_apple|bread|cookie|cake|pumpkin_pie|melon_slice|dried_kelp|sweet_berries|glow_berries|honey_bottle|milk_bucket|mushroom_stew|rabbit_stew|beetroot_soup|suspicious_stew|carrot|golden_carrot|potato|baked_potato|poisonous_potato|beetroot|chorus_fruit|rotten_flesh|spider_eye|tropical_fish|pufferfish|cod|salmon|beef|porkchop|chicken|mutton|rabbit|potion|splash_potion|lingering_potion)$|^cooked_/;
  const catOf = it => it.kind === 'block' ? 'blocks' : ARMOR.test(it.name) ? 'armor' : TOOLS.test(it.name) ? 'tools' : FOOD.test(it.name) ? 'food' : 'misc';
  const CATS = [['blocks', L('Bausteine', 'Blocks'), '▦'], ['tools', L('Werkzeuge & Waffen', 'Tools & weapons'), '⚔'], ['armor', L('Rüstung', 'Armor'), '🛡'], ['food', L('Essen & Tränke', 'Food & potions'), '🍎'], ['misc', L('Sonstiges', 'Misc'), '✦'], ['all', L('Alle', 'All'), '☰'], ['custom', L('Eigene Items', 'Custom items'), '★'], ['gui', 'GUI', '▣']];

  function universe() {
    const mats = new Set(meta.materials), map = new Map();
    for (const kind of ['block', 'item']) for (const n of (van && van[kind]) || []) map.set(n, { kind, name: n }); // gleicher Name als Item und Block: das Item-Bild gewinnt
    if (pk.ok) for (const p of pk.files) { const m = /^assets\/minecraft\/textures\/(item|block)\/([a-z0-9_]+)\.png$/.exec(p); if (m && !(map.has(m[2]) && m[1] === 'block')) map.set(m[2], { kind: m[1], name: m[2] }); }
    let list = [...map.values()];
    if (onlyGame) list = list.filter(it => mats.has(it.name.toUpperCase()));
    return list.sort((a, b) => a.name.localeCompare(b.name));
  }
  function guiList() {
    const all = new Set((van && van.gui) || []);
    if (pk.ok) for (const p of pk.files) { const m = /^assets\/minecraft\/textures\/gui\/([a-z0-9_./-]+)\.png$/.exec(p); if (m) all.add(m[1]); }
    const f = {
      container: n => n.startsWith('container/'), hud: n => n.startsWith('sprites/hud/') || n.startsWith('sprites/hud'),
      menus: n => !n.startsWith('container/') && !n.startsWith('sprites/hud') && (n.startsWith('sprites/widget') || n.startsWith('sprites/') || n.startsWith('title/') || n.includes('background') || !n.includes('/')), all: () => true
    }[guiCat];
    return [...all].filter(f).sort().map(n => ({ kind: 'gui', name: n }));
  }

  // Tooltip und Rechtsklick-Menue haengen am body und folgen dem Mauszeiger
  const tip = document.getElementById('tp-tip') || document.body.appendChild(h('div', { id: 'tp-tip', class: 'tp-tip' }));
  const menu = document.getElementById('tp-menu') || document.body.appendChild(h('div', { id: 'tp-menu', class: 'tp-menu' }));
  const hideTip = () => { tip.style.display = 'none'; };
  const hideMenu = () => { menu.style.display = 'none'; };
  const place = (el, x, y) => { el.style.display = 'block'; const w = el.offsetWidth, hh = el.offsetHeight; el.style.left = Math.max(4, Math.min(x + 14, innerWidth - w - 8)) + 'px'; el.style.top = Math.max(4, Math.min(y + 14, innerHeight - hh - 8)) + 'px'; };
  if (!menu._bound) { menu._bound = true; document.addEventListener('click', hideMenu); document.addEventListener('scroll', () => { hideMenu(); hideTip(); }, true); document.addEventListener('keydown', ev => { if (ev.key === 'Escape') hideMenu(); }); }
  const infoOf = it => {
    const s = [];
    if (hasOvr(it.kind, it.name)) s.push(L('Eigene Änderung im Server-Pack', 'Own change in the server pack'));
    else if (hasPack(it.kind, it.name)) s.push(L('Wird vom Server-Pack geändert', 'Changed by the server pack'));
    else s.push(L('Standard-Textur', 'Default texture'));
    return s;
  };
  function showTip(ev, it) {
    tip.replaceChildren(h('div', { class: 'tp-tip-t' }, pretty(it.name).replace(/\b\w/g, c => c.toUpperCase())), h('div', { class: 'tp-tip-s' }, 'minecraft:' + it.name), ...infoOf(it).map(t => h('div', { class: 'tp-tip-i' }, t)), h('div', { class: 'tp-tip-h' }, L('Rechtsklick: Aktionen', 'Right-click: actions')));
    place(tip, ev.clientX, ev.clientY);
  }
  function showMenu(ev, it) {
    ev.preventDefault(); hideTip();
    const entry = it.kind === 'entry' ? it.entry : null;
    const item = (icon, label, fn, danger) => h('div', { class: 'tp-mi' + (danger ? ' bad' : ''), onclick: ev2 => { ev2.stopPropagation(); hideMenu(); fn(); } }, h('span', { class: 'ic' }, icon), label);
    const kids = [h('div', { class: 'tp-mh' }, pretty(it.name))];
    if (entry) {
      kids.push(item('✎', L('Textur bearbeiten', 'Edit texture'), () => startEntryEditor(entry.id)),
        item('★', L('Fähigkeiten zuweisen', 'Assign abilities'), () => { tab = 'entries'; open = entry.id; render(); window.scrollTo({ top: 0, behavior: 'smooth' }); }),
        item('⧉', L('Duplizieren / Details', 'Duplicate / details'), () => { tab = 'entries'; open = entry.id; render(); window.scrollTo({ top: 0, behavior: 'smooth' }); }),
        item('⏻', entry.enabled ? L('Ausschalten', 'Disable') : L('Einschalten', 'Enable'), async () => { if (await call('entries/' + entry.id + '/enabled', 'POST', { enabled: !entry.enabled })) render(); }),
        item('⌫', L('Ins Archiv', 'Archive'), async () => { if (await ctx.confirm(L('Eintrag ins Archiv verschieben? Du kannst ihn zurueckholen.', 'Move entry to the archive? You can bring it back.'))) { if (await call('entries/' + entry.id, 'DELETE', undefined, L('Im Archiv', 'Archived'))) render(); } }, true));
    } else {
      kids.push(item('✎', L('Textur bearbeiten', 'Edit texture'), () => startOverrideEditor(it)));
      if (it.kind !== 'gui') kids.push(item('⧉', L('Kopieren als neues Item …', 'Copy as new item …'), () => { dlg = { mode: 'copy', it }; pick = it; render(); window.scrollTo({ top: 0, behavior: 'smooth' }); }),
        item('★', L('Fähigkeiten zuweisen …', 'Assign abilities …'), () => { dlg = { mode: 'abilities', it }; pick = it; render(); window.scrollTo({ top: 0, behavior: 'smooth' }); }));
      if (hasOvr(it.kind, it.name)) kids.push(item('↺', L('Zurücksetzen auf Standard', 'Reset to default'), async () => {
        if (!(await ctx.confirm(L('Deine Änderung an „' + pretty(it.name) + '“ entfernen? Danach gilt wieder das Server-Pack bzw. die Standard-Textur.', 'Remove your change to "' + pretty(it.name) + '"? The server pack or default texture applies again.')))) return;
        try { await ctx.api('override/' + it.kind + '/' + it.name, { method: 'DELETE' }); ctx.toast(L('Zurückgesetzt', 'Reset'), 'good'); await reloadSources(); render(); } catch (e) { ctx.toast(e.message, 'bad'); }
      }, true));
      kids.push(item('ⓘ', L('Details anzeigen', 'Show details'), () => { pick = it; dlg = null; render(); }));
    }
    menu.replaceChildren(...kids);
    place(menu, ev.clientX - 10, ev.clientY - 10);
  }

  function slot(it, i) {
    const src = it.kind === 'entry' ? texUrl(it.entry.id) : gameUrl(it.kind, it.name);
    const mark = it.kind === 'entry' ? null : hasOvr(it.kind, it.name) ? h('i', { class: 'mk own', title: L('eigene Änderung', 'own change') }) : hasPack(it.kind, it.name) ? h('i', { class: 'mk pack', title: L('vom Server-Pack', 'from server pack') }) : null;
    return h('div', { class: 'tp-slot' + (pick && pick.kind === it.kind && pick.name === it.name ? ' sel' : '') + (it.kind === 'gui' ? ' gui' : ''), style: '--i:' + Math.min(i, 40),
      onmouseenter: ev => showTip(ev, it.kind === 'entry' ? { ...it, name: it.entry.id } : it), onmousemove: ev => { if (tip.style.display === 'block') place(tip, ev.clientX, ev.clientY); }, onmouseleave: hideTip,
      oncontextmenu: ev => showMenu(ev, it), onclick: () => { hideTip(); if (it.kind === 'entry') { tab = 'entries'; open = it.entry.id; render(); return; } pick = it; dlg = null; render(); } },
      h('img', { src, loading: 'lazy', onerror: ev => { ev.target.style.visibility = 'hidden'; } }), mark);
  }

  function inventoryView() {
    if (!van) return h('div', { class: 'empty' }, '…');
    if (!van.ready || !van.guiReady) {
      return h('div', { class: 'card tp-pop' },
        h('h2', { style: 'margin-top:0' }, van.ready ? L('GUI-Bilder nachladen', 'Load GUI images') : L('Standard-Texturen laden', 'Load default textures')),
        h('p', { class: 'sub' }, L('Damit du Gegenstaende, Bloecke und die Oberflaeche (Inventar, Hotbar, Herzen ...) wie im Kreativ-Inventar ansehen und aendern kannst, holt der Server einmalig die Bilder deiner Spielversion (' + van.version + ') von den offiziellen Mojang-Servern. Sie werden nur auf deinem Server gespeichert und nicht weitergegeben.',
          'To browse and change items, blocks and the interface (inventory, hotbar, hearts ...) like the creative inventory, the server downloads the images of your game version (' + van.version + ') once from the official Mojang servers. They are stored on your server only and never redistributed.')),
        van.error ? h('p', { style: 'color:var(--bad)' }, van.error) : null,
        h('button', { class: 'px-btn primary', disabled: van.busy, onclick: startDownload }, van.busy ? L('Laedt …', 'Downloading …') : L('Jetzt herunterladen', 'Download now')));
    }
    const isGui = cat === 'gui', isCustom = cat === 'custom';
    let list;
    if (isCustom) list = entries.map(e => ({ kind: 'entry', name: e.id, entry: e }));
    else if (isGui) list = guiList();
    else list = universe().filter(it => cat === 'all' || catOf(it) === cat);
    const query = q.trim().toLowerCase().replace(/ /g, '_');
    if (query) list = list.filter(it => it.name.includes(query));
    if (onlyChanged && !isCustom) list = list.filter(it => hasPack(it.kind, it.name) || hasOvr(it.kind, it.name));
    const total = list.length, visible = list.slice(0, limit);
    const search = h('input', { id: 'tp-search', placeholder: '🔍 ' + L('Suchen, z. B. diamond oder sword', 'Search, e.g. diamond or sword'), value: q, style: 'max-width:340px',
      oninput: ev => { q = ev.target.value; limit = 360; const pos = ev.target.selectionStart; render(); const i = document.getElementById('tp-search'); if (i) { i.focus(); i.setSelectionRange(pos, pos); } } });
    const layerBtn = (k, l) => h('button', { class: 'px-btn small ' + (layer === k ? 'active' : ''), onclick: () => { layer = k; render(); } }, l);
    return h('div', {},
      pk.ok || pk.name ? null : h('div', { class: 'sub', style: 'margin-bottom:8px' }, L('Das Server-Pack ist nicht lesbar (noch nicht gebaut oder kein Zugriff auf die Pack-Seite). Es werden nur Standard-Bilder gezeigt.', 'The server pack cannot be read (not built yet or no access to the pack page). Only default images are shown.')),
      h('div', { class: 'tp-inv' },
        h('div', { class: 'tp-tabs' }, CATS.map(([k, l, ic]) => h('button', { class: 'tp-tab' + (cat === k ? ' on' : ''), title: l, onclick: () => { cat = k; limit = 360; pick = null; render(); } }, h('span', {}, ic), h('b', {}, l)))),
        h('div', { class: 'tp-invbody' },
          h('div', { class: 'row', style: 'margin-bottom:10px' }, search,
            h('span', { class: 'sub' }, L('Anzeige:', 'View:')), layerBtn('standard', L('Standard', 'Default')), layerBtn('pack', L('Server-Pack', 'Server pack')), layerBtn('both', L('Beides', 'Both')),
            pk.name ? h('span', { class: 'tag good', title: L('aktives Server-Pack', 'active server pack') }, pk.name) : null,
            isCustom ? null : h('label', { class: 'row', style: 'margin:0;gap:6px' }, h('input', { type: 'checkbox', style: 'width:auto', checked: onlyChanged, onchange: ev => { onlyChanged = ev.target.checked; render(); } }), L('nur geänderte', 'changed only')),
            isCustom || isGui ? null : h('label', { class: 'row', style: 'margin:0;gap:6px' }, h('input', { type: 'checkbox', style: 'width:auto', checked: onlyGame, onchange: ev => { onlyGame = ev.target.checked; render(); } }), L('nur echte Items', 'real items only'))),
          isGui ? h('div', { class: 'row', style: 'margin-bottom:10px' }, [['all', L('Alles', 'All')], ['container', L('Inventar & Container', 'Inventory & containers')], ['hud', L('Hotbar, Herzen, Hunger', 'Hotbar, hearts, hunger')], ['menus', L('Menüs & Knöpfe', 'Menus & buttons')]].map(([k, l]) => h('button', { class: 'px-btn small ' + (guiCat === k ? 'active' : ''), onclick: () => { guiCat = k; limit = 360; render(); } }, l))) : null,
          h('div', { class: 'sub', style: 'margin-bottom:8px' }, total + ' ' + L('Treffer', 'results') + (isCustom ? '' : ' · ' + L('Rechtsklick auf ein Bild: bearbeiten, kopieren, Fähigkeiten, zurücksetzen', 'right-click an image: edit, copy, abilities, reset'))),
          visible.length ? h('div', { class: 'tp-grid' }, visible.map(slot)) : h('div', { class: 'empty' }, isCustom ? L('Noch keine eigenen Items. Rechtsklick auf ein Item > Kopieren als neues Item.', 'No custom items yet. Right-click an item > Copy as new item.') : L('Nichts gefunden.', 'Nothing found.')),
          total > visible.length ? h('div', { style: 'margin-top:12px' }, h('button', { class: 'px-btn', onclick: () => { limit += 360; render(); } }, L('Mehr anzeigen (' + (total - visible.length) + ' weitere)', 'Show more (' + (total - visible.length) + ' more)'))) : null)));
  }

  /** Detail zur ausgewaehlten Textur: Standard und Server-Pack nebeneinander, die gleichen Aktionen wie im Rechtsklick-Menue. */
  function pickDetail(it) {
    const imgBox = (src, label) => h('div', { class: 'tp-cmp' }, h('div', { class: 'tp-cmp-img' }, h('img', { src, onerror: ev => { ev.target.style.visibility = 'hidden'; } })), h('div', { class: 'sub' }, label));
    const kinds = [];
    if (hasVan(it.kind, it.name)) kinds.push(imgBox(vurl(it.kind, it.name), L('Standard', 'Default')));
    if (hasPack(it.kind, it.name)) kinds.push(imgBox(purl(it.kind, it.name), pk.name ? L('Server-Pack: ', 'Server pack: ') + pk.name : L('Server-Pack', 'Server pack')));
    if (hasOvr(it.kind, it.name)) kinds.push(imgBox(ourl(it.kind, it.name), L('Deine Änderung', 'Your change')));
    return h('div', { class: 'card tp-pop tp-detail' },
      h('div', { class: 'row' }, h('div', { class: 'grow' }, h('h2', { style: 'margin:0' }, pretty(it.name)), h('div', { class: 'sub' }, 'minecraft:' + it.name + ' · ' + infoOf(it).join(' · '))), h('button', { class: 'px-btn small ghost', onclick: () => { pick = null; render(); } }, '×')),
      h('div', { class: 'row', style: 'margin:12px 0;align-items:flex-start' }, kinds.length ? kinds : h('div', { class: 'sub' }, L('Kein Bild vorhanden.', 'No image.'))),
      h('div', { class: 'row' },
        h('button', { class: 'px-btn primary', onclick: () => startOverrideEditor(it) }, '✎ ' + L('Textur bearbeiten', 'Edit texture')),
        it.kind !== 'gui' ? h('button', { class: 'px-btn', onclick: () => { dlg = { mode: 'copy', it }; render(); } }, '⧉ ' + L('Kopieren als neues Item', 'Copy as new item')) : null,
        it.kind !== 'gui' ? h('button', { class: 'px-btn', onclick: () => { dlg = { mode: 'abilities', it }; render(); } }, '★ ' + L('Fähigkeiten zuweisen', 'Assign abilities')) : null,
        hasOvr(it.kind, it.name) ? h('button', { class: 'px-btn danger', onclick: async () => {
          if (!(await ctx.confirm(L('Deine Änderung entfernen?', 'Remove your change?')))) return;
          try { await ctx.api('override/' + it.kind + '/' + it.name, { method: 'DELETE' }); ctx.toast(L('Zurückgesetzt', 'Reset'), 'good'); await reloadSources(); render(); } catch (e) { ctx.toast(e.message, 'bad'); }
        } }, '↺ ' + L('Zurücksetzen auf Standard', 'Reset to default')) : null));
  }

  /** Kopie anlegen / Fähigkeiten zuweisen: legt einen Eintrag (Gegenstand + Name am Amboss) mit dieser Textur an. */
  function copyDialog(d) {
    const it = d.it, mat = it.name.toUpperCase();
    const idIn = h('input', { value: 'mein_' + it.name.slice(0, 24), placeholder: 'kennung_klein' }), nameIn = h('input', { placeholder: L('Name im Spiel (Amboss)', 'Name in game (anvil)'), value: pretty(it.name).replace(/\b\w/g, c => c.toUpperCase()) });
    const matIn = h('input', { list: 'mats', value: meta.materials.includes(mat) ? mat : '', placeholder: 'DIAMOND_SWORD' });
    return h('div', { class: 'card tp-pop' },
      h('div', { class: 'row' }, h('img', { src: gameUrl(it.kind, it.name), class: 'tp-vbig' }), h('div', { class: 'grow' }, h('h2', { style: 'margin:0' }, d.mode === 'copy' ? L('Kopieren als neues Item', 'Copy as new item') : L('Fähigkeiten zuweisen', 'Assign abilities')),
        h('div', { class: 'sub' }, d.mode === 'copy' ? L('Neuer Eintrag mit dieser Textur. Danach kannst du Namen, Bild und Fähigkeiten frei ändern.', 'New entry with this texture. Afterwards you can freely change name, image and abilities.')
          : L('Fähigkeiten hängen an einem eigenen Item (Gegenstand + Name am Amboss). Ich lege dafür eine Kopie an und öffne danach die Fähigkeiten.', 'Abilities belong to a custom item (item + anvil name). I create a copy for that and then open the abilities.'))),
        h('button', { class: 'px-btn small ghost', onclick: () => { dlg = null; render(); } }, '×')),
      h('datalist', { id: 'mats' }, meta.materials.map(m => h('option', { value: m }))),
      h('div', { class: 'row', style: 'margin-top:10px' }, h('div', {}, h('label', {}, L('Kennung', 'Id')), idIn), h('div', {}, h('label', {}, L('Gegenstand', 'Item type')), matIn), h('div', {}, h('label', {}, L('Name', 'Name')), nameIn)),
      h('div', { class: 'row', style: 'margin-top:12px' }, h('button', { class: 'px-btn primary', onclick: async () => {
        try {
          const c = await firstFrame(gameUrl(it.kind, it.name));
          if (![16, 32, 64, 128, 256, 512].includes(c.width)) { ctx.toast(L('Diese Textur hat eine ungewoehnliche Groesse.', 'This texture has an unusual size.'), 'bad'); return; }
          if (await call('entries', 'POST', { id: idIn.value, material: matIn.value.trim().toUpperCase(), name: nameIn.value, png: canvasB64(c) }, L('Eintrag angelegt', 'Entry created'))) {
            const id = idIn.value.trim().toLowerCase(); dlg = null; pick = null;
            if (d.mode === 'abilities') { tab = 'entries'; open = id; } else { cat = 'custom'; }
            render(); window.scrollTo({ top: 0, behavior: 'smooth' });
          }
        } catch (err) { ctx.toast(err.message || L('Das Bild konnte nicht geladen werden.', 'The image could not be loaded.'), 'bad'); }
      } }, d.mode === 'copy' ? L('Kopie anlegen', 'Create copy') : L('Anlegen und Fähigkeiten öffnen', 'Create and open abilities'))));
  }

  // Aussehen dieser Seite
  if (!document.getElementById('tp-style')) {
    const st = document.createElement('style'); st.id = 'tp-style';
    st.textContent = `
.tp-inv{display:flex;gap:0;align-items:flex-start;margin-top:12px}
.tp-tabs{display:flex;flex-direction:column;gap:3px;flex:none;width:150px}
.tp-tab{display:flex;gap:8px;align-items:center;text-align:left;background:var(--panel2);border:var(--px) solid var(--line);border-right:0;color:inherit;font-family:inherit;padding:8px 10px;cursor:pointer;transition:transform .12s,background .12s}
.tp-tab b{font-weight:normal;font-size:13px}.tp-tab span{width:20px;text-align:center}
.tp-tab:hover{background:var(--panel);transform:translateX(-3px)}.tp-tab.on{background:#c6c6c6;color:#222;transform:translateX(0);border-color:#000;font-weight:bold}
.tp-invbody{flex:1;min-width:0;background:var(--panel);border:var(--px) solid var(--line);padding:14px}
.tp-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(56px,1fr));gap:0;background:#8b8b8b;border:3px solid #373737;border-right-color:#fff;border-bottom-color:#fff;padding:2px;max-width:100%}
.tp-slot{position:relative;aspect-ratio:1;background:#8b8b8b;border:2px solid #373737;border-right-color:#fff;border-bottom-color:#fff;display:flex;align-items:center;justify-content:center;cursor:pointer;opacity:0;animation:tpin .3s ease-out forwards;animation-delay:calc(var(--i)*14ms);transition:background .1s,transform .1s}
.tp-slot img{width:76%;height:76%;object-fit:contain;object-position:top;image-rendering:pixelated;transition:transform .15s}
.tp-slot.gui img{width:88%;height:88%;object-position:center}
.tp-slot:hover{background:#c5c5c5;z-index:2}.tp-slot:hover img{transform:scale(1.18)}.tp-slot.sel{background:#d4e3ff;outline:3px solid var(--accent);z-index:3}
.tp-slot .mk{position:absolute;right:3px;top:3px;width:8px;height:8px;border:1px solid #000}.tp-slot .mk.own{background:#ffd86b}.tp-slot .mk.pack{background:#55ff55}
.tp-tip{position:fixed;display:none;z-index:9999;pointer-events:none;background:#100010f2;border:2px solid #2a0a5a;padding:6px 10px;color:#fff;font-size:13px;max-width:320px;box-shadow:0 0 0 2px #100010}
.tp-tip-t{font-weight:bold}.tp-tip-s{color:#aaa;font-size:11px}.tp-tip-i{color:#ffd86b;font-size:12px;margin-top:2px}.tp-tip-h{color:#8a8aff;font-size:11px;margin-top:4px}
.tp-menu{position:fixed;display:none;z-index:10000;min-width:240px;background:#1b1b24;border:2px solid #000;box-shadow:6px 6px 0 rgba(0,0,0,.45);animation:tppop .15s ease-out}
.tp-mh{padding:6px 12px;background:#2c2c3a;color:#ffd86b;font-weight:bold;text-transform:capitalize}
.tp-mi{display:flex;gap:10px;align-items:center;padding:8px 12px;color:#eee;cursor:pointer}.tp-mi .ic{width:18px;text-align:center}.tp-mi:hover{background:var(--accent);color:#000}.tp-mi.bad:hover{background:#d33;color:#fff}
.tp-cmp{text-align:center}.tp-cmp-img{background:repeating-conic-gradient(var(--panel2) 0 25%,var(--line) 0 50%) 0 0/16px 16px;padding:8px;border:2px solid var(--line);min-width:96px;min-height:96px;display:flex;align-items:center;justify-content:center}
.tp-cmp-img img{max-width:240px;max-height:240px;min-width:96px;image-rendering:pixelated;object-fit:contain}
.tp-vbig{width:96px;height:96px;object-fit:cover;object-position:top;aspect-ratio:1;image-rendering:pixelated;animation:tpbig .45s cubic-bezier(.2,1.4,.4,1)}
.tp-pop{animation:tppop .3s ease-out}
@keyframes tpin{from{opacity:0;transform:translateY(8px) scale(.9)}to{opacity:1;transform:none}}
@keyframes tpbig{from{transform:scale(.3) rotate(-25deg);opacity:0}to{transform:none;opacity:1}}
@keyframes tppop{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:none}}
@media (max-width:760px){.tp-inv{flex-direction:column}.tp-tabs{flex-direction:row;flex-wrap:wrap;width:auto;margin-bottom:6px}.tp-tab{border-right:var(--px) solid var(--line)}.tp-tab b{display:none}}
@media (prefers-reduced-motion:reduce){.tp-slot,.tp-vbig,.tp-pop{animation:none;opacity:1}}`;
    document.head.appendChild(st);
  }

  // ---------- Eintraege ----------
  function abilityForm(en) {
    const kind = h('select', {}, ['EFFECT', 'ATTRIBUTE', 'PARTICLE'].map(k => h('option', { value: k }, { EFFECT: L('Effekt', 'Effect'), ATTRIBUTE: L('Wert', 'Attribute'), PARTICLE: L('Partikel', 'Particle') }[k])));
    const when = h('select', {}, h('option', { value: 'WORN' }, L('getragen', 'worn')), h('option', { value: 'HELD' }, L('gehalten', 'held')));
    const name = h('input', { list: 'names' }), list = h('datalist', { id: 'names' }), level = h('input', { type: 'number', value: 1, min: 1 }), amount = h('input', { type: 'number', step: '0.1', value: 1 });
    const mul = h('select', {}, h('option', { value: 'false' }, L('fester Wert', 'flat')), h('option', { value: 'true' }, L('Anteil (0.1 = 10 %)', 'fraction (0.1 = 10 %)')));
    const fillNames = () => { list.replaceChildren(...(kind.value === 'EFFECT' ? meta.effects : kind.value === 'ATTRIBUTE' ? meta.attributes : meta.particles).map(n => h('option', { value: n }))); };
    kind.onchange = fillNames; fillNames();
    return h('div', { class: 'card', style: 'background:var(--panel2)' }, list, h('div', { class: 'row' }, kind, when, h('div', { class: 'grow' }, name)),
      h('div', { class: 'row', style: 'margin-top:8px' }, h('span', { class: 'sub' }, L('Stufe/Anzahl', 'Level/count')), level, h('span', { class: 'sub' }, L('Wert', 'Value')), amount, mul,
        h('button', { class: 'px-btn primary', onclick: async () => {
          if (await call('entries/' + en.id + '/abilities', 'POST', { kind: kind.value, when: when.value, name: name.value, level: +level.value, amount: +amount.value, multiply: mul.value === 'true' }, L('Faehigkeit hinzugefuegt', 'Ability added'))) render();
        } }, L('Hinzufuegen', 'Add'))));
  }

  function detail(en) {
    const dupId = h('input', { placeholder: 'neue_kennung' }), dupName = h('input', { placeholder: L('neuer Name', 'new name') });
    return h('div', { class: 'card' },
      h('div', { class: 'row' }, h('img', { src: texUrl(en.id), style: 'width:96px;height:96px;image-rendering:pixelated' }), h('div', { class: 'grow' }, h('h2', { style: 'margin:0' }, en.id), h('div', { class: 'sub' }, en.material + ' – "' + en.name + '"')),
        h('button', { class: 'px-btn small ghost', onclick: () => { open = null; render(); } }, '×')),
      h('div', { class: 'row', style: 'margin-top:12px' },
        h('button', { class: 'px-btn', onclick: () => startEntryEditor(en.id) }, L('Textur bearbeiten', 'Edit texture')),
        h('button', { class: 'px-btn', onclick: async () => { if (await call('entries/' + en.id + '/enabled', 'POST', { enabled: !en.enabled })) render(); } }, en.enabled ? L('Ausschalten', 'Disable') : L('Einschalten', 'Enable')),
        h('button', { class: 'px-btn danger', onclick: async () => { if (await ctx.confirm(L('Eintrag ins Archiv verschieben? Du kannst ihn zurueckholen.', 'Move entry to the archive? You can bring it back.'))) { if (await call('entries/' + en.id, 'DELETE', undefined, L('Im Archiv', 'Archived'))) { open = null; render(); } } } }, L('Loeschen (Archiv)', 'Delete (archive)'))),
      h('h2', {}, L('Duplizieren', 'Duplicate')), h('div', { class: 'row' }, dupId, dupName, h('button', { class: 'px-btn', onclick: async () => { if (await call('entries/' + en.id + '/duplicate', 'POST', { id: dupId.value, name: dupName.value }, L('Dupliziert', 'Duplicated'))) { open = dupId.value.toLowerCase(); render(); } } }, L('Duplizieren', 'Duplicate'))),
      h('h2', {}, L('Faehigkeiten', 'Abilities') + ' (' + en.abilities.length + '/' + meta.maxAbilities + ')'),
      en.abilities.length ? h('table', {}, en.abilities.map((a, i) => h('tr', {}, h('td', {}, i + 1), h('td', {}, a.text), h('td', {}, h('button', { class: 'px-btn small danger', onclick: async () => { if (await call('entries/' + en.id + '/abilities/' + (i + 1), 'DELETE')) render(); } }, '×'))))) : h('div', { class: 'sub' }, L('Keine.', 'None.')),
      en.abilities.length < meta.maxAbilities ? abilityForm(en) : null);
  }

  function render() {
    hideTip(); hideMenu();
    const cur = entries.find(e => e.id === open);
    const set = (...kids) => root.replaceChildren(...kids.filter(k => k != null && k !== false));
    set(
      h('h1', {}, L('Texturen', 'Textures')),
      h('p', { class: 'sub' }, L('Wie das Kreativ-Inventar: Rechtsklick auf ein Bild, dann wählst du, was du damit tun willst. Änderungen bauen das Pack automatisch neu und gelten sofort für alle Spieler.', 'Like the creative inventory: right-click an image, then choose what to do. Changes rebuild the pack automatically and apply to all players right away.')),
      h('div', { class: 'row' },
        [['inventory', L('Inventar', 'Inventory')], ['entries', L('Eintraege', 'Entries') + ' (' + entries.length + ')'], ['archive', L('Archiv', 'Archive') + ' (' + archive.length + ')']].map(([k, l]) => h('button', { class: 'px-btn ' + (tab === k ? 'active' : ''), onclick: () => { tab = k; open = null; render(); } }, l)),
        h('span', { class: 'grow' }), h('button', { class: 'px-btn primary', onclick: () => startEntryEditor(null) }, '+ ' + L('Neue Textur zeichnen', 'Draw new texture')),
        h('button', { class: 'px-btn', onclick: () => importPicker.click() }, L('PNG importieren', 'Import PNG'))),
      editor ? editorView() : null,
      !editor && dlg ? copyDialog(dlg) : null,
      !editor && !dlg && tab === 'inventory' && pick ? pickDetail(pick) : null,
      cur && tab === 'entries' && !editor ? detail(cur) : null,
      tab === 'inventory' ? inventoryView() : null,
      tab === 'inventory' ? null : tab === 'entries'
        ? (entries.length ? h('div', { class: 'grid', style: 'margin-top:14px' }, entries.map(e => h('div', { class: 'tile' + (e.id === open ? ' sel' : '') + (e.enabled ? '' : ' off'), onclick: () => { open = e.id; render(); } },
          h('img', { src: texUrl(e.id) }), h('div', { class: 'nm' }, e.id), h('div', { class: 'meta' }, e.material), h('div', { class: 'meta' }, '"' + e.name + '"'), e.abilities.length ? h('span', { class: 'tag' }, e.abilities.length + ' ★') : null)))
          : h('div', { class: 'empty', style: 'margin-top:14px' }, L('Noch keine Eintraege. Rechtsklick auf ein Item im Inventar > Kopieren als neues Item.', 'No entries yet. Right-click an item in the inventory > Copy as new item.')))
        : (archive.length ? h('div', { class: 'grid', style: 'margin-top:14px' }, archive.map(e => h('div', { class: 'tile off' }, h('img', { src: texUrl(e.id, true) }), h('div', { class: 'nm' }, e.id), h('div', { class: 'meta' }, e.material + ' "' + e.name + '"'),
          h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { class: 'px-btn small', onclick: async () => { if (await call('archive/' + e.id + '/restore', 'POST', '', L('Wiederhergestellt', 'Restored'))) render(); } }, L('Zurueckholen', 'Restore'))))))
          : h('div', { class: 'empty', style: 'margin-top:14px' }, L('Das Archiv ist leer.', 'The archive is empty.'))),
      importPicker);
  }

  const importPicker = h('input', { type: 'file', accept: 'image/png', hidden: true, onchange: e => { if (e.target.files[0]) importFile(e.target.files[0]); importPicker.value = ''; } });
  // Neue Daten vom Server (andere Admins, Pack-Wechsel): Quellen auffrischen, solange man nichts bearbeitet
  ctx.on(async d => { if ((d.module === 'texturepeak' || d.module === 'pack') && !editor && !dlg) { await rel(); if (d.module === 'pack') await reloadSources(); render(); } });
  meta = await ctx.api('meta');
  await rel();
  try { await loadVanilla(); } catch (e) { ctx.toast(e.message, 'bad'); }
  await reloadSources();
  render();
}
