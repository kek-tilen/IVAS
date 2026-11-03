'use strict';
const form = document.getElementById('invoice-form');
const fieldset = document.getElementById('invoice-fields');
const items = document.getElementById('invoice-items');
const submitButton = document.getElementById('request-advice');
const statusText = document.getElementById('advice-status');
const configuration = document.getElementById('ai-configuration');
const results = document.getElementById('advice-results');
let configured = false;
let busy = false;
let lastResult;
let nextItem = 0;

function addItem(values = {}) {
  if (items.children.length >= 30) return;
  const row = document.createElement('div');
  row.className = 'invoice-item';
  const number = ++nextItem;
  for (const [name, caption, type, value, step, min, max] of [
    ['description', 'Opis postavke', 'text', values.description || '', null, null, null],
    ['quantity', 'Količina', 'number', values.quantity || '1', '0.0001', '0.0001', '1000000000'],
    ['unitNetPrice', 'Neto cena enote', 'number', values.unitNetPrice || '', '0.0001', '0', '1000000000']
  ]) {
    const label = document.createElement('label');
    label.textContent = caption;
    const input = document.createElement('input');
    input.type = type; input.name = name; input.value = value; input.required = true;
    input.id = `item-${number}-${name}`;
    if (type === 'text') input.maxLength = 200;
    if (step) { input.step = step; input.min = min; input.max = max; }
    label.append(input); row.append(label);
  }
  const remove = document.createElement('button');
  remove.type = 'button'; remove.className = 'secondary'; remove.textContent = 'Odstrani';
  remove.setAttribute('aria-label', `Odstrani postavko ${number}`);
  remove.addEventListener('click', () => {
    if (items.children.length > 1) { row.remove(); updateItemButtons(); clearResult(); }
  });
  row.append(remove); items.append(row); updateItemButtons();
}
function updateItemButtons() {
  document.getElementById('add-item').disabled = items.children.length >= 30;
  items.querySelectorAll('button').forEach(button => { button.disabled = items.children.length <= 1; });
}
function clearResult() {
  if (!lastResult) return;
  lastResult = undefined; results.hidden = true;
  statusText.className = '';
  statusText.textContent = 'Podatki so spremenjeni. Za novo AI razlago ponovno oddajte obrazec.';
}
function readRequest() {
  const data = new FormData(form);
  const value = name => String(data.get(name) || '').trim();
  return {
    invoiceNumber: value('invoiceNumber'), supplierName: value('supplierName'),
    supplierCountry: value('supplierCountry').toUpperCase(), supplierVatId: value('supplierVatId') || null,
    issueDate: value('issueDate'), dueDate: value('dueDate') || null, currency: value('currency'),
    items: [...items.children].map(row => Object.fromEntries(['description', 'quantity', 'unitNetPrice']
      .map(name => [name, row.querySelector(`[name="${name}"]`).value.trim()]))),
    netTotal: value('netTotal'), taxTotal: value('taxTotal'), grossTotal: value('grossTotal'),
    question: value('question'), notes: value('notes') || null, consent: data.get('consent') === 'on'
  };
}
function showList(id, values) {
  const list = document.getElementById(id);
  list.replaceChildren();
  for (const text of values.length ? values : ['Ni dodatnih navedb.']) {
    const item = document.createElement('li'); item.textContent = text; list.append(item);
  }
}
function renderResponse(response) {
  const labels = { PASS: 'Ujemanje', FAIL: 'Neskladje', UNKNOWN: 'Ni podatka', NOT_PERFORMED: 'Ni preverjeno' };
  const list = document.getElementById('local-checks'); list.replaceChildren();
  response.localChecks.checks.forEach(check => {
    const item = document.createElement('li'); const status = document.createElement('strong');
    status.textContent = labels[check.status] || check.status;
    status.className = check.status === 'PASS' ? 'check-pass' : check.status === 'FAIL' ? 'check-fail' : '';
    item.append(status, document.createTextNode(check.message)); list.append(item);
  });
  document.getElementById('ai-summary').textContent = response.advice.summary;
  showList('ai-observations', response.advice.observations);
  showList('ai-actions', response.advice.recommendedActions);
  showList('ai-missing', response.advice.missingInformation);
  document.getElementById('ai-metadata').textContent = `Azure OpenAI · deployment: ${response.metadata.deployment} · model: ${response.metadata.model} · ${new Date(response.metadata.generatedAt).toLocaleString('sl-SI')}`;
  document.getElementById('advice-json').textContent = JSON.stringify(response, null, 2);
  results.hidden = false;
}
form.addEventListener('input', clearResult);
document.getElementById('add-item').addEventListener('click', () => { addItem(); clearResult(); });
document.getElementById('sample').addEventListener('click', () => {
  const example = { invoiceNumber: 'DEMO-2026-001', supplierName: 'Izmišljeni dobavitelj d.o.o.',
    supplierCountry: 'SI', supplierVatId: '', issueDate: '2026-11-03', dueDate: '2026-11-17',
    currency: 'EUR', netTotal: '250.00', taxTotal: '55.00', grossTotal: '305.00',
    question: 'Ali se navedene vsote ujemajo s postavkami in katere informacije moram še preveriti?',
    notes: 'Sintetični primer za prikaz delovanja. Davčna stopnja in dobavitelj nista preverjena.' };
  for (const [name, value] of Object.entries(example)) form.elements.namedItem(name).value = value;
  form.elements.namedItem('consent').checked = false;
  items.replaceChildren();
  addItem({ description: 'Izmišljena storitev A', quantity: '2', unitNetPrice: '100.00' });
  addItem({ description: 'Izmišljena storitev B', quantity: '1', unitNetPrice: '50.00' });
  clearResult();
  statusText.className = ''; statusText.textContent = 'Vstavljen je sintetični primer. Podatki še niso bili poslani v Azure.';
});
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (busy || !configured || !form.reportValidity()) return;
  const request = readRequest(); // Read before disabling inputs: disabled fields are absent from FormData.
  if (!request.consent) return;
  busy = true; fieldset.disabled = true; submitButton.disabled = true;
  lastResult = undefined; results.hidden = true;
  statusText.className = ''; statusText.textContent = 'Pošiljam zahtevo v Azure OpenAI. Počakajte …';
  const cancellation = new AbortController();
  const timeout = setTimeout(() => cancellation.abort(), 130000);
  try {
    const response = await fetch('/api/v1/invoices/advice', { method: 'POST',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal: cancellation.signal });
    const body = await response.json();
    if (!response.ok) throw new Error(body.message || 'AI razlage ni bilo mogoče pridobiti.');
    renderResponse(body); lastResult = body;
    statusText.textContent = 'Prejeto: lokalna preverjanja in strukturirana AI razlaga. Pred odločitvijo preverite ugotovitve.';
  } catch (error) {
    results.hidden = true; statusText.className = 'error';
    statusText.textContent = error.name === 'AbortError'
      ? 'Čakanje je poteklo. Azure je zahtevo morda že obdelal; pred novim klicem preverite stanje. Samodejne ponovitve ni.'
      : error.message || 'Zahteva ni uspela. Poskusite znova ročno.';
  } finally {
    clearTimeout(timeout); busy = false; fieldset.disabled = false; submitButton.disabled = !configured;
    form.elements.namedItem('consent').checked = false;
    updateItemButtons();
  }
});
document.getElementById('download-json').addEventListener('click', () => {
  if (!lastResult) return;
  const url = URL.createObjectURL(new Blob([JSON.stringify(lastResult, null, 2)], { type: 'application/json;charset=utf-8' }));
  const link = document.createElement('a'); link.href = url; link.download = 'ivas-invoice-advice.json'; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
});
async function loadConfiguration() {
  try {
    const response = await fetch('/api/v1/invoices/advice/config', { cache: 'no-store' });
    if (!response.ok) throw new Error();
    configured = (await response.json()).configured === true;
    configuration.textContent = configured
      ? 'Azure OpenAI je konfiguriran. Podatki se pošljejo šele ob potrditvi in oddaji obrazca.'
      : 'AI pomoč še ni nastavljena. Skrbnik mora ustvariti model deployment ter nastaviti endpoint, ključ, ime deploymenta in IVAS_AI_ENABLED=true. Navodila: docs/Naloga4_LLM.md. Obrazec lahko medtem pripravite.';
    configuration.className = configured ? 'notice' : 'notice error';
  } catch (_) {
    configuration.className = 'notice error';
    configuration.textContent = 'Konfiguracije AI ni bilo mogoče preveriti. Osvežite stran ali preverite zagon aplikacije.';
  }
  submitButton.disabled = !configured;
}
addItem();
loadConfiguration(); // Local configuration check only; never an LLM inference request.
