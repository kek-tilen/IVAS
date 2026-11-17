# Naloga 6 – stage 1: evidenca računov

IVAS ima trajno evidenco računov s postavkami, opombo in uporabniškim stanjem
obravnave. Na `/invoices.html` lahko dodate račun, pregledujete strani seznama,
odprete podrobnosti in spremenite opombo ali stanje. Povezava do podrobnosti
vsebuje ID računa in deluje tudi po ponovnem nalaganju strani.

Ta faza ne izvaja preverjanja shranjenega računa. Obstoječi gRPC obrazec,
statistika in izbirna AI pomoč ostanejo dostopni prek navigacije. Povezovanje
evidence s preverjanji ter integraciji VIES in francoskega registra sledijo
v naslednjih fazah. Uvoz UBL in urejanje vsebine že shranjenega računa še nista
vključena.

## Zagon

Potrebujete JDK 25 in PostgreSQL. Za priloženi Docker Compose mora delovati
Docker Desktop z Linux vsebniki. V korenu repozitorija:

```powershell
docker compose --env-file .env.example up -d --wait db
.\mvnw.cmd spring-boot:run
```

Odprite **http://localhost:8080/invoices.html**. Statistika ostane na `/`.
Privzeti lokalni podatki so baza `ivas`, uporabnik `ivas`, geslo `ivas-local`,
vrata `5432`. PostgreSQL je izpostavljen samo na `127.0.0.1`.

Če uporabljate obstoječa lokalna orodja tega repozitorija:

```powershell
$env:JAVA_HOME = (Resolve-Path '.local/jdk-25').Path
$env:MAVEN_USER_HOME = (Resolve-Path '.local/maven').Path
docker compose --env-file .env.example up -d --wait db
.\mvnw.cmd '-Dmaven.repo.local=.local/repository' spring-boot:run
```

Zaustavitev aplikacije: `Ctrl+C`. Zaustavitev baze z ohranitvijo podatkov:

```powershell
docker compose stop db
```

Podatki so v Docker volumnu `ivas_postgres-data`, zato preživijo ponovni zagon
aplikacije in vsebnika. Za ohranitev podatkov ne odstranjujte tega volumna.
Ob zagonu aplikacije Flyway izvede manjkajoče migracije, Hibernate pa shemo
preveri (`ddl-auto=validate`); ne ustvarja je ponovno.

## Lastna PostgreSQL baza

Compose ni obvezen. Za že pripravljeno bazo nastavite procesne spremenljivke:

```powershell
$env:IVAS_DB_URL = 'jdbc:postgresql://localhost:5432/ivas'
$env:IVAS_DB_USER = 'ivas'
$env:IVAS_DB_PASSWORD = '<geslo vaše razvojne baze>'
.\mvnw.cmd spring-boot:run
```

Spring datoteke `.env` ne prebere samodejno. Compose jo prebere, vendar s tem
spremenljivke niso nastavljene v procesu Java. Ob lastnih nastavitvah uskladite
ime baze, uporabnika, geslo in vrata na obeh straneh. `IVAS_DB_NAME` in
`IVAS_DB_PORT` sta nastavitvi Compose; povezavo Spring določa `IVAS_DB_URL`.
Geslo `POSTGRES_PASSWORD` velja ob prvi inicializaciji praznega volumna;
sprememba nastavitve ne spremeni gesla že obstoječega uporabnika PostgreSQL.

Če baza ni dosegljiva, se aplikacija ne zažene z začasno nadomestno shrambo.
Preverite `docker compose ps` in nastavitve povezave. Azure ni potreben.

## Podatkovni model

| Tabela | Vsebina |
|---|---|
| `invoice_record` | Identifikator UUID, različica za sočasne spremembe, dobavitelj, datumi, valuta, vsote, opomba, stanje obravnave in časovna žiga |
| `invoice_line` | Urejene postavke računa; količina in neto cena enote |
| `verification_run` | Osnova prihodnje zgodovine: račun, čas, stanje izvedbe, ocena in posnetek preverjanega računa |
| `verification_check` | Osnova prihodnjih rezultatov: koda, status, vir, URL, čas pridobitve in pojasnilo |

Migracija je `src/main/resources/db/migration/V1__create_invoice_storage.sql`.
Zadnji dve tabeli sta pripravljeni za naslednje faze; stage 1 vanju ne zapisuje
izmišljenih preverjanj. Denarni podatki so Java `BigDecimal` in SQL `NUMERIC`,
registrski identifikatorji pa nizi, zato začetne ničle ostanejo ohranjene.

Številka računa ni globalno enolična: različni dobavitelji lahko uporabijo isto
številko. Račun v evidenci enolično določa UUID.

Uporabniška stanja so `NEW` (Nov), `IN_REVIEW` (V obravnavi) in `RESOLVED`
(Zaključen). Ne pomenijo avtomatske ocene ali odobritve plačila.

## HTTP API

Vsi odgovori evidence imajo `Cache-Control: no-store`.

| Metoda in pot | Obnašanje |
|---|---|
| `POST /api/v1/invoices` | Shrani račun s postavkami; vrne 201, podrobnosti in glavo `Location` |
| `GET /api/v1/invoices?page=0&size=20` | Povzetki od najnovejšega vnosa; velikost strani 1–100 |
| `GET /api/v1/invoices/{id}` | Podrobnosti računa, postavke in podatki obravnave |
| `PATCH /api/v1/invoices/{id}` | Spremeni opombo in stanje obravnave s preverjanjem različice |

Primer shranjevanja (isti model `InvoiceData` kot pri lokalnih preverjanjih):

