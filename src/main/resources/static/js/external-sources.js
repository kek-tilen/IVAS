'use strict';
(() => {
  const $ = id => document.getElementById(id);
  function paragraph(target, text, className = '') {
    const p = document.createElement('p'); p.textContent = text; if (className) p.className = className; target.append(p);
  }
  function metadata(target, info) {
    paragraph(target, 'Vir: ' + ({'FR_RECHERCHE_ENTREPRISES / REST_JSON':'API Recherche d’Entreprises','VIES / SOAP_XML':'VIES'})[info.source]);
    const p = document.createElement('p'), link = document.createElement('a');
    // Source URLs are emitted by our fixed-endpoint clients; still restrict link schemes.
    try { const url = new URL(info.sourceUrl); if (url.protocol !== 'https:') throw new Error();
      link.href = info.source === 'VIES / SOAP_XML' ? 'https://ec.europa.eu/taxation_customs/vies/' : url.href; link.textContent = 'Naslov vira'; link.target = '_blank'; link.rel = 'noopener noreferrer'; p.append(link); target.append(p);
    } catch { /* A missing link never prevents displaying the result. */ }
    paragraph(target, 'Pridobljeno: ' + new Date(info.fetchedAt).toLocaleString('sl-SI'), 'hint');
  }
  function france(target, result) {
    paragraph(target, ({FOUND:'Registrski zapis je pridobljen',PARTIAL:'Podatki so nepopolni',NOT_FOUND:'Ni javno dostopnega zapisa'})[result.status], 'source-outcome');
    paragraph(target,result.message);
    if (result.company) {
      const c = result.company;
      paragraph(target,'Naziv: ' + (c.name || 'Ni podatka'));
      paragraph(target,'SIREN: ' + c.siren);
      if (c.siret) paragraph(target,(result.requestedId.length === 9 ? 'SIRET sedeža: ' : 'SIRET: ') + c.siret);
      paragraph(target,'Stanje podjetja: ' + (({A:'Aktivno',C:'Prenehano'})[c.companyStatus] || 'Ni podatka'));
      if (result.requestedId.length === 14) paragraph(target,'Stanje poslovalnice: ' + (({A:'Aktivna',F:'Zaprta'})[c.establishmentStatus] || 'Ni podatka'));
    }
    metadata(target,result.metadata);
  }
  function vies(target,result) {
    paragraph(target,result.status === 'VALID' ? 'DDV številka je veljavna v VIES' : 'VIES ne potrjuje veljavnosti DDV številke', 'source-outcome');
    paragraph(target,result.message);
    paragraph(target,'Preverjena številka: ' + result.countryCode + ' ' + result.vatNumber);
    paragraph(target,'Datum preverjanja pri viru: ' + result.requestDate);
    if (result.name) paragraph(target,'Naziv pri viru: ' + result.name);
    if (result.address) paragraph(target,'Naslov pri viru: ' + result.address);
    metadata(target,result.metadata);
  }
  for (const [name,render] of [['france',france],['vies',vies]]) {
    const form=$(name+'-source-form'), fields=$(name+'-source-fields'), output=$(name+'-source-result'), status=$(name+'-source-status'), cancel=$(name+'-source-cancel');
    let controller = null;
    form.addEventListener('input',() => { if (!controller) { output.hidden=true; output.replaceChildren(); status.textContent=''; status.className='source-status'; } });
    cancel.addEventListener('click',() => controller?.abort());
    form.addEventListener('submit',async event => {
      event.preventDefault(); if (controller || !form.reportValidity()) return;
      const payload=Object.fromEntries(new FormData(form).entries());
      if (name==='vies') { payload.countryCode=payload.countryCode.trim().toUpperCase(); payload.vatNumber=payload.vatNumber.trim().toUpperCase(); }
      controller=new AbortController(); fields.disabled=true; cancel.hidden=false; output.hidden=true; output.replaceChildren();
      status.className='source-status'; status.textContent='Pridobivam odgovor zunanjega vira …';
      try {
        const response=await fetch('/api/v1/sources/'+name,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload),cache:'no-store',signal:controller.signal});
        const result=await response.json();
        if (!response.ok) {
          let message=result.message || `Poizvedba ni uspela (HTTP ${response.status}).`;
          if (result.retryAfterSeconds) message += ` Ponovni poskus čez najmanj ${result.retryAfterSeconds} s.`;
          if (result.providerCode) message += ` Oznaka vira: ${result.providerCode}.`;
          throw new Error(message);
        }
        render(output,result); output.hidden=false; status.textContent='Odgovor vira je pridobljen.';
      } catch (error) {
        output.hidden=true; output.replaceChildren(); status.className='source-status error';
        status.textContent=error.name==='AbortError' ? 'Čakanje je prekinjeno. Rezultat ni potrjen.'
          : error.message || 'Povezava z virom ni uspela. Rezultat ni potrjen.';
      } finally { controller=null; fields.disabled=false; cancel.hidden=true; }
    });
  }
})();
