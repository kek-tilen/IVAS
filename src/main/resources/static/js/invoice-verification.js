'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const form = $('verification-form');
  const field = name => form.elements.namedItem(name);
  let controller = null, generation = 0, busy = false, available = false, saved = null;
  const labels = {PASS:'Uspešno', FAIL:'Neskladje', UNKNOWN:'Ni potrjeno', NOT_APPLICABLE:'Ni relevantno'};
  const registryLabels = {FOUND:'Zapis pridobljen', PARTIAL:'Delni podatki', NOT_FOUND:'Zapis ni najden',
    SOURCE_ERROR:'Vir ni dosegljiv ali prepoznan', UNSUPPORTED:'Država ni podprta', MISSING_IDENTIFIER:'Manjka IČO'};
  function status(text, error = false) { $('verification-status').textContent = text; $('verification-status').className = error ? 'error' : ''; }
  function clear() {
    saved = null;
    $('verification-results').hidden = true;
    ['local-panel','supplier-panel','report-panel','json-panel'].forEach(id => $(id).hidden = true);
    $('progress').replaceChildren(); $('local-checks').replaceChildren(); $('findings').replaceChildren();
    $('result-json').textContent = ''; status('');
  }
  function setBusy(value) {
    busy = value; $('verification-fields').disabled = value || !available; $('cancel').hidden = !value;
    $('verification-results').setAttribute('aria-busy', String(value));
  }
  function addItem(item = {description:'',quantity:'1',unitNetPrice:''}) {
    if ($('invoice-items').children.length >= 30) return;
    const row = document.createElement('div'); row.className = 'invoice-item';
    [['description','Opis','text'],['quantity','Količina','number'],['unitNetPrice','Neto cena enote','number']].forEach(([name,label,type]) => {
      const wrapper = document.createElement('label'); wrapper.textContent = label;
      const input = document.createElement('input'); input.dataset.item = name; input.type = type; input.required = true;
      if (type === 'number') { input.min = name === 'quantity' ? '0.0001' : '0'; input.max = '1000000000'; input.step = '0.0001'; }
      else input.maxLength = 200;
      input.value = item[name]; wrapper.append(input); row.append(wrapper);
    });
    const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'secondary'; remove.textContent = 'Odstrani';
    remove.addEventListener('click', () => { if ($('invoice-items').children.length > 1) { row.remove(); clear(); updateItems(); } });
    row.append(remove); $('invoice-items').append(row); updateItems();
  }
  function updateItems() {
    const rows = [...$('invoice-items').children];
    rows.forEach(row => row.querySelector('button').disabled = rows.length === 1);
    $('add-item').disabled = rows.length >= 30;
  }
  function invoice() {
    const value = name => field(name).value.trim();
    const optional = name => value(name) || null;
    return {invoiceNumber:value('invoiceNumber'), supplierName:value('supplierName'), supplierCountry:value('supplierCountry').toUpperCase(),
      supplierVatId:optional('supplierVatId'), supplierRegistryId:optional('supplierRegistryId'), issueDate:value('issueDate'),
      dueDate:optional('dueDate'), currency:value('currency'), netTotal:value('netTotal'), taxTotal:value('taxTotal'), grossTotal:value('grossTotal'),
      items:[...$('invoice-items').children].map(row => Object.fromEntries([...row.querySelectorAll('input')].map(i => [i.dataset.item,i.value.trim()]))) };
  }
  function log(text) { $('verification-results').hidden = false; const li = document.createElement('li'); li.textContent = text; $('progress').append(li); }
  function local(result) {
    $('local-panel').hidden = false; $('local-checks').replaceChildren();
    $('local-totals').textContent = `Izračunana neto vsota: ${result.calculatedLineNetTotal}; pričakovani bruto: ${result.expectedGrossTotal}. Valuta: ${field('currency').value}.`;
    for (const c of result.checks || []) {
      const li = document.createElement('li'); const strong = document.createElement('strong');
      strong.textContent = labels[c.status] || c.status; strong.className = c.status === 'FAIL' ? 'check-fail' : c.status === 'PASS' ? 'check-pass' : '';
      li.append(strong, document.createTextNode(c.message)); $('local-checks').append(li);
    }
  }
  function supplier(result) {
    $('supplier-panel').hidden = false;
    const c = result.supplier;
    $('supplier-result').textContent = `${registryLabels[result.status] || result.status}\n${result.message || ''}`
      + (c ? `\nNaziv: ${c.name || '—'}\nIČO: ${c.registryId || '—'}\nDDV: ${c.vatId || '—'}` : '')
      + `\nVir: ${result.source || '—'}\nČas pridobitve: ${result.fetchedAt ? new Date(result.fetchedAt).toLocaleString('sl-SI') : '—'}`;
  }
  function save(result) { saved = result; $('json-panel').hidden = false; $('result-json').textContent = JSON.stringify(result, null, 2); }
  function report(result) {
    $('report-panel').hidden = false;
    $('assessment').textContent = ({REVIEW_REQUIRED:'Potreben je pregled', INCOMPLETE_VERIFICATION:'Preverjanje ni popolno', NO_ISSUES_DETECTED:'V izvedenem obsegu ni zaznanih neskladij'})[result.assessment] || 'Ocena ni znana';
    $('recommendation').textContent = result.recommendedAction;
    for (const finding of result.findings || []) { const li = document.createElement('li'); li.textContent = finding; $('findings').append(li); }
    $('report-scope').textContent = result.scope; save(result);
  }
  async function post(path, body, signal) {
    const response = await fetch('/api/v1/verification/' + path, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body), signal, cache:'no-store'});
    if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || `Zahtevek ni uspel (HTTP ${response.status}).`); }
    return response;
  }
  async function run(action) {
    if (busy || !available) return;
    if (action !== 'supplier' && !form.reportValidity()) return;
    const data = invoice();
    if (action === 'supplier' && (data.supplierCountry !== 'CZ' || !/^[0-9]{8}$/.test(data.supplierRegistryId || ''))) {
      status('Za ARES vnesite državo CZ in osemmestni IČO. Začetne ničle se ohranijo.', true); return;
    }
    if (action === 'stream' && field('checkRegistry').checked && data.supplierCountry === 'CZ'
        && data.supplierRegistryId && !/^[0-9]{8}$/.test(data.supplierRegistryId)) {
      status('IČO mora vsebovati natanko osem števk.', true); return;
    }
    clear(); const current = ++generation; controller = new AbortController(); setBusy(true); status('Preverjanje poteka …');
    const signal = controller.signal;
    try {
      if (action === 'local') {
        const result = await (await post('local', {invoice:data}, signal)).json();
        if (current !== generation) return;
        log('Lokalna preverjanja so zaključena. Registri niso bili klicani.'); local(result.local); save(result);
        status('Lokalni pregled je zaključen. To ni celovita potrditev računa.');
      } else if (action === 'supplier') {
        log('Pridobivanje podatkov ARES …');
        const result = await (await post('supplier', {name:data.supplierName,country:data.supplierCountry,registryId:data.supplierRegistryId,vatId:data.supplierVatId}, signal)).json();
        if (current !== generation) return;
        supplier(result.result); save(result); status('Pridobivanje podatkov dobavitelja je zaključeno.');
      } else {
        const response = await post('stream', {invoice:data,checkRegistry:field('checkRegistry').checked}, signal);
        if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new Error('Strežnik ni vrnil pričakovanega toka.');
        const reader = response.body.getReader(); const decoder = new TextDecoder();
        let buffer = '', lastSequence = 0, requestId = null, completed = false;
        const consume = frame => {
          if (!frame.trim()) return;
          let type = 'message'; const parts = [];
          for (const line of frame.split(/\r?\n/)) {
            if (line.startsWith('event:')) type = line.slice(6).trim();
            else if (line.startsWith('data:')) parts.push(line.slice(5).trimStart());
          }
          if (!parts.length) return;
          const event = JSON.parse(parts.join('\n'));
          if (type === 'failure') throw new Error(event.message || 'Tok preverjanja je bil prekinjen.');
          if (type !== 'verification' || completed) throw new Error('Nepričakovan dogodek v toku preverjanja.');
          if (event.sequence !== lastSequence + 1 || (requestId && event.requestId !== requestId)) throw new Error('Zaporedje rezultatov ni veljavno.');
          lastSequence = event.sequence; requestId = event.requestId;
          if (event.started) log('Preverjanje računa se je začelo.');
          else if (event.local) { local(event.local); log('Lokalne vsote in datumi so preverjeni.'); }
          else if (event.registryStarted) log('Pridobivanje registrskih podatkov ARES …');
          else if (event.supplier) { supplier(event.supplier); log('Rezultat registrskega preverjanja je prejet.'); }
          else if (event.report) {
            if (event.report.requestId !== requestId) throw new Error('Poročilo ne pripada temu zahtevku.');
            report(event.report); completed = true; log('Končno poročilo je pripravljeno.');
          } else throw new Error('Dogodek nima pričakovane vsebine.');
        };
        try {
          while (true) {
            const chunk = await reader.read();
            if (current !== generation) return;
            buffer += decoder.decode(chunk.value || new Uint8Array(), {stream:!chunk.done});
            if (buffer.length > 262144) throw new Error('Tok presega dovoljeno velikost dogodka.');
            let match;
            while ((match = /\r?\n\r?\n/.exec(buffer))) { consume(buffer.slice(0,match.index)); buffer = buffer.slice(match.index + match[0].length); }
            if (chunk.done) break;
          }
          if (buffer.trim() || !completed) throw new Error('Tok se je zaključil brez veljavnega končnega poročila. Delni rezultati niso dokončni.');
          status('Pregled je zaključen. Preberite ugotovitve in omejitve v končnem poročilu.');
        } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
      }
    } catch (error) {
      if (current !== generation) return;
      status(error.name === 'AbortError' ? 'Preverjanje je prekinjeno. Morebitni delni rezultati niso dokončni.' : error.message, true);
      if (action === 'stream') { saved = null; $('json-panel').hidden = true; $('report-panel').hidden = true; }
    } finally { if (current === generation) { controller = null; setBusy(false); } }
  }
  async function capabilities() {
    $('retry-capabilities').hidden = true;
    try {
      const response = await fetch('/api/v1/verification/capabilities', {cache:'no-store',signal:AbortSignal.timeout(7000)});
      if (!response.ok) throw new Error();
      const data = await response.json();
      $('capabilities').textContent = `Na voljo: vsote in datumi; register za ${(data.registryCountries || []).join(', ')}. Največ ${data.maxItems} postavk. Uvoz UBL ni vključen.`;
      $('capabilities').className = 'notice'; available = true; setBusy(false);
    } catch {
      $('capabilities').textContent = 'Povezava s storitvijo trenutno ni na voljo. Preverite zagon IVAS in poskusite znova.';
      $('capabilities').className = 'notice error'; $('retry-capabilities').hidden = false;
    }
  }
  form.addEventListener('submit', event => { event.preventDefault(); run('stream'); });
  form.addEventListener('input', () => { if (!busy) clear(); });
  $('validate').addEventListener('click', () => run('local'));
  $('lookup').addEventListener('click', () => run('supplier'));
  $('cancel').addEventListener('click', () => controller?.abort());
  $('add-item').addEventListener('click', () => { addItem(); clear(); });
  $('retry-capabilities').addEventListener('click', capabilities);
  $('sample').addEventListener('click', () => {
    clear();
    const sample = {invoiceNumber:'DEMO-2026-001',supplierName:'Izmišljeni dobavitelj',supplierCountry:'SI',supplierVatId:'',supplierRegistryId:'',
      issueDate:'2026-11-10',dueDate:'2026-11-24',currency:'EUR',netTotal:'250.00',taxTotal:'55.00',grossTotal:'305.00'};
    for (const [name,value] of Object.entries(sample)) field(name).value = value;
    field('checkRegistry').checked = false; $('invoice-items').replaceChildren();
    addItem({description:'Storitev A',quantity:'2',unitNetPrice:'100.00'}); addItem({description:'Storitev B',quantity:'1',unitNetPrice:'50.00'});
    status('Sintetični primer je vstavljen. Za pregled izberite preverjanje.');
  });
  $('download-json').addEventListener('click', () => {
    if (!saved) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(saved,null,2)], {type:'application/json'}));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'ivas-preverjanje.json'; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url),1000);
  });
  addItem(); capabilities();
})();
