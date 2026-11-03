# Naloga 4 – strukturirana AI pomoč pri pregledu računa

## Kaj je implementirano

Naloga je vključena v glavni projekt IVAS. Naloga 1 (LabTrack) ostaja ločena in
nespremenjena. Obstoječa ARES razčlenjevalnik in statistika SURS sta ohranjena.

Na naslovu **http://localhost:8080/invoice-advice.html** je obrazec za konkreten
račun: identifikacija dobavitelja, datuma, valuta, postavke, navedene vsote,
uporabnikovo vprašanje in dodatni kontekst. Povezava je tudi na začetni strani.

Obrazec pošlje zahtevo šele, ko uporabnik potrdi pošiljanje in izrecno odda obrazec.
Odprtje strani, vstavljanje primera, sprememba polj in preverjanje konfiguracije
ne sprožijo LLM klica. Po vsakem oddanem zahtevku je treba soglasje ponovno označiti.
Ni samodejnih ponovitev, drugih ponudnikov ali preklopa modela.

### Izpolnitev zahtev naloge

| Zahteva | Izvedba |
|---|---|
| Podatki od ponudnika LLM | Strežniški klic konfiguriranega Azure OpenAI deploymenta |
| Dinamičen prompt | Vprašanje + podatki konkretnega računa + lokalna preverjanja, serializirani kot JSON kontekst |
| Strukturiran odgovor | `response_format: json_schema` z `strict: true` in brez dodatnih lastnosti |
| Razred za prejete podatke | Jackson preslika preverjeni odgovor v `InvoiceAdviceResponse.Advice` in končni `InvoiceAdviceResponse` |
| Programska uporaba JSON | Ločen prikaz povzetka, opažanj, nadaljnjih korakov in manjkajočih podatkov; ogled/prenos JSON |

Kopiranje besedila v prompt ni edini ukrep: uporabljena je shema strukturiranega
izhoda, nato se na strežniku preverijo tip, obvezna polja, dolžine in velikost
seznamov. Neveljaven, podvojen, zavrnjen ali odrezan izhod se ne prikaže kot
uspešna razlaga. Pravilna JSON struktura ne jamči pravilnosti vsebine modela.

## Pomembno: kaj morate še narediti v Azure

Ob implementaciji je lastnik navedel resource z imenom **IVASai**, projekt
**IVAS-LLM** in regijo **West Europe**. Model še NI bil deployan. Noben dejanski
Azure LLM zahtevek med razvojem ni bil izveden in ključ ni bil vnesen.

1. Odprite Microsoft Foundry in svoj projekt `IVAS-LLM` / ustrezni resource `IVASai`.
2. V katalogu izberite OpenAI **gpt-5-mini** in ustvarite deployment. Predlagano ime
   deploymenta je `ivas-invoice-gpt5-mini`; uporabite lahko drugo ime.
3. Preverite razpoložljivost modela, dovoljeno vrsto deploymenta in kvoto na svojem
   računu/regiji. West Europe sam po sebi ne zagotavlja razpoložljivosti vsake vrste
   deploymenta. Če model ni ponujen, ne zamenjajte tiho modela ali regije.
4. Počakajte, da je deployment uspešno ustvarjen. Zabeležite **točno ime deploymenta**.
   API uporablja to ime; ime Foundry projekta ali resourcea ni ime deploymenta.
5. V razdelku za ključe/endpoint ali v API vzorcu deploymenta poiščite **Azure OpenAI
   endpoint** in resource API ključ. Kopirajte dejanski naslov, ne sestavljajte ga
   samo iz prikaznega imena `IVASai`.
6. Za to implementacijo uporabite **API key authentication**. Microsoft Entra ID
   prijava ni implementirana. Če so ključi na vašem resourceu onemogočeni, je potrebna
   posebej dogovorjena dopolnitev prijave; ne izklapljajte organizacijskih pravil.
