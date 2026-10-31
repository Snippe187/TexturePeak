# TexturePeak - Testliste

## Phase 14 - Server (Regeln, Datenbank, Pruefung, Markierung)

Automatisch (Tests): Pruefung von Kennung/Typ/Name und PNG (quadratisch, 16-512, beschaedigt, abgeschnitten), Datenbank (anlegen, doppelt, Archiv, Wiederherstellen, Neustart, kaputte Datei), Pack-Dateien je Eintrag.

Auf dem Testserver per Konsole geprueft: `list`, `add` (gueltig, falsche Groesse, falscher Typ, doppelte Kennung), `check`, `remove`, `archive`, `restore`, `reload`; das Pack enthaelt danach 3 Dateien je Eintrag.

Von Hand im Spiel (noch NICHT geprueft):
1. Textur nach `plugins/TexturePeak/import/<kennung>.png`, `/texturepeak add <kennung> <ITEM_TYP> <name>`.
2. Passenden Gegenstand in die Hand, `/texturepeak apply <kennung>`: Er zeigt die Textur, sobald das Pack aktiv ist.
3. Gegenstand am Amboss auf den Regelnamen umbenennen: Ergebnis bekommt die Textur.
4. Ohne Pack bleibt der Gegenstand normal sichtbar.

Grenzen: Nur Inventar-Symbol (Item-Modell). Getragene Ruestung (Equipment-Texturen) folgt in Phase 15.
## Phase 15 - Faehigkeiten

Automatisch (Tests): Pruefung der Werte, Planer (getragen/gehalten, vier Teile zaehlen einmal, hoechste Effekt-Stufe gewinnt, aus/unbekannt), Speichern, alte Dateien ohne Faehigkeiten, Limit 8.

Im Spiel geprueft (Dev-Client, Eintrag `rubin`): getragener markierter Brustpanzer gab Speed (Stufe 2) und +4 Max-Leben (24); nach dem Ablegen war beides nach 5 Sekunden weg. Der Gegenstand wurde per Befehl mit der Markierung versehen (`custom_data`), nicht ueber `/texturepeak apply`.

Befehle: `/texturepeak ability <kennung> list | remove <nr> | add effect|particle|attribute <worn|held> <name> [stufe|anzahl|wert [add|multiply]]`

Nicht geprueft: Partikel sichtbar, gehaltene Faehigkeiten (Haupt-/Nebenhand), Verhalten bei Tod/Weltwechsel, `/texturepeak apply` im Spiel.

Grenzen: Effekte werden jede Sekunde erneuert und laufen 2 Sekunden nach dem Ablegen aus; Werte sind fluechtig (nicht gespeichert); nur Partikel ohne Zusatzdaten (z. B. flame, heart, kein dust).

## Phase 17: Web-Seite "Texturen"
Pixel-Editor, Eintraege, Archiv, Faehigkeiten, Duplizieren ueber /api/texturepeak (siehe PeakCore/docs/TESTS.md, Phase 16/17). Eingaben werden serverseitig mit TextureCheck und AbilityRunner.check geprueft; Fehler kommen als Meldung in der Oberflaeche an.
