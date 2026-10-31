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