7. Nastavite okoljske spremenljivke v terminalu, kjer boste zagnali Spring Boot
   (spodaj), nato zaženite aplikacijo in izvedite ročni preizkus.

Podprti obliki nastavitve endpointa sta korenski Azure OpenAI naslov ali njegov v1
base URL, npr. `https://<dejanski-resource>.openai.azure.com/` oziroma
`https://<dejanski-resource>.openai.azure.com/openai/v1/`. Podprte so tudi ustrezne
Azure domene `.services.ai.azure.com` in `.cognitiveservices.azure.com`.

**Ne uporabite** Foundry projektnega URL-ja `/api/projects/IVAS-LLM`, URL-ja portala,
celotnega URL-ja `/chat/completions` ali URL-ja z `api-version` query parametrom.
Odjemalec sam oblikuje `/openai/v1/chat/completions` in uporablja API v1.

Lokacija resourcea ni nujno lokacija obdelave pri globalnem deploymentu. Če je za
vaš namen zahtevana obdelava znotraj EU podatkovne cone, preverite ustrezen Data
Zone deployment in njegovo razpoložljivost; za šolsko demonstracijo uporabljajte
sintetične podatke. Podrobnosti določa Microsoftova dokumentacija deploymentov.

## Konfiguracija in zagon na Windows

Potrebujete JDK 25, Maven wrapper in pripravljene Maven odvisnosti. Iz korena IVAS
odprite PowerShell. Za že pripravljeni lokalni JDK/predpomnilnik tega projekta:

```powershell
$env:JAVA_HOME = (Resolve-Path .local/jdk-25).Path
$env:MAVEN_USER_HOME = (Resolve-Path .local/maven).Path
```

Nato nastavite vrednosti za svoj **dejanski** deployment:

```powershell
$env:IVAS_AI_ENABLED = 'true'
$env:AZURE_OPENAI_ENDPOINT = 'https://<dejanski-resource>.openai.azure.com/'
$env:AZURE_OPENAI_DEPLOYMENT = 'ivas-invoice-gpt5-mini'
$ivasApiKeySecure = Read-Host 'Azure OpenAI resource API key' -AsSecureString
$env:AZURE_OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $ivasApiKeySecure).Password
Remove-Variable ivasApiKeySecure
$env:IVAS_AI_TIMEOUT_SECONDS = '60'
$env:IVAS_AI_MAX_COMPLETION_TOKENS = '4096'
.\mvnw.cmd '-Dmaven.repo.local=.local/repository' spring-boot:run
```

Vrednosti v `<...>` nadomestite s pravim naslovom iz Azure. Ključ se vnese skrito
in ne kot ukaz, da ne ostane v zgodovini terminala. Ne vnašajte ga v JavaScript,
`application.properties`, dokumentacijo ali Git. Ključ je med izvajanjem običajno
besedilo v procesnem okolju, ne trajna shramba skrivnosti. Po koncu dela ga lahko
odstranite iz terminala z `Remove-Item Env:AZURE_OPENAI_API_KEY`.

Če uporabljate lasten pravilno nastavljen JDK 25 in običajni Maven predpomnilnik,
izpustite nastavitvi `.local` ter parameter `-Dmaven.repo.local`.

`.env.example` je samo seznam nastavitev. **Spring tega projekta datoteke `.env`
ne bere samodejno.** Uporabite okolje terminala ali nastavitve zagona v IDE. IDE
mora dobiti iste spremenljivke. Po spremembi nastavitev aplikacijo ponovno zaženite.

| Nastavitev | Privzeto / pomen |
|---|---|
| `IVAS_AI_ENABLED` | `false`; opt-in na ravni strežnika |
| `AZURE_OPENAI_ENDPOINT` | prazno; dejanski Azure OpenAI resource/v1 endpoint |
| `AZURE_OPENAI_API_KEY` | prazno; resource API ključ, samo strežnik |
| `AZURE_OPENAI_DEPLOYMENT` | prazno; točno ime ustvarjenega deploymenta |
| `IVAS_AI_TIMEOUT_SECONDS` | 60; dovoljeno 1–120, povezava največ 10 sekund |
| `IVAS_AI_MAX_COMPLETION_TOKENS` | 4096; dovoljeno 256–8192, vključuje reasoning in izhod |

