# Changelog

## 0.1.3

- **Inventar** wie im Kreativ-Inventar (Web): Reiter Bausteine, Werkzeuge & Waffen, Ruestung, Essen & Traenke, Sonstiges, Alle, Eigene Items und GUI; Anzeige Standard / Server-Pack / Beides mit Markierungen fuer "vom Pack geaendert" und "eigene Aenderung"
- **Rechtsklick-Menue** auf jedem Bild: Textur bearbeiten, Kopieren als neues Item, Faehigkeiten zuweisen, Zuruecksetzen auf Standard
- **Standard-Texturen aendern**: eigene Aenderungen an Item-, Block- und GUI-Bildern ersetzen die Minecraft-Textur fuer alle Spieler (plugins/TexturePeak/overrides/, Routen override/<art>/<name>)
- **GUI-Texturen**: Inventar und Container, Hotbar/Herzen/Hunger, Menues und Knoepfe; Pixel-Editor fuer jede Bildgroesse (auch nicht quadratisch und Animationen)
- Der Download der Standard-Bilder holt jetzt auch die GUI-Bilder; aeltere Downloads werden dazu aufgefordert, sie nachzuladen

## 0.1.0 (in Arbeit)
- Eingebautes Modul von PeakCore: Eintraege (Gegenstand + Amboss-Name -> Textur) mit Archiv, Pruefung der Texturen, Item-Modell `texturepeak:<id>`
- Faehigkeiten je Eintrag: Effekte, Werte (Attribute), Partikel; getragen oder gehalten
- Web-Seite mit Pixel-Editor, Duplizieren, Archiv; `/texturepeak list|archive|add|remove|restore|apply|check|ability|reload`

- Web: Standard-Texturen per Vorschaubild waehlen (Download der Bilder von Mojang per Knopf), als Kopie anlegen oder im Pixel-Editor aendern.
