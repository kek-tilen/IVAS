'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const createForm = $('create-form'), metadataForm = $('metadata-form');
  const statuses = {NEW:'Nov', IN_REVIEW:'V obravnavi', RESOLVED:'Zaključen'};
  let page = 0, current = null, request = null, routeVersion = 0, writing = false;
  function status(message, error = false) { $('invoice-status').textContent = message; $('invoice-status').className = error ? 'error' : ''; }
  function cell(row, text) { const td = document.createElement('td'); td.textContent = text; row.append(td); return td; }
  function entry(target, label, value) {
    const group = document.createElement('div'), dt = document.createElement('dt'), dd = document.createElement('dd');
    dt.textContent = label; dd.textContent = value ?? '—'; group.append(dt, dd); target.append(group);
  }
  function money(value, currency) {
    return new Intl.NumberFormat('sl-SI', {style:'currency', currency}).format(value);
  }
  function date(value) { return value ? value.split('-').reverse().join('. ') : '—'; }
  function timestamp(value) { return new Date(value).toLocaleString('sl-SI'); }
  async function api(path, options = {}) {
    const response = await fetch('/api/v1/invoices' + path, {cache:'no-store', ...options});
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || `Zahtevek ni uspel (HTTP ${response.status}).`);
    return body;
  }
  function setWriting(value) {
    writing = value; $('create-fields').disabled = value; $('metadata-fields').disabled = value;
    $('save-invoice').textContent = value ? 'Shranjujem …' : 'Shrani račun';
    $('save-metadata').textContent = value ? 'Shranjujem …' : 'Shrani obravnavo';
  }
  function addLine(line = {description:'', quantity:'1', unitNetPrice:''}) {
    if ($('invoice-lines').children.length >= 30) return;
    const row = document.createElement('div'); row.className = 'invoice-item';
    for (const [name, label, type] of [['description','Opis','text'],['quantity','Količina','number'],['unitNetPrice','Neto cena enote','number']]) {
      const wrapper = document.createElement('label'), input = document.createElement('input');
      wrapper.textContent = label; input.type = type; input.dataset.item = name; input.required = true; input.value = line[name];
      if (type === 'number') { input.min = name === 'quantity' ? '0.0001' : '0'; input.max = '1000000000'; input.step = '0.0001'; }
      else input.maxLength = 200;
      wrapper.append(input); row.append(wrapper);
    }
    const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'secondary'; remove.textContent = 'Odstrani';
    remove.addEventListener('click', () => { if ($('invoice-lines').children.length > 1) { row.remove(); updateLines(); } });
    row.append(remove); $('invoice-lines').append(row); updateLines();
  }
  function updateLines() {
    const rows = [...$('invoice-lines').children]; rows.forEach(row => row.querySelector('button').disabled = rows.length === 1);
    $('add-line').disabled = rows.length >= 30;
  }
  function invoiceInput() {
    const value = name => createForm.elements.namedItem(name).value.trim(), optional = name => value(name) || null;
    return {invoiceNumber:value('invoiceNumber'), supplierName:value('supplierName'), supplierCountry:value('supplierCountry').toUpperCase(),
      supplierVatId:optional('supplierVatId'), supplierRegistryId:optional('supplierRegistryId'), currency:value('currency'),
      issueDate:value('issueDate'), dueDate:optional('dueDate'), netTotal:value('netTotal'), taxTotal:value('taxTotal'), grossTotal:value('grossTotal'),
      items:[...$('invoice-lines').children].map(row => Object.fromEntries([...row.querySelectorAll('input')].map(i => [i.dataset.item, i.value.trim()]))) };
  }
  async function list(signal, version) {
    $('invoice-table').hidden = true; $('empty-list').hidden = true; $('pagination').hidden = true; $('list-count').textContent = '';
    const result = await api(`?page=${page}&size=20`, {signal});
    if (version !== routeVersion) return;
    $('invoice-rows').replaceChildren();
    for (const invoice of result.items) {
      const row = document.createElement('tr'), first = document.createElement('td'), link = document.createElement('a'), supplier = document.createElement('span');
      link.href = '#id=' + invoice.id; link.textContent = invoice.invoiceNumber;
      supplier.className = 'supplier-name'; supplier.textContent = invoice.supplierName; first.append(link, supplier); row.append(first);
      cell(row, invoice.supplierCountry); cell(row, date(invoice.issueDate)); cell(row, money(invoice.grossTotal, invoice.currency));
      const badge = document.createElement('span'); badge.className = 'status-badge status-' + invoice.handlingStatus; badge.textContent = statuses[invoice.handlingStatus];
      const last = document.createElement('td'); last.append(badge); row.append(last); $('invoice-rows').append(row);
    }
    $('list-count').textContent = `Skupaj shranjenih računov: ${result.totalElements}`;
    $('empty-list').hidden = result.totalElements !== 0; $('invoice-table').hidden = result.items.length === 0;
    $('pagination').hidden = result.totalPages <= 1;
    $('previous-page').disabled = page === 0; $('next-page').disabled = page + 1 >= result.totalPages;
    $('page-label').textContent = `Stran ${page + 1} / ${Math.max(1, result.totalPages)}`;
    status('');
  }
  function showDetail(detail) {
    current = detail; const invoice = detail.invoice;
    $('detail-heading').textContent = invoice.invoiceNumber; $('detail-supplier').textContent = invoice.supplierName;
    $('detail-data').replaceChildren();
    for (const [label, value] of [['Država',invoice.supplierCountry],['DDV identifikator',invoice.supplierVatId],['Registrski identifikator',invoice.supplierRegistryId],
      ['Datum izdaje',date(invoice.issueDate)],['Datum zapadlosti',date(invoice.dueDate)],['Shranjeno',timestamp(detail.createdAt)]]) entry($('detail-data'),label,value);
    $('detail-lines').replaceChildren();
    for (const line of invoice.items) { const row = document.createElement('tr'); cell(row,line.description); cell(row,line.quantity); cell(row,line.unitNetPrice + ' ' + invoice.currency); $('detail-lines').append(row); }
    $('detail-totals').replaceChildren();
    for (const [label,value] of [['Neto skupaj',invoice.netTotal],['Davek skupaj',invoice.taxTotal],['Bruto skupaj',invoice.grossTotal]]) entry($('detail-totals'),label,money(value,invoice.currency));
    metadataForm.elements.namedItem('note').value = detail.note; metadataForm.elements.namedItem('handlingStatus').value = detail.handlingStatus;
    $('detail-updated').textContent = 'Zadnja sprememba: ' + timestamp(detail.updatedAt);
    $('detail-panel').hidden = false;
  }
  async function route() {
    const version = ++routeVersion; if (request) request.abort(); request = new AbortController();
    const hash = location.hash.slice(1); current = null;
    ['list-panel','create-panel','detail-panel'].forEach(id => $(id).hidden = true);
    if (hash === 'new') { $('create-panel').hidden = false; status(''); return; }
    status('Nalagam račune …');
    try {
      if (hash.startsWith('id=')) {
        const id = hash.slice(3);
        if (!/^[0-9a-f-]{36}$/i.test(id)) throw new Error('Povezava do računa ni veljavna. Vrnite se na seznam računov.');
        const detail = await api('/' + id, {signal:request.signal});
        if (version === routeVersion) { showDetail(detail); status(''); }
      } else { $('list-panel').hidden = false; await list(request.signal, version); }
    } catch (error) { if (version === routeVersion && error.name !== 'AbortError') status(error.message || 'Povezava do shrambe računov ni uspela. Poskusite znova.', true); }
  }
  $('add-line').addEventListener('click', () => addLine());
  $('sample-invoice').addEventListener('click', () => {
    const today = new Date(), due = new Date(); due.setDate(today.getDate() + 30);
    const iso = value => [value.getFullYear(),String(value.getMonth()+1).padStart(2,'0'),String(value.getDate()).padStart(2,'0')].join('-');
    const sample = {invoiceNumber:'DEMO-' + iso(today), supplierName:'Sintetični dobavitelj d.o.o.', supplierCountry:'SI',
      supplierVatId:'', supplierRegistryId:'', currency:'EUR', issueDate:iso(today), dueDate:iso(due), netTotal:'250.00', taxTotal:'55.00', grossTotal:'305.00', note:'Sintetični primer za demonstracijo.', handlingStatus:'NEW'};
    for (const [name,value] of Object.entries(sample)) createForm.elements.namedItem(name).value = value;
    $('invoice-lines').replaceChildren(); addLine({description:'Razvoj aplikacije',quantity:'2',unitNetPrice:'100.00'}); addLine({description:'Podpora',quantity:'1',unitNetPrice:'50.00'});
    status('Sintetični primer je pripravljen. Za shranjevanje izberite »Shrani račun«.');
  });
  createForm.addEventListener('submit', async event => {
    event.preventDefault(); if (writing || !createForm.reportValidity()) return;
    const version = routeVersion, payload = {invoice:invoiceInput(), note:createForm.elements.namedItem('note').value, handlingStatus:createForm.elements.namedItem('handlingStatus').value};
    setWriting(true); status('Shranjujem račun …');
    try {
      const result = await api('', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
      createForm.reset(); $('invoice-lines').replaceChildren(); addLine(); page = 0;
      if (version === routeVersion) location.hash = 'id=' + result.id;
    } catch (error) { if (version === routeVersion) status(error.message || 'Shranjevanje ni uspelo. Vneseni podatki so ohranjeni.',true); }
    finally { setWriting(false); }
  });
  metadataForm.addEventListener('submit', async event => {
    event.preventDefault(); if (writing || !current || !metadataForm.reportValidity()) return;
    const version = routeVersion, payload = {version:current.version, note:metadataForm.elements.namedItem('note').value, handlingStatus:metadataForm.elements.namedItem('handlingStatus').value}, id = current.id;
    setWriting(true); status('Shranjujem obravnavo …');
    try {
      const result = await api('/' + id,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
      if (version === routeVersion) { showDetail(result); status('Obravnava je shranjena.'); }
    } catch (error) { if (version === routeVersion) status(error.message || 'Shranjevanje ni uspelo. Vnesena opomba je ohranjena.',true); }
    finally { setWriting(false); }
  });
  $('reload-list').addEventListener('click', route); $('reload-detail').addEventListener('click', route);
  $('previous-page').addEventListener('click', () => { if (page > 0) { page--; route(); } });
  $('next-page').addEventListener('click', () => { page++; route(); });
  window.addEventListener('hashchange',route);
  addLine(); route();
})();