Za `gpt-5-mini` je uporabljen `reasoning_effort: low`. Nastavitve so namenjene temu
modelu; zamenjava z modelom z drugačnimi parametri zahteva preverjanje združljivosti.
`store: false`, brez streaminga, brez orodij, brez model routerja, brez fallbacka.
Omejitev tokenov ni denarna kvota: dejanski strošek je odvisen od Azure deploymenta,
vhodnih/izhodnih tokenov in veljavnega cenika. Azure proračunska opozorila nastavite
posebej; aplikacija ne obljublja skupnega denarnega limita.

Brez konfiguracije se aplikacija normalno zažene. Statistična stran ostane na `/`.
Na obrazcu je gumb AI onemogočen in prikazana so navodila za manjkajočo konfiguracijo.
"Konfigurirano" pomeni prisotno in oblikovno veljavno konfiguracijo, ne že uspešnega
preizkusa ključa, kvote ali deploymenta.

## Ročni preizkus po deploymentu

1. Odprite `http://localhost:8080/invoice-advice.html` in preverite obvestilo o konfiguraciji.
2. Kliknite **Vstavi sintetični primer**. To še ne pošlje zahtevka v Azure.
3. Preberite/vnesite vprašanje, označite soglasje in kliknite **Pridobi AI razlago**.
4. Pričakujte lokalna preverjanja vsot ter ločeno AI razlago. Pri primeru je vsota
   postavk 250,00 EUR, navedeni davek 55,00 EUR, bruto 305,00 EUR. To ni preverjanje
   davčne stopnje ali resničnega dobavitelja.
5. Odprite **Strukturirani rezultat JSON**; preverite polja `metadata`, `localChecks`
   in `advice`. JSON lahko prenesete. V modelovi razlagi pričakujte štiri polja:
   `summary`, `observations`, `recommendedActions`, `missingInformation`.
6. Spremenite bruto na 300,00 EUR in zastavite vprašanje o razliki. Stari rezultat
   se skrije. Ponovno potrdite pošiljanje in oddajte novo zahtevo: lokalni
   `GROSS_TOTAL` mora biti `FAIL`, pričakovani bruto pa 305,00 EUR.
7. Spremenite vprašanje/kontekst ter ponovno oddajte. Vsebina modelove razlage se
   lahko razlikuje, struktura mora ostati enaka. Dejanski prompt vključuje nove
   podatke, ne statičnega vprašanja.
8. V DevTools opazujte POST na lokalni `/api/v1/invoices/advice`; ključ ni del
   brskalniškega zahtevka ali odgovora. Spreminjanje polj ne sproži novega POST.
9. Za izklop nastavite `IVAS_AI_ENABLED=false` in ponovno zaženite aplikacijo.

Ponovna oddaja je nov, lahko plačljiv klic. Ob napaki ali timeoutu ni samodejne
ponovitve. Timeout ne zagotavlja, da Azure zahtevka ni že obdelal ali obračunal.

## Meje finančne obravnave

- To je pomoč pri pregledu, ne uradna davčna validacija, dokaz odsotnosti prevare
  ali odobritev plačila. Končno odločitev sprejme uporabnik.
- Lokalna matematika uporablja `BigDecimal`: vsaka neto postavka (količina × cena)
  se zaokroži `HALF_UP` na minor unit izbrane ISO valute, nato se postavke seštejejo.
  Neto + vneseni davek se primerja z vnesenim bruto. Davčna stopnja se ne ugiba.
- Preveri se odnos datumov; manjkajoča zapadlost ostane `UNKNOWN`.
- ARES, VIES in francoski register se v tem toku ne kličejo. Statusi so izrecno
  `NOT_PERFORMED`, tudi če je uporabnik vnesel DDV identifikator.
