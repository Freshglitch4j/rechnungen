# Abfahrt

Kleine Android-App für das Samsung Galaxy A55: zeigt die eigenen regelmäßigen
Bus- und Zugabfahrten **mit Echtzeit** – als laufende Benachrichtigung auf dem
Sperrbildschirm (ab One UI 8 als Live-Benachrichtigung in der Now Bar), als
Widget und als schwarze Standanzeige. Die Anzeige erscheint von selbst rund
30 Minuten vor der ersten Abfahrt und verschwindet, sobald alle Fahrten weg
sind. Geöffnet werden muss die App im Alltag nicht.

```
Abfahrt · Heimfahrt · Stand 16:39
Bus 760  16:46 → 16:51  +5 Min
in 11 Min · Kundl Gemeindesaal

REX 2  16:59 → 16:59  pünktlich
in 19 Min · Kundl Bahnhof
```

## Datenquelle

Echtzeitdaten kommen ausschließlich von der offiziellen REST-Schnittstelle der
**Verkehrsauskunft Österreich (VAO)**, Dienst `departureBoard`. Sie bündelt die
Echtzeitmeldungen aller Verkehrsunternehmen (VVT-Busse und ÖBB-Züge).

- Zugang: kostenloser persönlicher Schlüssel „VAO Start“ –
  <https://www.verkehrsauskunft.at/start>. Vertrag ausfüllen, unterschreiben, an
  `start@verkehrsauskunft.at` senden; Freischaltung laut VAO binnen längstens
  15 Werktagen. Der Schlüssel wird in der App eingetragen und liegt nur auf dem
  Gerät – nie im Repository.
- Limit: 100 Abfragen pro Tag. Ein normaler Montag (Hin- und Heimfahrt) braucht
  rund 60; die App zählt mit und streckt den Takt, bevor das Limit erreicht ist.
- Offene Echtzeit-Feeds (GTFS-RT/SIRI) gibt es für Tirol nicht. Die internen
  Schnittstellen der ÖBB-/VVT-Apps werden bewusst **nicht** verwendet.

Ohne Schlüssel zeigt die App nur die Fahrplanzeit und kennzeichnet das
(„nur Fahrplan“). Eine Fahrt gilt nur dann als „pünktlich“, wenn die
Schnittstelle für genau diese Fahrt eine Echtzeitprognose liefert. Sonst steht
dort „keine Echtzeitdaten“, „Echtzeit veraltet“ (mit letztem bekannten Stand)
oder „keine Verbindung“.

## Voreingestellte Verbindungen

Haltestellen, Linien und Zeiten sind gegen die offiziellen Soll-Fahrplandaten
(VVT und ÖBB, Fahrplanjahr 2026) geprüft:

| Richtung | Tage | Plan | Linie | Haltestelle | ID |
|---|---|---|---|---|---|
| Hin | Mo–Fr | 06:33 | Bus 760 | Kastengstatt Moosegg | `at:47:65761` |
| Hin | Mo–Fr | 06:42 | Bus 4055 | Kirchbichl Grattenbrücke | `at:47:65724` |
| Heim | Mo–Do | 16:46 | Bus 760 | Kundl Gemeindesaal | `at:47:64459` |
| Heim | Mo–Do | 16:59 | REX 2 | Kundl Bahnhof | `at:47:1172` |
| Heim | Fr | 12:29 | REX 2 | Kundl Bahnhof | `at:47:1172` |

Alle Richtung Wörgl Hbf (`at:47:1170`).

## Einrichtung am Handy

1. `Abfahrt-1.3.0.apk` installieren (Installation aus dieser Quelle erlauben).
2. App einmal öffnen, Benachrichtigungen erlauben und unter *Einrichtung* die
   mit „!“ markierten Punkte beheben (v. a. Akku: „Nicht eingeschränkt“).
3. Galaxy-Einstellungen:
   - *Benachrichtigungen → Sperrbildschirm-Benachrichtigungen*: „Inhalt
     anzeigen“, Stil „Karten“.
   - *Sperrbildschirm und AOD → Now Bar*: Live-Benachrichtigungen einschalten.
   - Für „Handy liegt vor mir“: Always On Display einschalten oder in der App
     die Standanzeige nutzen (auf die Benachrichtigung tippen – öffnet sich
     ohne Entsperren; wahlweise automatisch zu Beginn).
4. Optional das Widget „Abfahrt“ auf den Startbildschirm legen.
5. Mit **Demo (5 Min)** prüfen, wie die Anzeige auf Sperrbildschirm und Now Bar
   aussieht (erfundene Werte, keine Abfragen).
6. Sobald der VAO-Schlüssel da ist: unter *Echtzeitdaten* eintragen und
   **Verbindungen testen**.

## Funktionsweise

- **Zeitsteuerung:** exakter Wecker zum Fensterbeginn (löst auch im
  Stromsparmodus aus) → Vordergrunddienst mit laufender Benachrichtigung →
  Weckzeiten im Minutentakt für den Countdown. Zwischen den Schritten schläft
  das Gerät. Nach Neustart, App-Update und Zeitumstellung werden die Wecker neu
  gestellt.
- **Abfragetakt** je Fahrt: > 25 Min vor Abfahrt alle 10 Min, bis 16 Min alle
  5 Min, bis 8 Min alle 3 Min, bis 3 Min alle 2 Min, danach jede Minute – bezogen
  auf die *erwartete* Abfahrt. Netzfehler werden nach 30 s wiederholt.
- **Ende:** 2 Minuten nach der erwarteten Abfahrt der letzten Fahrt (ohne
  Echtzeit 6 Minuten nach Plan), spätestens 90 Minuten nach Plan.
- **Wochenenden und österreichische Feiertage** lösen nichts aus;
  „Heute ausblenden“ (oder Wegwischen) gilt für das laufende Fenster.
- **Schnittstellenversion:** VAO schaltet alte Versionen ab; die App fragt die
  aktuelle Versionsliste ab und wechselt selbst.

## Bauen und Testen

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Benötigt JDK 21 und das Android-SDK (Plattform 36). Die Signatur liegt nicht im
Repository: `keystore.properties` und `abfahrt-release.jks` im Projektordner
ablegen, sonst wird mit dem Debug-Schlüssel signiert (ein Update über eine
bestehende Installation braucht denselben Schlüssel).

Die Tests (Robolectric) decken Zeitsteuerung, Feiertage, Fahrtzuordnung,
Abfragetakt und Kontingent, das Auswerten der VAO-Antworten nach der
öffentlichen OpenAPI-Beschreibung, Fehlerbilder (offline, Schlüssel abgelehnt,
Limit erreicht, veraltete Daten), Dienststart per Wecker, Neustart und einen
vollständig simulierten Tag ab. `ScreenshotTest` zeichnet die Oberflächen nach
`app/build/screenshots`.

Nicht automatisch prüfbar und daher am Gerät zu kontrollieren: das Aussehen
auf dem Samsung-Sperrbildschirm bzw. in der Now Bar und die Antworten der
echten VAO-Schnittstelle (dafür „Demo“ und „Verbindungen testen“).

Datenquelle im Betrieb: Verkehrsauskunft Österreich VAO GmbH (VAO Start).
