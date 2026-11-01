// Seite "Texturen": Eintraege, Faehigkeiten, Archiv und Pixel-Editor.
export default async function mount(root, ctx) {
  const { h } = ctx;
  const L = (de, en) => ctx.t({ de, en });
  let meta, entries = [], archive = [], tab = 'entries', open = null, editor = null;

  const rel = async () => { entries = await ctx.api('entries'); archive = await ctx.api('archive'); };
  async function call(path, method, body, ok) {
    try { await ctx.api(path, { method, body }); if (ok) ctx.toast(ok, 'good'); await rel(); return true; }
    catch (e) { if (e.message) ctx.toast(e.message, 'bad'); return false; }
  }
  const texUrl = (id, arch) => '/api/texturepeak/texture/' + id + '?archived=' + (arch ? 1 : 0) + '&t=' + Date.now();

  // ---------- Pixel-Editor ----------
  const PALETTE = ['#000000', '#1d2b53', '#7e2553', '#008751', '#ab5236', '#5f574f', '#c2c3c7', '#fff1e8', '#ff004d', '#ffa300', '#ffec27', '#00e436', '#29adff', '#83769c', '#ff77a8', '#ffccaa', '#00000000'];
  function newEditor(size, img) {
    const e = { size, cells: new Uint8ClampedArray(size * size * 4), color: '#ff004d', tool: 'pen', undo: [], redo: [], zoom: Math.max(8, Math.floor(512 / size)), id: null, material: null, name: '' };
    if (img) { const c = document.createElement('canvas'); c.width = c.height = size; const g = c.getContext('2d'); g.imageSmoothingEnabled = false; g.drawImage(img, 0, 0, size, size); e.cells.set(g.getImageData(0, 0, size, size).data); }
    return e;
  }
  const hex2rgba = hx => hx.length > 7 ? [0, 0, 0, 0] : [parseInt(hx.slice(1, 3), 16), parseInt(hx.slice(3, 5), 16), parseInt(hx.slice(5, 7), 16), 255];
  const rgba2hex = (c, i) => c[i + 3] === 0 ? '#00000000' : '#' + [c[i], c[i + 1], c[i + 2]].map(v => v.toString(16).padStart(2, '0')).join('');

  function editorView() {
    const e = editor, S = e.size, canvas = h('canvas', { class: 'pix', width: S * e.zoom, height: S * e.zoom });
    const g = canvas.getContext('2d');
    const draw = () => {
      g.clearRect(0, 0, canvas.width, canvas.height);
      const px = document.createElement('canvas'); px.width = px.height = S; px.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(e.cells), S, S), 0, 0);
      g.imageSmoothingEnabled = false; g.drawImage(px, 0, 0, canvas.width, canvas.height);
      if (e.zoom >= 10) { g.strokeStyle = 'rgba(128,128,128,.25)'; g.lineWidth = 1; for (let i = 1; i < S; i++) { g.beginPath(); g.moveTo(i * e.zoom + .5, 0); g.lineTo(i * e.zoom + .5, canvas.height); g.moveTo(0, i * e.zoom + .5); g.lineTo(canvas.width, i * e.zoom + .5); g.stroke(); } }
    };
    const snap = () => { e.undo.push(new Uint8ClampedArray(e.cells)); if (e.undo.length > 60) e.undo.shift(); e.redo = []; };
    const put = (x, y, col) => { if (x < 0 || y < 0 || x >= S || y >= S) return; const i = (y * S + x) * 4; e.cells.set(col, i); };
    const at = ev => { const r = canvas.getBoundingClientRect(); return [Math.floor((ev.clientX - r.left) / r.width * S), Math.floor((ev.clientY - r.top) / r.height * S)]; };
    const fill = (x, y, col) => {
      const i0 = (y * S + x) * 4, target = [...e.cells.slice(i0, i0 + 4)];
      if (target.every((v, k) => v === col[k])) return;
      const stack = [[x, y]];
      while (stack.length) { const [cx, cy] = stack.pop(); if (cx < 0 || cy < 0 || cx >= S || cy >= S) continue; const i = (cy * S + cx) * 4; if (!target.every((v, k) => e.cells[i + k] === v)) continue; e.cells.set(col, i); stack.push([cx + 1, cy], [cx - 1, cy], [cx, cy + 1], [cx, cy - 1]); }
    };
    let down = false;
    const apply = ev => {
      const [x, y] = at(ev);
      if (x < 0 || y < 0 || x >= S || y >= S) return;
      if (e.tool === 'pick') { const c = rgba2hex(e.cells, (y * S + x) * 4); e.color = c; e.tool = 'pen'; render(); return; }
      if (e.tool === 'fill') fill(x, y, hex2rgba(e.color));
      else put(x, y, e.tool === 'erase' ? [0, 0, 0, 0] : hex2rgba(e.color));
      draw();
    };
    canvas.addEventListener('pointerdown', ev => { canvas.setPointerCapture(ev.pointerId); down = true; snap(); apply(ev); });
    canvas.addEventListener('pointermove', ev => { if (down && (e.tool === 'pen' || e.tool === 'erase')) apply(ev); });
    canvas.addEventListener('pointerup', () => { down = false; });
    draw();

    const toBlob = () => new Promise(res => { const c = document.createElement('canvas'); c.width = c.height = S; c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(e.cells), S, S), 0, 0); c.toBlob(res, 'image/png'); });
    const tools = [['pen', L('Stift', 'Pen')], ['erase', L('Radierer', 'Eraser')], ['fill', L('Fuellen', 'Fill')], ['pick', L('Pipette', 'Picker')]];
    const colorInput = h('input', { type: 'color', value: e.color.length > 7 ? '#000000' : e.color, style: 'width:56px;padding:2px', oninput: ev => { e.color = ev.target.value; } });
    const swap = k => { const from = k === 'undo' ? e.undo : e.redo, to = k === 'undo' ? e.redo : e.undo; if (!from.length) return; to.push(new Uint8ClampedArray(e.cells)); e.cells = from.pop(); draw(); };

    return h('div', { class: 'card' },
      h('div', { class: 'row' }, h('h2', { style: 'margin:0' }, L('Pixel-Editor', 'Pixel editor') + ' ' + S + '×' + S + (e.id ? ' – ' + e.id : '')), h('button', { class: 'px-btn small ghost', onclick: () => { editor = null; render(); } }, L('Schliessen', 'Close'))),
      h('div', { class: 'px-edit', style: 'margin-top:12px' },
        canvas,
        h('div', {},
          h('div', { class: 'row' }, tools.map(([k, l]) => h('button', { class: 'px-btn small ' + (e.tool === k ? 'active' : ''), onclick: () => { e.tool = k; render(); } }, l))),
          h('div', { class: 'row', style: 'margin-top:8px' }, colorInput,
            h('button', { class: 'px-btn small', onclick: () => swap('undo') }, '↶ ' + L('Zurueck', 'Undo')), h('button', { class: 'px-btn small', onclick: () => swap('redo') }, '↷'),
            h('button', { class: 'px-btn small', onclick: () => { snap(); e.cells.fill(0); draw(); } }, L('Leeren', 'Clear')),
            h('button', { class: 'px-btn small', onclick: () => { snap(); const n = new Uint8ClampedArray(e.cells.length); for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) n.set(e.cells.slice((y * S + x) * 4, (y * S + x) * 4 + 4), (y * S + (S - 1 - x)) * 4); e.cells = n; draw(); } }, L('Spiegeln', 'Flip'))),
          h('div', { class: 'swatches' }, PALETTE.map(c => h('div', { class: 'sw' + (c === e.color ? ' on' : ''), title: c, style: c.length > 7 ? 'background:repeating-conic-gradient(#888 0 25%,#ccc 0 50%) 0 0/8px 8px' : 'background:' + c, onclick: () => { e.color = c; if (c.length <= 7) colorInput.value = c; render(); } }))),
          h('div', { class: 'row', style: 'margin-top:14px' },
            e.id ? h('button', { class: 'px-btn primary', onclick: async () => { const b = await toBlob(); if (await call('entries/' + e.id + '/texture', 'POST', await b.arrayBuffer(), L('Textur gespeichert', 'Texture saved'))) { editor = null; render(); } } }, L('Speichern', 'Save')) : null,
            h('button', { class: 'px-btn', onclick: async () => { const b = await toBlob(); const a = h('a', { href: URL.createObjectURL(b), download: (e.id || 'textur') + '.png' }); a.click(); } }, L('Als PNG laden', 'Download PNG'))),
          !e.id ? newEntryForm(toBlob) : null)));
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

  async function startEditor(id, arch) {
    if (!id) { editor = newEditor(16); render(); return; }
    const img = new Image(); img.src = texUrl(id, arch);
    await img.decode();
    editor = newEditor(img.naturalWidth, img); editor.id = arch ? null : id; render();
  }

  async function importFile(file) {
    const img = new Image(); img.src = URL.createObjectURL(file);
    try { await img.decode(); } catch { ctx.toast(L('Kein Bild', 'Not an image'), 'bad'); return; }
    if (![16, 32, 64, 128, 256, 512].includes(img.naturalWidth) || img.naturalWidth !== img.naturalHeight) { ctx.toast(L('Das Bild muss quadratisch sein: 16, 32, 64, 128, 256 oder 512 Pixel.', 'The image must be square: 16, 32, 64, 128, 256 or 512 px.'), 'bad'); return; }
    editor = newEditor(img.naturalWidth, img); render();
  }

  // ---------- Standard-Texturen (Auswahl per Vorschaubild) ----------
  const vurl = (kind, name) => '/api/texturepeak/vanilla/' + kind + '/' + name;
  const pretty = n => n.replace(/_/g, ' ');
  let van = null, vanSearch = '', vanKind = 'all', vanPick = null, vanPoll = null;

  async function loadVanilla() { van = await ctx.api('vanilla'); }
  async function startDownload() {
    try { van = await ctx.api('vanilla/download', { method: 'POST', body: '' }); } catch (e) { ctx.toast(e.message, 'bad'); return; }
    clearInterval(vanPoll);
    vanPoll = setInterval(async () => {
      try { await loadVanilla(); } catch { return; }
      if (!van.busy) { clearInterval(vanPoll); if (van.ready) ctx.toast(L('Standard-Texturen geladen', 'Default textures loaded'), 'good'); else if (van.error) ctx.toast(van.error, 'bad'); }
      if (tab === 'vanilla' && !editor) render();
    }, 2000);
    render();
  }

  // erstes Bild einer (evtl. animierten, hochkant gestapelten) Textur als quadratisches Canvas
  async function firstFrame(kind, name) {
    const img = new Image(); img.src = vurl(kind, name); await img.decode();
    const w = img.naturalWidth, c = document.createElement('canvas'); c.width = c.height = w;
    const g = c.getContext('2d'); g.imageSmoothingEnabled = false; g.drawImage(img, 0, 0, w, w, 0, 0, w, w);
    return c;
  }
  const canvasB64 = c => c.toDataURL('image/png').split(',')[1];

  function vanillaView() {
    if (!van) return h('div', { class: 'empty' }, '…');
    if (!van.ready) {
      return h('div', { class: 'card tp-pop' },
        h('h2', { style: 'margin-top:0' }, L('Standard-Texturen laden', 'Load default textures')),
        h('p', { class: 'sub' }, L('Damit du Gegenstaende und Bloecke per Vorschaubild auswaehlen kannst, holt der Server einmalig die Bilder deiner Spielversion (' + van.version + ') von den offiziellen Mojang-Servern. Sie werden nur auf deinem Server gespeichert und nicht weitergegeben.',
          'To pick items and blocks by preview image, the server downloads the images of your game version (' + van.version + ') once from the official Mojang servers. They are stored on your server only and never redistributed.')),
        van.error ? h('p', { style: 'color:var(--bad)' }, van.error) : null,
        h('button', { class: 'px-btn primary', disabled: van.busy, onclick: startDownload }, van.busy ? L('Laedt …', 'Downloading …') : L('Jetzt herunterladen', 'Download now')));
    }
    const mats = new Set(meta.materials), q = vanSearch.trim().toLowerCase().replace(/ /g, '_');
    const all = [];
    for (const kind of ['item', 'block']) if (vanKind === 'all' || vanKind === kind) for (const n of van[kind]) if (mats.has(n.toUpperCase()) && (!q || n.includes(q))) all.push([kind, n]);
    // gleicher Name als Item und Block nur einmal (das Item-Bild gewinnt)
    const seen = new Set(), list = all.filter(([k, n]) => (seen.has(n) ? false : seen.add(n)));
    const shown = list.slice(0, 300);
    const search = h('input', { placeholder: L('Suchen, z. B. diamond oder sword', 'Search, e.g. diamond or sword'), value: vanSearch, style: 'max-width:340px',
      oninput: ev => { vanSearch = ev.target.value; const pos = ev.target.selectionStart; render(); const i = root.querySelector('input[placeholder^="' + (L('Suchen', 'Search')) + '"]'); if (i) { i.focus(); i.setSelectionRange(pos, pos); } } });
    return h('div', {},
      h('div', { class: 'row', style: 'margin:14px 0' }, search,
        [['all', L('Alle', 'All')], ['item', L('Gegenstaende', 'Items')], ['block', L('Bloecke', 'Blocks')]].map(([k, l]) => h('button', { class: 'px-btn small ' + (vanKind === k ? 'active' : ''), onclick: () => { vanKind = k; render(); } }, l)),
        h('span', { class: 'sub' }, list.length + ' ' + L('Treffer', 'results') + (list.length > shown.length ? ' (' + L('die ersten', 'first') + ' ' + shown.length + ')' : ''))),
      vanPick ? vanillaDetail(vanPick) : null,
      shown.length ? h('div', { class: 'tp-vgrid' }, shown.map(([kind, n], i) => h('div', { class: 'tp-vtile' + (vanPick && vanPick.name === n ? ' sel' : ''), style: '--i:' + Math.min(i, 40), title: pretty(n), onclick: () => { vanPick = { kind, name: n }; render(); root.querySelector('.tp-vdetail')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); } },
        h('img', { src: vurl(kind, n), loading: 'lazy' }), h('div', { class: 'nm' }, pretty(n)))))
        : h('div', { class: 'empty' }, L('Nichts gefunden.', 'Nothing found.')));
  }

  function vanillaDetail(p) {
    const material = p.name.toUpperCase(), idIn = h('input', { placeholder: 'kennung_klein', value: 'mein_' + p.name.slice(0, 20) }), nameIn = h('input', { placeholder: L('Name im Spiel (Amboss)', 'Name in game (anvil)') });
    return h('div', { class: 'card tp-vdetail tp-pop' },
      h('div', { class: 'row' },
        h('img', { src: vurl(p.kind, p.name), class: 'tp-vbig' }),
        h('div', { class: 'grow' }, h('h2', { style: 'margin:0' }, pretty(p.name)), h('div', { class: 'sub' }, material), h('div', { class: 'sub' }, L('Was moechtest du damit tun?', 'What do you want to do with it?'))),
        h('button', { class: 'px-btn small ghost', onclick: () => { vanPick = null; render(); } }, '×')),
      h('div', { class: 'tp-choice' },
        h('div', { class: 'tp-opt' }, h('h3', {}, '⧉ ' + L('Kopieren', 'Copy')), h('p', { class: 'sub' }, L('Neuer Eintrag mit genau dieser Textur. Danach kannst du Namen, Faehigkeiten und Bild frei aendern.', 'New entry with exactly this texture. Afterwards you can change name, abilities and image freely.')),
          h('label', {}, L('Kennung', 'Id')), idIn, h('label', {}, L('Name', 'Name')), nameIn,
          h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { class: 'px-btn primary', onclick: async () => {
            const c = await firstFrame(p.kind, p.name);
            if (await call('entries', 'POST', { id: idIn.value, material, name: nameIn.value, png: canvasB64(c) }, L('Eintrag angelegt', 'Entry created'))) { open = idIn.value.trim().toLowerCase(); tab = 'entries'; vanPick = null; render(); }
          } }, L('Kopie anlegen', 'Create copy')))),
        h('div', { class: 'tp-opt' }, h('h3', {}, '✎ ' + L('Aendern', 'Modify')), h('p', { class: 'sub' }, L('Das Bild im Pixel-Editor oeffnen und veraendern, zum Beispiel umfaerben oder etwas dazumalen. Gespeichert wird als neuer Eintrag.', 'Open the image in the pixel editor and change it, for example recolor it or paint on it. It is saved as a new entry.')),
          h('div', { class: 'row', style: 'margin-top:10px' }, h('button', { class: 'px-btn primary', onclick: async () => {
            const c = await firstFrame(p.kind, p.name);
            if (![16, 32, 64, 128, 256, 512].includes(c.width)) { ctx.toast(L('Diese Textur hat eine ungewoehnliche Groesse.', 'This texture has an unusual size.'), 'bad'); return; }
            editor = newEditor(c.width, c); editor.material = material; tab = 'entries'; vanPick = null; render(); window.scrollTo({ top: 0, behavior: 'smooth' });
          } }, L('Im Editor oeffnen', 'Open in editor'))))));
  }

  // Animationen und Aussehen dieser Seite
  if (!document.getElementById('tp-style')) {
    const st = document.createElement('style'); st.id = 'tp-style';
    st.textContent = `
.tp-vgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:10px;margin-top:10px}
.tp-vtile{background:var(--panel);border:var(--px) solid var(--line);padding:8px;cursor:pointer;text-align:center;opacity:0;animation:tpin .35s ease-out forwards;animation-delay:calc(var(--i)*18ms);transition:transform .12s,border-color .12s,box-shadow .12s}
.tp-vtile:hover{transform:translate(-2px,-4px) scale(1.04);border-color:var(--accent);box-shadow:4px 4px 0 var(--accent2)}
.tp-vtile.sel{border-color:var(--accent);box-shadow:4px 4px 0 var(--accent2)}
.tp-vtile img{width:64px;height:64px;object-fit:cover;object-position:top;aspect-ratio:1;image-rendering:pixelated;transition:transform .25s}
.tp-vtile:hover img{transform:rotate(-6deg) scale(1.12)}
.tp-vtile .nm{font-size:11px;margin-top:6px;word-break:break-word;color:var(--dim)}
.tp-vbig{width:112px;height:112px;object-fit:cover;object-position:top;aspect-ratio:1;image-rendering:pixelated;animation:tpbig .45s cubic-bezier(.2,1.4,.4,1)}
.tp-choice{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px;margin-top:14px}
.tp-opt{background:var(--panel2);border:var(--px) solid var(--line);padding:14px;transition:border-color .15s,transform .15s}
.tp-opt:hover{border-color:var(--accent);transform:translateY(-2px)}
.tp-pop{animation:tppop .3s ease-out}
@keyframes tpin{from{opacity:0;transform:translateY(12px) scale(.9)}to{opacity:1;transform:none}}
@keyframes tpbig{from{transform:scale(.3) rotate(-25deg);opacity:0}to{transform:none;opacity:1}}
@keyframes tppop{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:none}}
@media (prefers-reduced-motion:reduce){.tp-vtile,.tp-vbig,.tp-pop{animation:none;opacity:1}}`;
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
        h('button', { class: 'px-btn', onclick: () => startEditor(en.id) }, L('Textur bearbeiten', 'Edit texture')),
        h('button', { class: 'px-btn', onclick: async () => { if (await call('entries/' + en.id + '/enabled', 'POST', { enabled: !en.enabled })) render(); } }, en.enabled ? L('Ausschalten', 'Disable') : L('Einschalten', 'Enable')),
        h('button', { class: 'px-btn danger', onclick: async () => { if (await ctx.confirm(L('Eintrag ins Archiv verschieben? Du kannst ihn zurueckholen.', 'Move entry to the archive? You can bring it back.'))) { if (await call('entries/' + en.id, 'DELETE', undefined, L('Im Archiv', 'Archived'))) { open = null; render(); } } } }, L('Loeschen (Archiv)', 'Delete (archive)'))),
      h('h2', {}, L('Duplizieren', 'Duplicate')), h('div', { class: 'row' }, dupId, dupName, h('button', { class: 'px-btn', onclick: async () => { if (await call('entries/' + en.id + '/duplicate', 'POST', { id: dupId.value, name: dupName.value }, L('Dupliziert', 'Duplicated'))) { open = dupId.value.toLowerCase(); render(); } } }, L('Duplizieren', 'Duplicate'))),
      h('h2', {}, L('Faehigkeiten', 'Abilities') + ' (' + en.abilities.length + '/' + meta.maxAbilities + ')'),
      en.abilities.length ? h('table', {}, en.abilities.map((a, i) => h('tr', {}, h('td', {}, i + 1), h('td', {}, a.text), h('td', {}, h('button', { class: 'px-btn small danger', onclick: async () => { if (await call('entries/' + en.id + '/abilities/' + (i + 1), 'DELETE')) render(); } }, '×'))))) : h('div', { class: 'sub' }, L('Keine.', 'None.')),
      en.abilities.length < meta.maxAbilities ? abilityForm(en) : null);
  }

  function render() {
    const cur = entries.find(e => e.id === open);
    const set = (...kids) => root.replaceChildren(...kids.filter(k => k != null && k !== false));
    set(
      h('h1', {}, L('Texturen', 'Textures')),
      h('p', { class: 'sub' }, L('Gegenstand + Name am Amboss ergibt eine eigene Textur. Aenderungen bauen das Pack automatisch neu.', 'Item + anvil name gives a custom texture. Changes rebuild the pack automatically.')),
      h('div', { class: 'row' },
        [['entries', L('Eintraege', 'Entries') + ' (' + entries.length + ')'], ['vanilla', L('Standard-Texturen', 'Default textures')], ['archive', L('Archiv', 'Archive') + ' (' + archive.length + ')']].map(([k, l]) => h('button', { class: 'px-btn ' + (tab === k ? 'active' : ''), onclick: async () => { tab = k; open = null; if (k === 'vanilla' && !van) { try { await loadVanilla(); } catch (e) { ctx.toast(e.message, 'bad'); } } render(); } }, l)),
        h('span', { class: 'grow' }), h('button', { class: 'px-btn primary', onclick: () => startEditor(null) }, '+ ' + L('Neue Textur zeichnen', 'Draw new texture')),
        h('button', { class: 'px-btn', onclick: () => picker.click() }, L('PNG importieren', 'Import PNG'))),
      editor ? editorView() : null,
      cur && tab === 'entries' && !editor ? detail(cur) : null,
      tab === 'vanilla' ? vanillaView() : null,
      tab === 'vanilla' ? null : tab === 'entries'
        ? (entries.length ? h('div', { class: 'grid', style: 'margin-top:14px' }, entries.map(e => h('div', { class: 'tile' + (e.id === open ? ' sel' : '') + (e.enabled ? '' : ' off'), onclick: () => { open = e.id; render(); } },
          h('img', { src: texUrl(e.id) }), h('div', { class: 'nm' }, e.id), h('div', { class: 'meta' }, e.material), h('div', { class: 'meta' }, '"' + e.name + '"'), e.abilities.length ? h('span', { class: 'tag' }, e.abilities.length + ' ★') : null)))
          : h('div', { class: 'empty', style: 'margin-top:14px' }, L('Noch keine Eintraege. Zeichne eine Textur oder importiere ein PNG.', 'No entries yet. Draw a texture or import a PNG.')))
        : (archive.length ? h('div', { class: 'grid', style: 'margin-top:14px' }, archive.map(e => h('div', { class: 'tile off' }, h('img', { src: texUrl(e.id, true) }), h('div', { class: 'nm' }, e.id), h('div', { class: 'meta' }, e.material + ' "' + e.name + '"'),
          h('div', { class: 'row', style: 'margin-top:8px' }, h('button', { class: 'px-btn small', onclick: async () => { if (await call('archive/' + e.id + '/restore', 'POST', '', L('Wiederhergestellt', 'Restored'))) render(); } }, L('Zurueckholen', 'Restore'))))))
          : h('div', { class: 'empty', style: 'margin-top:14px' }, L('Das Archiv ist leer.', 'The archive is empty.'))));
  }

  const picker = h('input', { type: 'file', accept: 'image/png', hidden: true, onchange: e => { if (e.target.files[0]) importFile(e.target.files[0]); picker.value = ''; } });
  ctx.on(async d => { if (d.module === 'texturepeak' && !editor) { await rel(); render(); } });
  meta = await ctx.api('meta');
  await rel();
  render();
}