- UBL import, dobropisi, popusti/dodatni stroški na ravni dokumenta, pravila DDV in
  popoln tok preverjanja iz opisa naloge 0 niso implementirani s to nalogo.
- Največ 30 postavk; omejene dolžine besedil, zneski in natančnost. Odgovor Azure je
  omejen na 64 KiB ter preverjeno strukturo. Istočasno je dovoljen en AI zahtevek
  na aplikacijsko instanco; druga zahteva dobi 429 brez klica Azure.
- Modelovo besedilo se prikazuje kot besedilo, ne kot HTML. Vhod se označi kot
  nezaupan kontekst, ne kot sistemska navodila. Prompt zmanjšuje možnost
  manipulacije, ne zagotavlja nezmotljivosti LLM.
- Podatki se ne zapisujejo v aplikacijsko bazo, `localStorage` ali običajne loge.
  Prenos JSON je izrecna uporabniška možnost. Azure ima lastna pravila obdelave.
- Lokalna demonstracija nima uporabniške avtentikacije ali trajne uporabniške
  kvote. Pred javno objavo je treba zaščititi endpoint za plačljive klice; samo
  polje `consent` ni dokaz identitete ali zaščita pred zlorabo javnega API-ja.

## API in obravnava napak

- `GET /api/v1/invoices/advice/config`: samo lokalno stanje konfiguracije, brez ključa.
- `POST /api/v1/invoices/advice`: tipiziran JSON z računa, `question`, `notes` in
  `consent: true`. Vsi zneski se preslikajo v `BigDecimal`; obrazec jih pošlje kot
  decimalne nize, da ne uporablja binarnega računanja v JavaScriptu. Izračunani
  vsoti v odgovoru sta prav tako decimalna niza; Java model zanju uporablja BigDecimal,
  JSON prikaz/prenos pa ne izgublja natančnosti zneskov v JavaScriptu.
- Uspeh 200: `InvoiceAdviceResponse`, `Cache-Control: no-store`.
- 400: neveljaven vhod ali manjkajoče soglasje; 422: zavrnitev modela; 429: omejitev
  ponudnika ali že tekoč zahtevek; 503: manjkajoča konfiguracija/prijava/prekinitev;
  502: povezava, ponudnik ali neveljaven odgovor; 504: časovna omejitev.
- Sporočila ne vračajo ključa, surovih odgovorov napak Azure ali sledov sklada.

## Koda in preverjanje

- `advice/InvoiceAdviceRequest.java`: razred vhodnega računa in postavk.
- `advice/InvoiceAdviceResponse.java`: razredi lokalnih preverjanj in AI odgovora.
- `advice/InvoiceAdviceService.java`: validacija, izračuni, dinamični kontekst, omejitev sočasnosti.
- `advice/AzureOpenAiClient.java`: JDK HttpClient, en Azure v1 zahtevek, omejitve in JSON preslikava.
- `advice/InvoiceAdviceController.java`: REST API in varna sporočila napak.
- `src/main/resources/advice/`: sistemska navodila in JSON Schema.
- `src/main/resources/static/invoice-advice.html`, `js/invoice-advice.js`, `css/invoice-advice.css`: spletni obrazec.

Java datoteke so pod `src/main/java/um/feri/ipt/tids/project/`. Uporabljeni so
obstoječi JDK HttpClient, Spring MVC in Jackson 3 (`tools.jackson`), brez novega
SDK-ja, spremembe Maven odvisnosti ali novega samostojnega projekta.

