# Rechnungen

Eine kleine PWA, um Rechnungen und Belege für den Hausbau zu erfassen:
Beleg fotografieren oder als PDF importieren, von Claude auslesen lassen,
kurz prüfen, speichern. Export für Excel und Datensicherung inklusive.

**Belege und Fotos bleiben auf dem Gerät.** Es gibt kein Backend und keine
Anmeldung. Gespeichert wird in der IndexedDB des Browsers. Nach außen geht
nur das Foto bzw. PDF eines Belegs zum Auslesen an die Claude-API
(Anthropic), direkt vom Handy aus. Wer die App deinstalliert oder die
Website-Daten löscht, verliert die Belege – vorher also immer eine
Sicherung speichern.

Läuft über GitHub Pages aus `main`: https://freshglitch4j.github.io/rechnungen/

## Einrichtung

1. In der [Claude Console](https://platform.claude.com) unter
   *Settings → Workspaces* einen eigenen Workspace „Rechnungen“ anlegen und
   dort unter *Spend limits* ein monatliches Ausgabenlimit setzen.
2. Unter *Settings → API keys* einen persönlichen Schlüssel nur für diesen
   Workspace anlegen. **Wichtig:** Der Schlüssel muss genau einem Workspace
   zugeordnet sein – die App sendet keinen `anthropic-workspace-id`-Header.
3. In der App unter *Einstellungen → API-Schlüssel* eintragen. Der Schlüssel
   wird nur im `localStorage` dieses Geräts gespeichert, steht nie im Code
   und ist auch in Sicherungsdateien nicht enthalten.

## Funktionen

- **Erfassen** – Foto mit der Rückkamera (auch mehrseitig, Seiten drehbar)
  oder aus der Galerie, alternativ PDF-Import
- **Auslesen** – Lieferant, Land, Rechnungsnummer, Datum, Fälligkeit,
  Steuersätze (mehrere, auch ausländische) mit Netto und USt, Brutto,
  Zahlbetrag, bezahlt ja/nein, Kategorie, Positionen und ein Hinweis
  (z. B. Skonto, Haftrücklass, Abschlagsrechnung). Unsichere Felder markiert
  Claude, sie werden gelb hinterlegt.
- **Prüfen** – alle Felder editierbar, Plausibilitätsprüfung
  (Netto + USt = Brutto und USt passend zum Satz), Warnung bei Duplikaten
  (gleiche Rechnungsnummer und gleicher Lieferant), Vollbild mit Zoomen
- **Bezahlen** – Empfänger, IBAN, BIC, Zahlungsreferenz und Skonto werden
  mit ausgelesen. „Bezahlen“ erzeugt einen „Zahlen mit Code“-QR-Code
  (EPC-QR), der als Bild gespeichert und in George unter *QR-Code scannen →
  aus Ordner* eingelesen wird; dazu Kopier-Knöpfe und „George öffnen“. Mit
  Skonto bis zur Frist automatisch der niedrigere Betrag. IBAN mit
  Prüfziffernkontrolle und Warnung, wenn ein Lieferant plötzlich eine andere
  IBAN verwendet als früher.
- **Offline** – ohne Internet erfasste Belege werden gespeichert und
  automatisch ausgelesen, sobald wieder eine Verbindung besteht
- **Belege** – Liste nach Monaten mit Suche (auch in Positionen und Beträgen)
  und Filtern nach offen, zu prüfen und Kategorie
- **Übersicht** – Summe, offene Beträge, Summen je Kategorie und Monat,
  offene Rechnungen nach Fälligkeit
- **Einstellungen** – API-Schlüssel, Modell, Kategorien mit Stichworten für
  die Zuordnung, Hell/Dunkel, Export für Excel (Belege oder Positionen,
  wahlweise für einen Zeitraum), Sicherung als ZIP speichern und laden

Zahlen durchgehend im österreichischen Format, auch im Excel-Export:
Semikolon als Trenner, Komma als Dezimaltrennzeichen, UTF-8 mit BOM.

## Modell

Voreingestellt ist **Claude Sonnet 5.5** (`claude-sonnet-5-5`), mit
`effort: medium` und strukturierter Ausgabe (`output_config.format` mit
JSON-Schema), damit die Antwort immer gültiges JSON ist. In den Einstellungen
umschaltbar auf Opus 5.5 (gründlicher, etwa doppelte Kosten) oder Haiku 4.5
(billiger, schwächer bei kleiner Schrift). Einzelne Belege lassen sich auf der
Beleg-Seite mit einem anderen Modell neu auslesen.

Die Kosten je Beleg werden aus den `usage`-Angaben der API berechnet und in
den Einstellungen aufsummiert (Preise in `MODELS` in `app.js`).

## Technik

Vanilla JavaScript, kein Framework, **kein Build-Schritt**. Die Dateien im
Repository sind genau das, was ausgeliefert wird.

| Datei | Zweck |
|---|---|
| `index.html` | Gerüst, Tableiste, Content-Security-Policy |
| `boot.js` | setzt Hell/Dunkel vor dem ersten Rendern |
| `app.js` | gesamte Anwendung (Router, Datenbank, Auslesen, Ansichten, Export, Sicherung) |
| `app.css` | Design-System, Farbvariablen für Hell und Dunkel |
| `sw.js` | Service Worker, macht die App offline nutzbar |
| `manifest.webmanifest` | Installierbarkeit, Icons, Farben |
| `inter.woff2` | Schrift, lokal eingebunden |
| `lib/pdfjs/` | pdf.js 6.3.289 (Legacy-Build, Apache 2.0) für die PDF-Vorschau |
| `lib/qrcode.js` | qrcode-generator 2.0.4 (MIT) für den Bezahl-QR-Code, wird erst beim Bezahlen geladen |
| `icon-*.png` | App-Icons inklusive maskable und Apple-Touch |

Hash-Router:

```
#/                          Belege
#/uebersicht                Übersicht
#/neu                       neuer Beleg
#/beleg/<id>                Beleg prüfen / bearbeiten
#/einstellungen             Einstellungen
#/einstellungen/kategorien  Kategorien
```

## Datenmodell

IndexedDB `rechnungen`, zwei Speicher:

```js
belege:  { id, created, updated, status: "warten"|"pruefen"|"ok", art: "foto"|"pdf",
           dateien: [dateiId], thumb /* kleines JPEG als data-URL */,
           lieferant, land, nr, datum, faellig, kategorie /* Kategorie-ID */,
           bezahlt, bezahltAm, gezahlt, waehrung, steuer: [{ satz, netto, ust }], netto, ust, brutto,
           zahlbetrag, empfaenger, iban, bic, referenz, skontoProz, skontoBis, skontoBetrag,
           positionen: [{ text, menge, betrag }], notiz,
           unsicher: [feld], modell, ausgelesen, fehler, netzfehler, kosten }
dateien: { id, beleg, idx, type, name, blob }   // Fotos als JPEG, PDFs im Original
```

`localStorage`:

- `rechnungen.v1` – Einstellungen `{ theme, model, cats: [{ id, name, hint, c }], lastBackup, cost }`
- `rechnungen.key` – API-Schlüssel (nur auf dem Gerät)

Die Zahlungsfelder kamen mit Version 1.1 dazu; ältere Datensätze bekommen sie
beim Laden über `fillDefaults()`.

Status: `warten` = noch nicht ausgelesen, `pruefen` = ausgelesen, aber noch
nicht bestätigt, `ok` = geprüft und gespeichert. Summen verwenden bei
bezahlten Belegen den gezahlten Betrag (z. B. mit Skonto), sonst den
Zahlbetrag, sonst Brutto – so zählen Abschlagsrechnungen, die in der
Schlussrechnung abgezogen werden, nicht doppelt.

Sicherung: ZIP ohne Kompression mit `daten.json` (Belege, Kategorien) und
den Originaldateien unter `dateien/`. Beim Laden wird alles in einer
einzigen Transaktion geschrieben – ganz oder gar nicht.

## Neue Version veröffentlichen

**Bei jeder Änderung beide Stellen hochzählen:**

1. `APP_VERSION` in `app.js`
2. `CACHE` in `sw.js`

Sonst liefert der Service Worker weiter die alte Fassung aus dem Cache.
Nach dem Push nach `main` dauert GitHub Pages ein bis zwei Minuten. Die
installierte App lädt die neue Version beim nächsten Seitenwechsel – aber
nie, solange ungespeicherte Eingaben offen sind oder gerade ausgelesen wird.

## Entwicklung und Test

```bash
python3 -m http.server 8099
```

Getestet wird mit Playwright im Zuschnitt des Zielgeräts (Samsung Galaxy A55,
Chrome): Viewport 412×915, `isMobile`, `hasTouch`, Sprache `de-AT`. Die
Claude-API wird dabei mit `context.route('https://api.anthropic.com/**')`
simuliert – so braucht der Test keinen Schlüssel und kostet nichts.

## Fallstricke

- **API-Schlüssel.** Niemals in Code, Tests oder Commits. Die App liest ihn
  ausschließlich aus `localStorage`.
- **pdf.js.** Es wird der *Legacy*-Build verwendet: Der normale Build von
  pdf.js 6 setzt `Map.prototype.getOrInsertComputed` voraus und rendert in
  vielen aktuellen Browsern nichts. Aufräumen geht über
  `doc.loadingTask.destroy()` – `doc.destroy()` gibt es in Version 6 nicht mehr.
- **`hidden`-Attribut.** Klassen mit `display` überschreiben es; deshalb
  steht `[hidden]{display:none!important}` im CSS.
- **Content-Security-Policy.** Verbindungen nur zu sich selbst und zu
  `api.anthropic.com`. Wer eine neue Adresse braucht, muss sie in
  `index.html` ergänzen. `wasm-unsafe-eval` ist für die Bilddecoder von
  pdf.js nötig.
- **Formular-Ereignisse.** Die Eingaben auf der Beleg-Seite werden einmalig
  am `#view` registriert (`onFormInput` usw.), nicht bei jedem Rendern –
  sonst vervielfachen sie sich.
- **Bezahl-QR-Code.** Aufbau nach EPC069-12, Version `002` (BIC optional),
  Zeichensatz UTF-8, Betrag mit Punkt (`EUR1455.00`). Eine Referenz im
  Format `RF…` (ISO 11649) kommt ins strukturierte Feld, alles andere als
  Verwendungszweck; ohne Angabe „Rechnung <Nr.>“. Geprüft wird im Test mit
  einem unabhängigen QR-Decoder (jsQR).
- **George öffnen.** Chrome startet aus Webseiten nur Apps, die dafür einen
  Einsprung (BROWSABLE) anbieten; der normale Startbildschirm von George
  gehört nicht dazu, einen dokumentierten Deep Link gibt es nicht. Die
  Intent-Adresse mit `package=at.erstebank.george` landet deshalb im Play
  Store, dort öffnet „Öffnen“ die App. Zusätzlich gibt es „Teilen“ (Web
  Share mit der PNG-Datei), damit das QR-Bild direkt an George übergeben
  werden kann, falls George im Teilen-Menü erscheint.
- **Farbe der Statusleiste.** Installiert unter Android gilt nur
  `theme_color` aus dem Manifest (eine Farbe für beide Modi), im Browsertab
  die beiden `<meta name="theme-color">`.

## Ideen für später

Erinnerung vor Ablauf der Skontofrist · Kontoauszug (CSV) importieren und
Zahlungen automatisch zuordnen · Zuordnung zu Gewerken bzw.
Auftragnehmern mit Auftragssumme und Restbetrag · Budget je Kategorie ·
mehrere Belege auf einmal importieren · Teilen direkt aus der
E-Mail-App (Share Target)
