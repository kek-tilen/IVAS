# Statistika uporabe e-računov v IVAS

IVAS preverja strukturirane račune UBL 2.1. Analitična stran prikazuje razširjenost uporabe strukturiranih e-računov v Sloveniji in tako dopolnjuje tematiko projekta. Statistika je informativna in ne spreminja ocene posameznega računa ali dobavitelja.

## Vir podatkov

- OPSI: https://podatki.gov.si/dataset/surs2966321s
- SURS, tabela 2966321S: »Število podjetij po strukturi računa, poslanega v prejšnjem letu, in velikostnem razredu, Slovenija, letno«.
- Dejanska datoteka PCAXIS: https://pxweb.stat.si/SiStatData/Resources/PX/Databases/Data/2966321S.PX
- Izbor podatkov: https://pxweb.stat.si/SiStatData/pxweb/sl/Data/-/2966321S.px
- Metodologija: https://www.stat.si/statweb/File/DocSysFile/8271
- Licenca zbirke: CC BY 4.0. Vir: SURS.

Ob preverjanju 31. 10. 2026 je datoteka vsebovala leta 2021-2025, devet kazalnikov in štiri velikostne razrede (180 vrednosti). LAST-UPDATED je bil 20251204 10:30. Pri prihodnjem nalaganju se leta in velikostni razredi preberejo iz vira.

## Zagon

Potreben je JDK 25 in dostop do interneta za Maven odvisnosti ter prenos SURS. V korenu obstoječega projekta:

```powershell
.\mvnw.cmd spring-boot:run
```

Odprite http://localhost:8080/. API: `GET /api/v1/statistics/invoices`.

Če uporabljate že pripravljeni lokalni JDK in Maven predpomnilnik tega projekta:

```powershell
$env:JAVA_HOME = (Resolve-Path .local/jdk-25).Path
$env:MAVEN_USER_HOME = (Resolve-Path .local/maven).Path
.\mvnw.cmd "-Dmaven.repo.local=.local/repository" spring-boot:run
```

Gradnja in testi:

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

## Podatkovni tok in obdelava

1. Ob odprtju strani JavaScript pokliče API. Enako stori gumb »Osveži podatke«.
2. `InvoiceStatisticsService` prenese izvorno datoteko `.PX` s časovno omejitvijo povezave 10 sekund in zahtevka 25 sekund. Podatki se ne shranjujejo v aplikacijski predpomnilnik; odgovor vsebuje `Cache-Control: no-store`.
3. `PcAxisParser` prebere CODEPAGE (v tej datoteki Windows-1250), privzeti jezik, STUB, HEADING, CODES, VALUES in DATA. Vrednosti poveže z dimenzijami v vrstnem redu datoteke; zadnja dimenzija se spreminja najhitreje. Preveri število vrednosti in dimenzije. Večjezični vnosi ne prepišejo privzetih slovenskih.
4. Storitev uporabi kode STAT001 (vsa podjetja), STAT00847 (strukturirani e-računi), STAT00849 (nestrukturirani elektronski računi) in STAT00851 (papirnati računi). Izračuna deleže in spremembe.
5. API vrne metapodatke, razpoložljiva leta, velikostne razrede in opazovanja JSON. Vsako opazovanje vsebuje leto raziskovanja, leto poslanih računov, razred, štiri števila, delež in medletno spremembo.
6. Brskalnik filtrira prejete podatke po velikosti in letu ter posodobi povzetke, tabelo in grafa Chart.js. Spreminjanje filtrov ne zahteva ponovnega prenosa vira.

Delež = strukturirani e-računi / vsa podjetja istega leta in velikosti × 100. Medletna sprememba = delež tekočega leta - delež predhodnega leta, v odstotnih točkah. Za manjkajoče podatke, ničelni imenovalec ali odsotno predhodno leto je rezultat `null`, v prikazu »—«. Čas posodobitve vira je prikazan kot lokalni čas iz metapodatkov SURS; čas prenosa se v brskalniku prikaže v uporabnikovem časovnem pasu.

## Razlaga in omejitve