Testi in gradnja (brez pravih LLM zahtevkov):

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.local/repository' --offline test
.\mvnw.cmd '-Dmaven.repo.local=.local/repository' --offline '-Dadvice.browser.tests=true' '-Dstatistics.browser.tests=true' package
```

`--offline` uporabite le, če so odvisnosti že v lokalnem Maven predpomnilniku.
Brskalniški testi potrebujejo nameščeni Edge in obstoječi Playwright driver. Ob
že pripravljenem driverju lahko nastavite `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`.
Testi ARES in živi SURS testi ostanejo izrecno opt-in; zgornja ukaza jih ne vklopita.

Lokalni testni Azure nadomestek preveri dejansko HTTP pogodbo, posredovanje prompta,
shemo, deserializacijo, neveljaven JSON, zavrnitev, napačen ključ, 429, timeout,
prevelik odgovor, odsotnost ponovitev in sočasnost. Brskalniški test preveri
izrecno soglasje, prikaz odgovora, varno besedilo, spremembo podatkov, obnovo po
napaki in širini 1440 ter 390 px. Ločen tok preveri privzeto izklopljeni AI.
Posnetki lokalnega nadomestka so v ignorirani `target/advice-preview/`; niso dokaz
uspešnega odgovora pravega modela.

### Rezultati preverjanja — 2026-11-03

Končna lokalna gradnja `package` je uspešna na Windows, JDK 25.0.4.1, Maven 3.10.0.
Izvedeno je **39 uspešnih testov od 42**, brez napak. Trije izpuščeni testi so
namerno opt-in živi ARES testi (2) in živi prenos SURS (1). Med 39 izvedenimi je
18 testov nove AI funkcionalnosti, vključno z dvema brskalniškima tokovoma, ter
regresijski testi ARES in statistike. Izvedeni so lokalni Edge testi pri 1440 in
390 px; to je preizkus odzivnega prikaza v brskalniku, ne fizičnega telefona.

Dejanski ukaz končne gradnje (po nastavitvi lokalnega JDK/predpomnilnika):

```powershell
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = '1'
.\mvnw.cmd '-Dmaven.repo.local=.local/repository' --offline '-Dadvice.browser.tests=true' '-Dstatistics.browser.tests=true' '-Dares.browser.tests=true' '-Dares.browser.channel=msedge' '-Dlogging.level.root=ERROR' '-Dspring.main.banner-mode=off' package
```

Dodatno preverjeno: JavaScript sintaksa z `node --check`, pakirani razredi in
resursi v izvršljivem JAR, pravilen `Start-Class: ProjectApplication`, pregled
desktop/mobile posnetkov ter Git diff/whitespace pred commiti. Maven odvisnosti
in obstoječa koda nalog 1–3 so nespremenjeni, razen povezave iz začetne strani.

V predhodnem krogu je padel nov test fokusa tipkovnice: po zadnjem aktivnem
kontrolniku je napačno zahteval fokus zunaj `body`. Popravljen je na konkreten
prehod Tab iz številke računa v naziv dobavitelja; končni krog je uspešen.
Obstoječe opozorilo Mockito o dinamično naloženem agentu na JDK 25 ostaja opozorilo,
ne napaka gradnje; testnih odvisnosti zaradi te naloge nismo spreminjali.

**NEPREVERJENO:** dejanski Azure klic, točnost odgovora pravega gpt-5-mini,
veljavnost ključa/deploymenta, kvota in izbrana Azure vrsta deploymenta. Model in
prijavni podatki še niso nastavljeni. Lastnik po zgornjih korakih izvede živi
preizkus s sintetičnim računom. Med razvojem ni bilo plačljivih LLM zahtevkov.

## Uradni viri

- [Azure strukturirani izhodi in JSON Schema](https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/structured-outputs)
- [Azure OpenAI Chat API v1](https://learn.microsoft.com/en-us/rest/api/microsoft-foundry/azureopenai/chat)
- [Ime deploymenta in endpoint](https://learn.microsoft.com/en-us/azure/foundry-classic/openai/how-to/switching-endpoints)
- [Ustvarjanje Foundry resourcea in deploymenta](https://learn.microsoft.com/en-us/azure/foundry/tutorials/quickstart-create-foundry-resources)
- [Vrste deploymentov in lokacija obdelave](https://learn.microsoft.com/en-us/azure/foundry/foundry-models/concepts/deployment-types)
