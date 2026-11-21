# Naloga 6 – stage 2: francoski REST vir in VIES SOAP

IVAS ima odjemalca za API Recherche d’Entreprises in VIES. Na obstoječi strani
`/invoice-verification.html#external-sources` sta samostojna obrazca za
pridobitev francoskega podjetja in preverjanje DDV številke. Podatki se
pridobijo ob vsaki izrecni oddaji; odpiranje strani ne kliče teh dveh virov.

Odjemalca vračata podatke neodvisno od gRPC transporta. Povezovanje vseh
preverjanj s shranjenim računom, skupnim poročilom in zgodovino sledi v stage 3.
Stage 2 ne spreminja sheme PostgreSQL in ne zapisuje rezultatov v evidenco.

## Zagon

JDK 25 in PostgreSQL kot pri stage 1. V korenu projekta:

```powershell
docker compose --env-file .env.example up -d --wait db
.\mvnw.cmd spring-boot:run
```

Odprite **http://localhost:8080/invoice-verification.html#external-sources**.
Azure in API ključi za ta vira niso potrebni. Za lokalni JDK/Maven glejte
[navodila stage 1](Naloga6_Stage1_Shramba.md#zagon).

V zgornjem gRPC obrazcu ostaneta lokalni pregled in ARES. Nova francoska in
VIES poizvedba sta spodaj in njuna rezultata še ne spreminjata tega poročila.

## Zunanja vira in pogodbi

| Vir | Tehnologija in operacija | Vhod |
|---|---|---|
| API Recherche d’Entreprises | REST GET `/search` z odgovorom JSON | Izrecni SIREN ali SIRET |
| VIES | SOAP 1.1 `checkVat`, XML, `SOAPAction: ""` | Državna oznaka in DDV številka brez oznake |

Uradne pogodbe:

- [Francoski OpenAPI](https://recherche-entreprises.api.gouv.fr/openapi.json)
- [Opis in omejitve francoskega vira](https://www.data.gouv.fr/dataservices/api-recherche-dentreprises)
- [VIES WSDL](https://ec.europa.eu/taxation_customs/vies/checkVatService.wsdl)
- [VIES testni WSDL](https://ec.europa.eu/taxation_customs/vies/checkVatTestService.wsdl)

Produkcijska naslova sta določena v strežniških odjemalcih; uporabnik prek
zahtevka ne more izbrati poljubnega zunanjega URL-ja. SOAP je izveden z JDK
HTTP/XML orodji; dodatna SOAP odvisnost ali generiranje razredov nista potrebna.

## Francoski register

```http
POST /api/v1/sources/france
Content-Type: application/json

{"registryId":"356000000"}
```

SIREN vsebuje 9, SIRET pa 14 števk. Identifikator ostane niz, tudi če se začne
z ničlo. Oblikovna kontrola ne pomeni registrske veljavnosti identifikatorja.
Zunanji zahtevek uporablja `q=<id>`, `minimal=true`,
`include=siege,matching_etablissements` in `per_page=1`.

Odgovor vsebuje `status`, `requestedId`, `company`, `message` in `metadata`.
V podjetju so SIREN, razpoložljivi SIRET, naziv, stanje podjetja ter stanje
poslovalnice. Pri SIREN je prikazani SIRET oznaka sedeža; pri SIRET mora biti
vrnjena točno zahtevana poslovalnica.

- `FOUND`: točen identifikator ter potrebni podatki so potrjeni v odgovoru vira.
- `PARTIAL`: identifikator se ujema, vendar manjka naziv ali znano stanje.
- `NOT_FOUND`: vir je izrecno vrnil prazen rezultat. To pomeni odsotnost javno
  dostopnega zapisa, ne dokaza, da podjetje ne obstaja.

Iskanje po imenu ni uporabljeno. Če se zahtevani SIRET razlikuje od sedeža,
odjemalec pregleda `matching_etablissements`. Sam podatek o matičnem podjetju
ne potrdi poslovalnice. Napačen identifikator v odgovoru, dvoumni rezultati,
napačni tipi, podvojeni ključi ali poškodovan JSON povzročijo napako vira;
aplikacija ne izbere prvega približnega zadetka.

Stanje podjetja `A` pomeni aktivno, `C` prenehano; za poslovalnico `A` pomeni
aktivno in `F` zaprto. To je registrski podatek in ne ocena celotnega računa.
Vir ne vsebuje vseh nejavnih registrskih podatkov. Uporablja omejevanje klicev;
ob HTTP 429 IVAS posreduje razpoložljiv varen `Retry-After` in ne ponavlja klica.

## VIES

```http
POST /api/v1/sources/vies
Content-Type: application/json

{"countryCode":"FR","vatNumber":"39356000000"}
```

Oznaka države in preostanek DDV številke se pošljeta ločeno. Državna oznaka se
ne odstranjuje samodejno iz številke. Odjemalec pretvori črke v velike, za Grčijo
sprejme `GR` in ga pretvori v VIES oznako `EL`, podpira tudi `XI`. `GB` ni
nadomestilo za `XI`. Številka mora ustrezati WSDL obliki: 2–12 znakov iz nabora
črk, števk ter `+`, `*` in `.`; notranji presledki niso dovoljeni.

Uspešno izvedena poizvedba vrne `status` (`VALID` ali `INVALID`), `countryCode`,
`vatNumber`, `requestDate`, razpoložljiva `name` in `address`, `message` ter
`metadata`. Datum preverjanja pri VIES je ločen od časa, ko IVAS prejme odgovor.
Prazen, manjkajoč ali izrecno `nil` naziv/naslov ostane `null`.

Odjemalec preveri SOAP imenske prostore, obvezna polja, datum in dovoljene
vrednosti XML boolean. Zahteva točno ujemanje države in številke v odgovoru.
DTD, zunanji XML entiteti, vključevanje zunanjih shem in XInclude so onemogočeni.
Podvojena ali z napačnim imenskim prostorom opremljena obvezna polja niso
sprejeta kot uspešno preverjanje.

SOAP `Fault` se obravnava tudi pri HTTP 200. Napake `MS_UNAVAILABLE`,
`SERVICE_UNAVAILABLE`, `TIMEOUT`, omejitve sočasnih zahtev in blokade niso
rezultat `INVALID`. `INVALID_INPUT` pomeni zavrnjen vhod, ne potrditve
neveljavnosti številke. Izvirna poljubna besedila napak se ne izpisujejo.

Veljavnost številke v VIES ne potrjuje vseh podatkov dobavitelja ali davčne
skladnosti računa in ne pomeni odobritve plačila.

## Ažurnost in napake

Vsak veljaven POST sproži novo pridobivanje. Aplikacijskega predpomnilnika in
samodejnih ponovitev ni. Odgovori imajo `Cache-Control: no-store`. Uspešen
rezultat vsebuje ime vira, dejanski naslov zahtevka in `fetchedAt`; pri napaki
je naveden čas poskusa `attemptedAt`, brez izmišljene potrditve podatkov.

| HTTP | Koda oziroma pomen |
|---|---|
| 400 | Napačen vhod ali `VIES_INVALID_INPUT` |
| 413 | Telo vhodnega zahtevka presega 128 KiB |
| 429 | `SOURCE_RATE_LIMITED` ali lokalni `SOURCE_BUSY` |
| 502 | `SOURCE_INVALID_RESPONSE` |
| 503 | `SOURCE_UNAVAILABLE` ali prekinjena strežniška poizvedba |
| 504 | `SOURCE_TIMEOUT` |

Napaka vsebuje varno pojasnilo ter, kjer je znana, oznako SOAP napake. Ne vrne
`valid:false` ali `NOT_FOUND` kot nadomestila za izpad vira. `Retry-After`
podpira število sekund in HTTP datum; posredovana vrednost je omejena na 1–86400
sekund. Brez podatka vira se čas za ponovni poskus ne izmišlja.

Največji odgovor zunanjega vira je 256 KiB. Hkrati se izvajajo največ štirje
klici novih odjemalcev. Preusmeritve niso dovoljene. Celotni rok, ki vključuje
tudi branje odgovora po prejemu glav, je privzeto 20 sekund:

```powershell
$env:IVAS_SOURCES_TIMEOUT_SECONDS = '20' # dovoljeno 1–60
```

V obrazcih sprememba vnosa odstrani prejšnji rezultat. Napaka ali prekinitev
čakanja prav tako skrije prejšnji rezultat. Gumb »Prekini čakanje« prekine
čakanje brskalnika; že poslani zunanji klic na strežniku lahko teče do svojega
omejenega roka. Novih klicev po prekinitvi aplikacija ne ponavlja samodejno.

## Preverjanje

Ponovljivi testi uporabljajo lokalni HTTP strežnik z nadzorovanimi JSON in SOAP
odgovori. Preverijo točne identifikatorje, poslovalnico izven sedeža, delne in
prazne rezultate, sveže klice, napake vira, SOAP pogodbo, napake in varnost XML,
HTTP omejitve, sočasnost ter rok za branje telesa. Ne potrebujejo interneta ali
PostgreSQL; kot ostali osnovni testi uporabljajo testno H2.

```powershell
.\mvnw.cmd '-Dtest=ExternalSourceClientTests,ExternalSourcesWebTests' test
```

Izbirni brskalniški test uporablja nameščeni Microsoft Edge. Preveri izrecno
oddajo, varno izpisovanje besedila, čiščenje zastarelih rezultatov, napako,
ponovni ročni poskus, prekinitev čakanja ter prikaz pri 1440 in 390 px:

```powershell
.\mvnw.cmd '-Dtest=ExternalSourcesWebTests' '-Dsources.browser.tests=true' test
```

Posnetki so v ignorirani mapi `target/sources-preview/`.

Žive preizkuse vklopite ločeno:

```powershell
.\mvnw.cmd '-Dtest=ExternalSourcesLiveTests' '-Dsources.live.tests=true' test
```

Ti preizkusi pridobijo javni zapis LA POSTE po SIREN `356000000` in SIRET
`35600000000048` ter uporabijo uradno **testno** storitev VIES za sintetični
številki `100` (veljavna) in `200` (neveljavna). To ni preizkus nacionalnega
produkcijskega registra s sintetičnima številkama. Produkcijski odjemalec vedno
uporablja produkcijski VIES endpoint in vmesnik ne omogoča preklopa na testni vir.

## Demonstracija

1. Na strani preverjanja se pomaknite na »Zunanji viri«.
2. V francoskem obrazcu vnesite `356000000` in izrecno sprožite poizvedbo.
3. Preverite naziv, identifikator, stanje, vir in čas pridobitve.
4. Ponovite s SIRET `35600000000048`.
5. VIES preverite z dejanskim javnim DDV identifikatorjem: državo in preostanek
   številke vnesite ločeno. Sintetični `100` in `200` sta samo za testno storitev.
6. Spremenite vnos: prejšnji rezultat se skrije. Nova oddaja pridobi nov odgovor.

## Dejanski rezultati – 21. 11. 2026

- Ponovljivi testi novih odjemalcev in API-ja z vklopljenim brskalnikom:
  **14 testov, brez napak**. Vizualno pregledan prikaz novih razdelkov pri
  namizni in mobilni širini; besedilo iz vira se ne izvaja kot HTML.
- Živi testi: **3 testi, brez napak**. Pridobljen je bil javni francoski zapis
  po SIREN in SIRET; uradna testna VIES storitev je vrnila pričakovana pozitivni
  in negativni SOAP odgovor za sintetični številki 100 in 200.
- Končna gradnja `package` z brskalniškimi testi novih virov ter evidence,
  gRPC obrazca, statistike in AI: **84 testov, 70 izvedenih, 14 izbirnih
  izpuščenih, brez napak; BUILD SUCCESS**. Izpuščeni so ločeno vključljivi
  ARES/SURS preizkusi in trije živi testi novih virov, ki so bili izvedeni
  samostojno po zgornjem ukazu.
- Izdelani JAR se uspešno zažene z obstoječo PostgreSQL bazo. Stran in novi API
  delujeta brez testnih nadomestkov. Francoski produkcijski vir je vrnil `FOUND`.
- Produkcijski VIES klic z javnim identifikatorjem LA POSTE, pridobljenim iz
  francoskega vira, je vrnil SOAP napako **MS_MAX_CONCURRENT_REQ**. IVAS je
  pravilno vrnil HTTP 429 / `SOURCE_RATE_LIMITED` in ni izpisal rezultata
  `INVALID`. Pozitivno produkcijsko preverjanje zato ob tem preizkusu ni bilo
  potrjeno; pozitivna in negativna veja sta potrjeni z uradno testno storitvijo.

Ukaz končne gradnje (JDK 25, nameščeni Edge; živi klici so izklopljeni):

```powershell
.\mvnw.cmd '-Dsources.browser.tests=true' '-Dinvoices.browser.tests=true' '-Dverification.browser.tests=true' '-Dstatistics.browser.tests=true' '-Dadvice.browser.tests=true' package
```