- Zbirka šteje podjetja, ne računov. Delež zato ne pomeni deleža vseh izdanih računov.
- Leto raziskovanja 2025 opisuje račune, poslane v letu 2024.
- Podjetje lahko uporablja več oblik računov; treh kategorij ne seštevamo v celoto in jih ne prikazujemo s tortnim grafom.
- Skupni razred »10 ali več« že vključuje podrazrede. Podjetja z manj kot desetimi zaposlenimi in samozaposlenimi niso vključena.
- Vsote lahko odstopajo zaradi zaokroževanja. Odstotki so v vmesniku prikazani na eno decimalno mesto.
- Parser je namenjen gostemu zapisu te SURS tabele. Ni splošna implementacija celotnega standarda PCAXIS (npr. ne podpira hierarhij in redkega zapisa). Sprememba strukture ali kod zahteva prilagoditev; aplikacija ob napaki ne kaže starih podatkov kot svežih.
- Če prenos ali obdelava ne uspe, API vrne HTTP 502 z razumljivim sporočilom, stran skrije rezultate in omogoči ponovni poskus.

## Mape in knjižnice

- `src/main/java/um/feri/ipt/tids/project/parser/pcaxis/`: razčlenjevanje PCAXIS.
- `src/main/java/um/feri/ipt/tids/project/statistics/`: prenos, izračuni in REST kontroler.
- `src/main/resources/static/`: HTML, CSS, JavaScript in lokalni Chart.js 4.5.1 (MIT; licenca je v `vendor/Chart.js-LICENSE.md`). Vir knjižnice: https://www.chartjs.org/.
- `src/test/java/um/feri/ipt/tids/project/statistics/`: testi parserja, izračunov, napak in HTTP prikaza.
- `src/test/resources/pcaxis/2966321S.PX`: nespremenjeni posnetek javne datoteke SURS, pridobljen 31. 10. 2026, samo za ponovljive teste. Produkcija vedno bere spletni vir.

## Preverjanje

Avtomatski testi preverijo uradna števila, pravilne dimenzije in šumnike, manjkajoče vrednosti, poškodovan DATA, citirane podpičje in narekovaje, deleže, odstotne točke, napake vira ter dejanski lokalni HTTP strežnik z JSON in statičnimi datotekami. Obstoječi testi ARES ostanejo vključeni; brskalniški in zunanji ARES testi se še naprej izvajajo samo ob izrecnem vklopu njihovih lastnosti.

Kontrolni primer za leto raziskovanja 2025, razred »10 ali več«: skupaj 9033 podjetij, strukturirane e-račune pošilja 5704, nestrukturirane 7613 in papirnate 7612. Delež strukturiranih je približno 63,1 %. Primerjati ga je treba z istim izborom v SiStat.

Za demonstracijo: odprite stran, spremenite leto in velikost podjetja, preglejte tabelo ter kliknite »Osveži podatke«. V omrežnem pregledu je viden nov klic API-ja; sprememba filtrov uporablja že naložene podatke.


### Dodatni preverjanji

```powershell
.\mvnw.cmd "-Dstatistics.browser.tests=true" "-Dstatistics.live.tests=true" test
```

`statistics.browser.tests` uporabi lokalni Microsoft Edge prek obstoječega Playwright za preverjanje filtrov, dveh grafov, osvežitve, napake HTTP 502, ponovnega poskusa in prikaza pri širini 390 px. Posnetka sta v ignorirani mapi `target/statistics-preview/`. Test uporablja dejanski lokalni HTTP strežnik in posnetek SURS, brez zunanjega podatkovnega zahtevka.

`statistics.live.tests` ločeno preveri dejanski prenos in obdelavo uradne datoteke SURS. Običajni testi ne potrebujejo povezave do SURS ali brskalnika. Preverjanje 31. 10. 2026: osnovni testi uspešni, prenos uradne datoteke uspešen, brskalniški tok in prikaz pri 1440 ter 390 px uspešna. Med preverjanjem je bilo popravljeno vodoravno prelivanje grafov na ozkih zaslonih.

Končna gradnja `package` z vklopljenima preverjanjema statistike: BUILD SUCCESS, 24 testov, 14 izvedenih, 10 izpuščenih (izbirni testi ARES), brez napak.