```json
{
  "invoice": {
    "invoiceNumber": "DEMO-2026-001",
    "supplierName": "Sintetični dobavitelj d.o.o.",
    "supplierCountry": "SI",
    "supplierVatId": null,
    "supplierRegistryId": null,
    "issueDate": "2026-11-17",
    "dueDate": "2026-12-17",
    "currency": "EUR",
    "items": [{"description": "Podpora", "quantity": "1", "unitNetPrice": "100.00"}],
    "netTotal": "100.00",
    "taxTotal": "22.00",
    "grossTotal": "122.00"
  },
  "note": "Sintetični primer.",
  "handlingStatus": "NEW"
}
```

Pri shranjevanju se preverijo obvezna polja, dolžine, datumi, država, valuta,
število postavk ter meje in natančnost pozitivnih števil. Neskladje vsot je
vsebina prihodnjega preverjanja in samo po sebi ne prepreči shranjevanja.
Največja opomba ima 2000 znakov. POST in PATCH imata omejitev telesa 128 KiB,
tudi pri prenosu brez `Content-Length`. Zapis računa in postavk je transakcijski.

Primer spremembe obravnave; `version` vzemite iz zadnjega GET ali PATCH odgovora:

```json
{"version": 0, "note": "Poslano v pregled.", "handlingStatus": "IN_REVIEW"}
```

Pri PATCH je različica obvezna, navesti pa je treba vsaj opombo ali stanje.
Izpuščena oziroma `null` vrednost ohrani obstoječi podatek; prazen niz opombo
izbriše. Ob uspehu različica naraste. Ob zastareli različici strežnik vrne **409**;
vmesnik ohrani uporabnikovo vneseno opombo. Ponovno nalaganje podrobnosti
pridobi trenutno stanje, nato lahko uporabnik uskladi spremembe. Napačen vnos
vrne 400, neobstoječ račun 404, preveliko telo 413 in zaznana nedosegljivost
shrambe med obdelavo 503.

## Preverjanje

Osnovni testi ne potrebujejo Dockerja ali zunanjih storitev. H2 je odvisnost
samo za teste; testi izvedejo isto migracijo in preverjanje sheme:

```powershell
.\mvnw.cmd test
```

Novi testi preverijo zapis in branje, vrstni red postavk, decimalne zneske,
začetne ničle, strani seznama, veljavnost vnosa, meje zahtevkov, sočasne spremembe
in ohranitev podatkov ob zaprtju ter ponovnem odprtju Spring aplikacije.

Za preizkus na dejanskem PostgreSQL uporabite **ločeno testno bazo**:

```powershell
docker compose --env-file .env.example exec -T db createdb -U ivas ivas_test
$env:IVAS_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/ivas_test'
$env:IVAS_TEST_DB_USER = 'ivas'
$env:IVAS_TEST_DB_PASSWORD = 'ivas-local'
.\mvnw.cmd '-Dtest=InvoiceStorageWebTests,InvoicePersistenceRestartTests' '-Dinvoices.browser.tests=true' test
```

`createdb` izvedite samo enkrat. Brskalniški test uporablja nameščeni Microsoft
Edge prek Playwright. Preveri vnos, ponovno nalaganje, urejanje, konflikt med
zavihkoma, varno izpisovanje besedila in prikaz pri 1440 ter 390 px. Posnetki
so v ignorirani mapi `target/invoices-preview/`.

Po preizkusu lahko odstranite preglasitve za naslednji osnovni zagon testov:

```powershell
Remove-Item Env:IVAS_TEST_DB_URL, Env:IVAS_TEST_DB_USER, Env:IVAS_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
```

## Demonstracija

1. Odprite `/invoices.html`, izberite »Nov račun« in vstavite sintetični primer.
2. Shranite račun. Odprejo se njegove podrobnosti.
3. Spremenite opombo in stanje v »V obravnavi«, nato shranite obravnavo.
4. Ponovno naložite stran in preverite ohranjeno opombo ter stanje.
5. Ponovno zaženite aplikacijo z isto bazo in odprite isto povezavo do računa.
6. Za konflikt odprite isti račun v dveh zavihkih. Po shranjevanju v prvem
   poskusite shraniti v drugem: prepis je zavrnjen, vnesena opomba ostane.

## Dejanski rezultati – 17. 11. 2026

- PostgreSQL 17.12: migracija na prazni bazi, preverjanje JPA sheme in vseh
  **9 novih testov uspešnih**, vključno z brskalnikom in ponovnim odprtjem
  aplikacijskega konteksta.
- Končna gradnja `package` na testni H2 z vklopljenimi spletnimi testi evidence,
  gRPC obrazca, statistike in AI obrazca: **67 testov, 56 izvedenih, 11 izbirnih
  izpuščenih, brez napak; BUILD SUCCESS**. Izpuščena so izbirna preverjanja ARES
  in živega vira SURS, ki niso predmet sprememb te faze.
- Izdelani JAR je bil zagnan z glavno PostgreSQL bazo. Po shranjevanju računa in
  obravnave je bil proces Java zaprt in ponovno zagnan. Račun, postavka,
  registrski identifikator z začetnimi ničlami, opomba in stanje so ostali
  ohranjeni. Sintetični preizkusni račun je bil nato odstranjen.
- Vizualno pregledani posnetki namiznih podrobnosti ter mobilnih podrobnosti,
  seznama in vnosa. Brskalniški testi niso zaznali napak JavaScript ali
  vodoravnega prelivanja celotne strani pri 390 px.

Ukaz končne gradnje (JDK 25):

```powershell
.\mvnw.cmd '-Dinvoices.browser.tests=true' '-Dverification.browser.tests=true' '-Dstatistics.browser.tests=true' '-Dadvice.browser.tests=true' package
```
