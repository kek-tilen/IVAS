'use strict';
const yearSelect = document.getElementById('year');
const sizeSelect = document.getElementById('size');
const reloadButton = document.getElementById('reload');
const statusMessage = document.getElementById('status');
const numberFormat = new Intl.NumberFormat('sl-SI', { maximumFractionDigits: 0 });
const decimalFormat = new Intl.NumberFormat('sl-SI', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
let statistics;
let trendChart;
let comparisonChart;

function number(value) { return value == null ? '—' : numberFormat.format(value); }
function percent(value) { return value == null ? '—' : `${decimalFormat.format(value)} %`; }
function setText(id, value) { document.getElementById(id).textContent = value; }

function fillOptions(select, options, previousValue) {
  select.replaceChildren(...options.map(item => new Option(item.label, item.value)));
  if (options.some(item => item.value === previousValue)) select.value = previousValue;
}

async function loadStatistics() {
  const previousYear = yearSelect.value;
  const previousSize = sizeSelect.value;
  reloadButton.disabled = yearSelect.disabled = sizeSelect.disabled = true;
  document.getElementById('results').hidden = true;
  setText('source-time', '');
  statusMessage.className = '';
  statusMessage.textContent = 'Pridobivam podatke SURS …';
  try {
    const response = await fetch('/api/v1/statistics/invoices', { cache: 'no-store' });
    if (!response.ok) throw new Error('Podatkov SURS trenutno ni mogoče pridobiti ali obdelati. Poskusite znova.');
    statistics = await response.json();
    if (!statistics.years.length || !statistics.sizeClasses.length) throw new Error('Vir ne vsebuje podatkov za prikaz.');
    fillOptions(yearSelect, [...statistics.years].sort((a,b) => b-a).map(year => ({ value: String(year), label: String(year) })), previousYear);
    fillOptions(sizeSelect, statistics.sizeClasses.map(size => ({ value: size.code, label: size.label })), previousSize);
    yearSelect.disabled = sizeSelect.disabled = false;
    document.getElementById('results').hidden = false;
    renderStatistics();
    setText('source-time', `Posodobitev zbirke: ${statistics.sourceUpdatedAt.replace('T', ' ')} (čas SURS) · Prenos: ${new Date(statistics.fetchedAt).toLocaleString('sl-SI')}`);
    statusMessage.textContent = 'Podatki so pridobljeni. S filtri prilagodite prikaz.';
  } catch (error) {
    document.getElementById('results').hidden = true;
    yearSelect.disabled = sizeSelect.disabled = true;
    statusMessage.className = 'error';
    statusMessage.textContent = error.message || 'Pri nalaganju je prišlo do napake. Poskusite znova.';
  } finally {
    reloadButton.disabled = false;
  }
}

function renderStatistics() {
  const rows = statistics.observations.filter(row => row.sizeCode === sizeSelect.value).sort((a,b) => a.year-b.year);
  const selected = rows.find(row => row.year === Number(yearSelect.value));
  setText('period', `Raziskovanje ${selected.year} · računi, poslani v letu ${selected.invoiceYear}`);
  setText('total', number(selected.total));
  setText('share', percent(selected.structuredShare));
  setText('structured', `${number(selected.structured)} podjetij pošilja strukturirane e-račune`);
  setText('change', selected.changePoints == null ? '—' : `${selected.changePoints > 0 ? '+' : ''}${decimalFormat.format(selected.changePoints)} o. t.`);
  renderTable(rows);
  renderCharts(rows, selected);
}

function renderTable(rows) {
  const tableRows = rows.map(row => {
    const tr = document.createElement('tr');
    [row.year, number(row.total), number(row.structured), number(row.unstructured), number(row.paper), percent(row.structuredShare)].forEach(value => {
      const td = document.createElement('td');
      td.textContent = value;
      tr.append(td);
    });
    return tr;
  });
  document.getElementById('data-rows').replaceChildren(...tableRows);
}

function renderCharts(rows, selected) {
  if (typeof Chart === 'undefined') throw new Error('Knjižnice za grafe ni mogoče naložiti. Osvežite stran.');
  if (trendChart) trendChart.destroy();
  if (comparisonChart) comparisonChart.destroy();
  const common = { animation: false, locale: 'sl-SI', responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } } };
  trendChart = new Chart(document.getElementById('trend'), {
    type: 'line',
    data: { labels: rows.map(row => row.year), datasets: [{ label: 'Podjetja, ki pošiljajo e-račune (%)', data: rows.map(row => row.structuredShare), borderColor: '#17664f', backgroundColor: '#e8f3ed', fill: true, pointRadius: 4, spanGaps: false }] },
    options: { ...common, scales: { y: { min: 0, max: 100, ticks: { callback: value => `${value} %` } }, x: { title: { display: true, text: 'Leto raziskovanja' } } } }
  });
  comparisonChart = new Chart(document.getElementById('comparison'), {
    type: 'bar',
    data: { labels: ['Strukturirani', 'Nestrukturirani', 'Papirnati'], datasets: [{ label: 'Število podjetij', data: [selected.structured, selected.unstructured, selected.paper], backgroundColor: ['#17664f', '#759bba', '#d3a65b'], borderRadius: 5 }] },
    options: { ...common, scales: { y: { beginAtZero: true, title: { display: true, text: 'Število podjetij' } } } }
  });
}

yearSelect.addEventListener('change', renderStatistics);
sizeSelect.addEventListener('change', renderStatistics);
reloadButton.addEventListener('click', loadStatistics);
loadStatistics();
