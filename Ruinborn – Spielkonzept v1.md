# Ruinborn – Spielkonzept v1

Sep 25, 2026 · @Marvin

## 1. Überblick und Leitprinzipien

Ruinborn (Arbeitstitel) ist ein Aufbau- und Strategiespiel für Android nach dem Vorbild eines bekannten Mobile-Strategiespiels mit Zombie-Setting. Es läuft auf einem eigenen Server für bis zu 50 Freunde. Jeder Fortschritt ist ausschließlich erspielbar, Geld kauft höchstens Aussehen.

**Leitprinzipien**

1. KISS: Jede Mechanik lässt sich in einem Satz erklären. Jede Zahl steht in der Balance-Datei `balance.json`.
2. Der Server ist die einzige Wahrheit. Der Client zeigt an und sendet Befehle.
3. Kämpfe sind deterministisch. Zufall gibt es nur beim Erscheinen von Kartenobjekten und bei Item-Drops.
4. Gelegenheitsspieler werden nicht bestraft: zwei kostenlose Bauwarteschlangen, Schutz vor Dauerangriffen, Aufholbonus für Nachzügler.
5. Alle Zahlen in diesem Dokument sind Startwerte. Die Feinjustierung erfolgt per Simulation (Abschnitt 17) nur in `balance.json`, ohne Codeänderung.

**Fairness-Charta (verbindlich)**

- Alles, was Kampfkraft, Ressourcen, Zeit oder Fortschritt beeinflusst, ist nur durch Spielen erhältlich.
- Es gibt keine Premium-Währung, kein VIP-System, keine Lootboxen und kein Gacha.
- Niemals kaufbar: Warteschlangen, Beschleuniger, Ressourcen, Helden, Heldenerfahrung, Schilde, Umzüge, Truppen.
- Kaufbar wäre, falls das Spiel je öffentlich wird, ausschließlich Kosmetik ohne Spielwerte. In v1 gibt es keine Bezahlfunktion, Kosmetik wird über Erfolge freigeschaltet.
- Admins können keine Items oder Ressourcen verteilen. Einzige Ausnahme ist der Entwicklungsmodus `devMode`, der auf dem Produktivserver aus ist.

**Umfang v1**

| Bereich | Inhalt |
| --- | --- |
| Basis | 13 Gebäudetypen, 10 feste Plätze plus 10 Ressourcenplätze, 2 Bauwarteschlangen |
| Wirtschaft | Nahrung, Holz, Stahl; Lagerhaus mit Plünderschutz |
| Forschung | 15 Technologien mit je 10 Stufen |
| Militär | 3 Truppentypen mit je 5 Stufen, Lazarett, 5 Helden |
| Weltkarte | 100 × 100 Felder, 3 Zonen, Zombies, Zombie-Nester, Ressourcenfelder |
| PvP | Angriff, Aufklärung, Plündern, Schilde |
| Allianzen | Ränge, Hilfe, Verstärkung, Sammelangriffe, Allianzchat, Allianzgeschenke |
| Motivation | Tagesaufgaben, Erfolge als Tutorial, Ranglisten, Kosmetik |
| Betrieb | Einladungscode, Admin-Befehle, tägliche Backups, Update-Hinweis in der App |

**Bewusst nicht in v1:** Minispiele, Events und Saisons, Hauptstadt- und Territorialkämpfe, Privatnachrichten, Push-Benachrichtigungen (geplant für v1.1), Sound, iOS, Echtgeldzahlungen, andere Sprachen als Deutsch, Ausdauer- oder Energiesystem, Aufwertung von Truppen auf höhere Stufen.

## 2. Setting, Spielschleife und Grundregeln

Zwanzig Jahre nach dem Ausbruch einer Seuche sind die Städte Ruinen voller Zombies. Jeder Spieler führt einen Außenposten von Überlebenden und baut ihn zur Festung aus.

**Spielschleife**

1. Produktionsgebäude erzeugen Ressourcen, auch während der Spieler offline ist.
2. Ressourcen fließen in Gebäude, Forschung und Truppen.
3. Truppen unter einem Helden besiegen Zombies und sammeln auf Ressourcenfeldern. Das bringt Ressourcen, Heldenerfahrung und Items.
4. Stärkere Spieler bekämpfen höhere Zombies, greifen mit der Allianz Zombie-Nester an und kämpfen gegen andere Spieler.
5. Tagesaufgaben und Erfolge geben Ziele und belohnen mit Beschleunigern, Ressourcen und Schilden.

**Startzustand eines neuen Spielers**

| Bereich | Wert |
| --- | --- |
| Gebäude | Hauptquartier 1, Mauer 1, Lagerhaus 1, Kaserne 1, Lazarett 1, Sammelpunkt 1; Farm 1 auf R1, Sägewerk 1 auf R2, Stahlwerk 1 auf R3 |
| Ressourcen | 2.000 Nahrung, 2.000 Holz, 1.000 Stahl |
| Truppen | 200 Infanterie T1 |
| Helden | Rhea, Stufe 1 |
| Items | 5 × Beschleuniger 5 min |
| Schutz | Neulingsschutz 72 h (Regeln in Abschnitt 8) |
| Position | zufälliges freies Feld in Zone 1, euklidischer Abstand zur nächsten Basis mindestens 3 Felder; gibt es kein solches Feld mehr, genügt ein beliebiges freies Feld in Zone 1 |

**Globale Rechenregeln**

- Zeit: Server-Uhr, UTC, Millisekunden seit Epoch. Anzeige und Tagesgrenzen in der Zeitzone `Europe/Berlin` (konfigurierbar).
- Spielgeschwindigkeit `gameSpeed` (Standard 1,0, zum Testen z. B. 20): Alle Dauern werden durch `gameSpeed` geteilt, Produktions- und Sammelraten damit multipliziert. Ausgenommen sind Schutz- und Schilddauern, Tagesreset und Wartungsjobs.
- Boni gleicher Art werden addiert, nie multipliziert. Beispiel: Forschung +20 % und Held +10 % ergeben +30 %.
- Rundung: Kosten, Mengen, Beute und Kapazitäten werden abgerundet. Dauern werden auf ganze Sekunden aufgerundet, mindestens 1 s.
- L ist immer die Zielstufe. Stufe 1 bauen heißt Neubau.

Stufenwerte (Kosten, Dauer, Produktion, Kapazität) folgen immer derselben Form, mit Basiswert X₀ und Wachstum w:

```latex
X(L) = X_0 \cdot w^{L-1}
```

Jede Dauer wird mit der Summe aller passenden Tempo-Boni B und der Spielgeschwindigkeit g berechnet:

```latex
T = \left\lceil \frac{T_0}{(1 + B_{Tempo}) \cdot g} \right\rceil
```

Jeder andere Wert (Produktion, Kapazität, Traglast, Marschgröße, Angriff, Verteidigung) wird so erhöht. Kampfwerte (Angriff, Verteidigung, LP) werden dabei nicht abgerundet:

```latex
W = \left\lfloor W_0 \cdot (1 + B_{Wert}) \right\rfloor
```

## 3. Wirtschaft: Ressourcen und Gebäude

Es gibt genau drei Ressourcen: Nahrung (N), Holz (H) und Stahl (S), alle ab Spielstart. Weitere Währungen gibt es nicht.

**Produktion und Lager**

- Farm, Sägewerk und Stahlwerk produzieren kontinuierlich pro Stunde. Boni: Forschung (Abschnitt 4) und Aufholbonus (Abschnitt 8).
- Das Lagerhaus bestimmt die Kapazität K je Ressource. Die Produktion einer Ressource stoppt, sobald ihr Bestand K erreicht.
- Beute, Belohnungen und Ressourcenkisten dürfen K überschreiten.
- 25 % von K sind je Ressource vor Plünderung geschützt.

Der Server speichert je Ressource nur Bestand B₀ und Zeitpunkt t₀. Der aktuelle Bestand wird bei jedem Zugriff berechnet (Rate r pro Stunde, Δt in Stunden):

```latex
B(t) = \begin{cases} B_0 & \text{falls } B_0 \ge K \\ \min\left(K,\ B_0 + \lfloor r \cdot \Delta t \rfloor\right) & \text{sonst} \end{cases}
```

Vor jeder Änderung von Bestand, Rate oder Kapazität wird materialisiert: B₀ = B(jetzt), t₀ = jetzt. Bruchteile unter einer Einheit verfallen dabei.

**Bauplätze**

- 10 feste Plätze mit je genau einem Gebäudetyp: `HQ`, `MAUER`, `LAGER`, `KASERNE`, `FABRIK`, `SCHIESSSTAND`, `LAZARETT`, `LABOR`, `SAMMELPUNKT`, `ALLIANZ`.
- 10 Ressourcenplätze `R1` bis `R10`, frei mit Farm, Sägewerk oder Stahlwerk belegbar.
- Freischaltung der Ressourcenplätze: R1 bis R4 ab HQ 1, R5 ab HQ 3, R6 ab HQ 5, R7 ab HQ 7, R8 ab HQ 9, R9 ab HQ 11, R10 ab HQ 13.
- Ein Ressourcengebäude kann abgerissen werden, um den Typ zu wechseln. Abriss ist sofort, ohne Erstattung und nur ohne laufenden Timer auf dem Platz.

**Gebäude**

Für alle Gebäude gilt: Kostenwachstum 1,40, Zeitwachstum 1,30, Maximalstufe 20. Kein Gebäude außer dem HQ darf eine höhere Stufe als das HQ haben. HQ-Stufe L erfordert Mauer mindestens L−1.

| Gebäude | Platz | Freischaltung | Basiskosten N / H / S | Basisdauer | Effekt bei Stufe L |
| --- | --- | --- | --- | --- | --- |
| Hauptquartier | HQ | Start | 500 / 500 / 250 | 120 s | Obergrenze aller Gebäudestufen, schaltet Plätze und Helden frei |
| Mauer | MAUER | Start | 300 / 400 / 200 | 90 s | +2 % × L Verteidigung für alle Verteidiger der eigenen Basis |
| Lagerhaus | LAGER | Start | 200 / 300 / 100 | 60 s | Kapazität K = 5.000 × 1,32^(L−1) je Ressource, davon 25 % geschützt |
| Farm | R1–R10 | Start | 120 / 150 / 50 | 45 s | 100 × 1,25^(L−1) Nahrung pro Stunde |
| Sägewerk | R1–R10 | Start | 150 / 120 / 50 | 45 s | 100 × 1,25^(L−1) Holz pro Stunde |
| Stahlwerk | R1–R10 | Start | 150 / 150 / 30 | 60 s | 50 × 1,25^(L−1) Stahl pro Stunde |
| Kaserne | KASERNE | Start | 250 / 250 / 150 | 75 s | bildet Infanterie aus; bis 100 + 50 × (L−1) Einheiten pro Auftrag |
| Fahrzeugfabrik | FABRIK | HQ 2 | 250 / 250 / 150 | 75 s | bildet Fahrzeuge aus; Auftragsgröße wie Kaserne |
| Schießstand | SCHIESSSTAND | HQ 3 | 250 / 250 / 150 | 75 s | bildet Schützen aus; Auftragsgröße wie Kaserne |
| Lazarett | LAZARETT | Start | 200 / 200 / 100 | 60 s | Platz für 300 × 1,25^(L−1) verwundete Einheiten |
| Forschungslabor | LABOR | HQ 3 | 300 / 300 / 200 | 90 s | Forschungsstufe n erfordert Labor mindestens 2n−1 |
| Sammelpunkt | SAMMELPUNKT | Start | 200 / 250 / 100 | 60 s | Marschgröße 500 + 400 × (L−1) Einheiten |
| Allianzzentrum | ALLIANZ | HQ 4 | 300 / 300 / 150 | 60 s | bis 5 + ⌊L/2⌋ Allianzhilfen je Timer; Verstärkungsplatz 2.000 + 1.000 × (L−1) |

Ausbildungsgebäude schalten Truppenstufen frei: T1 ab Stufe 1, T2 ab 5, T3 ab 9, T4 ab 13, T5 ab 17. Ohne Allianzzentrum sind 3 Hilfen je Timer möglich und es kann keine Verstärkung empfangen werden.

Zur Einordnung: HQ 10 kostet 10.330 N, 10.330 H und 5.165 S bei 21 min 13 s Bauzeit. HQ 20 kostet 298.815 N, 298.815 H und 149.407 S bei 4 h 52 min 24 s. Eine Farm auf Stufe 20 liefert 6.938 Nahrung pro Stunde, ein Lagerhaus auf Stufe 20 fasst rund 977.000 je Ressource.

**Warteschlangen und Timer**

| Warteschlange | Anzahl | Hilfe möglich |
| --- | --- | --- |
| Bau (Neubau, Ausbau) | 2, für alle kostenlos | ja |
| Forschung | 1 | ja |
| Ausbildung | 1 je Ausbildungsgebäude | nein |
| Heilung | 1 | ja |

- Kosten werden beim Start abgezogen. Ein Gebäude bleibt während seines Ausbaus voll nutzbar, Produktionsgebäude produzieren bis zum Abschluss mit der alten Stufe.
- Dasselbe Gebäude kann nicht gleichzeitig in beiden Bauwarteschlangen stehen. Alle Voraussetzungen (HQ-Stufe, Mauer-Stufe, freigeschalteter Platz) werden beim Start geprüft.
- Abbrechen ist jederzeit möglich und erstattet 50 % der Kosten. Das verhindert, dass Ressourcen durch Starten und Abbrechen vor Plünderung versteckt werden.
- Beschleuniger und Allianzhilfen verkürzen die Restzeit (Abschnitte 9 und 10).

## 4. Forschung

Es gibt 15 Technologien mit je 10 Stufen, erforscht in einer einzigen Warteschlange. Es gibt keinen Technologiebaum: Die einzige Voraussetzung ist die Labor-Stufe.

- Kosten für Stufe n: 1.000 N, 1.000 H und 500 S, jeweils × 1,6^(n−1).
- Dauer für Stufe n: 300 s × 1,4^(n−1).
- Voraussetzung für Stufe n: Forschungslabor mindestens 2n−1. Stufe 10 braucht also Labor 19.
- Beispiel Stufe 10: 68.719 N, 68.719 H, 34.359 S bei 1 h 43 min 19 s.

| Nr. | Technologie | Effekt je Stufe | Effekt bei Stufe 10 |
| --- | --- | --- | --- |
| 1 | Ackerbau | +5 % Nahrungsproduktion | +50 % |
| 2 | Holzverarbeitung | +5 % Holzproduktion | +50 % |
| 3 | Metallurgie | +5 % Stahlproduktion | +50 % |
| 4 | Lagertechnik | +5 % Lagerkapazität (damit auch geschützte Menge) | +50 % |
| 5 | Bautechnik | +3 % Bautempo | +30 % |
| 6 | Forschungsmethodik | +3 % Forschungstempo | +30 % |
| 7 | Sammeltechnik | +5 % Sammeltempo, +3 % Traglast | +50 %, +30 % |
| 8 | Logistik | +3 % Marschtempo | +30 % |
| 9 | Drill | +2 % Angriff und Verteidigung der Infanterie | +20 % |
| 10 | Panzerung | +2 % Angriff und Verteidigung der Fahrzeuge | +20 % |
| 11 | Ballistik | +2 % Angriff und Verteidigung der Schützen | +20 % |
| 12 | Feldmedizin | +5 % Heiltempo, +5 % Lazarettkapazität | +50 %, +50 % |
| 13 | Ausbildungsmethodik | +3 % Ausbildungstempo | +30 % |
| 14 | Marschordnung | +3 % Marschgröße | +30 % |
| 15 | Zombiekunde | +3 % Angriff aller Einheiten gegen Zombies und Nester | +30 % |

Forschungseffekte gelten für alle eigenen Einheiten, auch in Märschen, als Verstärkung und in Sammelangriffen.

## 5. Militär: Truppen, Lazarett und Helden

Es gibt drei Truppentypen in einem Konter-Dreieck, jeweils in fünf Stufen T1 bis T5. Wer seinen Gegentyp trifft, macht 25 % mehr Schaden.

**Konter:** Infanterie schlägt Schützen, Schützen schlagen Fahrzeuge, Fahrzeuge schlagen Infanterie. Zombies und Nester haben keinen Typ, gegen sie gibt es keinen Konterbonus.

**Grundwerte T1**

| Typ | Angriff | Verteidigung | Lebenspunkte | Traglast | Tempo (s pro Feld) | Kosten N / H / S | Ausbildung pro Einheit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Infanterie | 10 | 12 | 100 | 12 | 30 | 30 / 20 / 5 | 10 s |
| Fahrzeuge | 12 | 10 | 120 | 8 | 20 | 20 / 30 / 10 | 12 s |
| Schützen | 14 | 8 | 80 | 10 | 25 | 25 / 25 / 5 | 11 s |

**Stufen-Multiplikatoren** (gelten für alle drei Typen; das Tempo bleibt gleich)

| Stufe | Angriff, Verteidigung, LP, Kosten | Traglast | Ausbildungszeit | Freischaltung (Stufe des Ausbildungsgebäudes) |
| --- | --- | --- | --- | --- |
| T1 | × 1 | × 1 | × 1 | 1 |
| T2 | × 1,6 | × 1,3 | × 1,4 | 5 |
| T3 | × 2,56 | × 1,69 | × 1,96 | 9 |
| T4 | × 4,096 | × 2,197 | × 2,744 | 13 |
| T5 | × 6,5536 | × 2,8561 | × 3,8416 | 17 |

Höhere Stufen kosten pro Kampfkraft gleich viel Ressourcen. Ihr Vorteil: weniger Ausbildungszeit, weniger Lazarettplatz und weniger Marschplatz pro Kampfkraft sowie höhere Verteidigung.

**Kampfkraft** einer Einheit = Angriff + Verteidigung + LP ÷ 10, mit Grundwerten ohne Boni. Beispiel: Infanterie T1 hat 32 Kampfkraft.

**Ausbildung**

- Ein Auftrag umfasst einen Typ, eine Stufe und eine Anzahl bis zur Auftragsgröße des Gebäudes.
- Dauer = Anzahl × Ausbildungszeit pro Einheit, mit Tempo-Boni nach der globalen Formel.
- Fertige Einheiten stehen sofort zu Hause bereit. Truppen können weder aufgewertet noch entlassen werden.

**Lazarett**

- Verwundete Einheiten belegen Lazarettplatz, kämpfen nicht und können nicht getötet oder geplündert werden.
- Heilen kostet 50 % der Ausbildungskosten und dauert 30 % der Ausbildungszeit. Der Spieler wählt eine beliebige Teilmenge.
- Wie Kampfverluste auf Lazarett und Tod verteilt werden, regelt Abschnitt 7.

**Helden**

Helden führen Märsche. Jeder Marsch außer der Aufklärung braucht genau einen Helden, und ein Held kann nur in einem Marsch sein. Die Zahl gleichzeitiger Märsche ist damit die Zahl der freien Helden, maximal 5.

| Held | Freischaltung | Bonus je Heldenstufe |
| --- | --- | --- |
| Rhea | Start | +1 % Angriff und Verteidigung der Infanterie |
| Viktor | HQ 4 | +1,5 % Angriff gegen Zombies und Nester |
| Kaya | HQ 7 | +2 % Sammeltempo, +1 % Traglast |
| Brock | HQ 10 | +1 % Angriff und Verteidigung der Fahrzeuge |
| Nova | HQ 14 | +1 % Angriff und Verteidigung der Schützen |

- Heldenstufe 1 bis 30. Erfahrung für den Aufstieg von Stufe L auf L+1: 100 × 1,25^(L−1). Überschüssige Erfahrung wird übertragen.
- Erfahrung gibt es aus Zombiekämpfen (Abschnitt 6), aus PvP-Kämpfen (2 × Summe über getötete gegnerische Einheiten × deren Stufe) und aus Heldenhandbüchern.
- Erfahrung erhalten nur die Helden der siegreichen Seite, auch der Verteidigungsheld. In Sammelangriffen erhält jeder beteiligte Held die volle Erfahrung.
- Der Heldenbonus gilt nur für die Einheiten seines Marsches.
- Verteidigungsheld: Der Held mit der höchsten Stufe, der gerade zu Hause ist, gibt seinen Bonus allen eigenen Verteidigern. Bei Gleichstand gilt die Freischalt-Reihenfolge der Tabelle.
- Helden sterben nicht, werden nicht verwundet und nicht gefangen.
- Helden werden fest freigeschaltet, es gibt kein Ziehen oder Sammeln von Helden.

**Weitere Regeln**

- Truppen haben keinen Unterhalt und keine Obergrenze. Begrenzt sind nur Auftragsgröße, Marschgröße und Lazarettplatz.
- Der Erfolgszähler für ausgebildete Einheiten zählt bei Fertigstellung, der Zähler der Tagesaufgabe „Drill“ beim Auftragsstart.
- Jeder Held der siegreichen Seite erhält die volle Erfahrung des Kampfes.
- Auf Heldenstufe 30 gibt es keine Erfahrung mehr. Heldenhandbücher lassen sich dann nicht benutzen (Fehler `VALIDATION`).
- Ein Held in einem Marsch, auch in einem wartenden, gilt nicht als zu Hause.

## 6. Weltkarte

Die Welt ist ein Raster aus 100 × 100 Feldern mit drei Zonen um das Zentrum. Je näher am Zentrum, desto stärker die Zombies und desto ergiebiger die Ressourcen.

**Grundregeln**

- Koordinaten von (0,0) bis (99,99), Zentrum (50,50). Jedes Feld trägt höchstens ein Objekt.
- Distanz ist euklidisch: d = √((x₁ − x₂)² + (y₁ − y₂)²), als Kommazahl.
- Die ganze Karte ist für alle sichtbar, es gibt keinen Nebel.
- Zone nach Distanz r zum Zentrum: Zone 3 (Kern) r ≤ 15, Zone 2 (Mitte) 15 < r ≤ 32, Zone 1 (Außen) r > 32.

**Kartenobjekte**

| Objekt | Zone 1 | Zone 2 | Zone 3 |
| --- | --- | --- | --- |
| Zombiegruppe (Stufe, Zielanzahl) | 1–7, 300 | 6–14, 200 | 13–20, 80 |
| Zombie-Nest (Stufe, Zielanzahl) | keine | 1–2, 3 | 3–5, 3 |
| Ressourcenfeld (Stufe, Zielanzahl) | 1–2, 150 | 2–3, 100 | 3–4, 40 |
| Spielerbasis | Neuspawn nur hier | per Umzug ab HQ 8 | per Umzug ab HQ 14 |

- Die Stufe eines neuen Objekts wird gleichverteilt aus dem Bereich seiner Zone gezogen. Der Typ eines Ressourcenfelds (Nahrung, Holz, Stahl) ebenfalls.
- Spawn-Job alle 5 Minuten Echtzeit: Je Zone und Objektart wird bis zur Zielanzahl aufgefüllt, auf zufälligen freien Feldern der Zone. Nach 50 erfolglosen Versuchen wird das Objekt übersprungen.
- Objekte verschwinden nur, wenn sie besiegt oder leer gesammelt sind. Ein Kampf, den der Angreifer verliert, lässt Zombies und Nester vollständig geheilt zurück.
- Ein Spieler darf Zombiegruppen bis zur Stufe (höchste eigene besiegte Stufe + 1) angreifen. Zu Beginn ist das Stufe 1.

**Zombiegruppe Stufe Z** (1 bis 20)

- Anzahl Zombies: ⌊60 × 1,25^(Z−1)⌋.
- Werte je Zombie: Angriff 9 × 1,12^(Z−1), Verteidigung 8 × 1,12^(Z−1), LP 90 × 1,12^(Z−1).
- Belohnung bei Sieg, sofort gutgeschrieben und nicht durch Traglast begrenzt: je 400 × 1,30^(Z−1) Nahrung und Holz, Stahl 40 % davon, Heldenerfahrung 50 × 1,25^(Z−1).
- Drop-Chance 25 % auf einen Beschleuniger: 5 min (Z 1–5), 15 min (Z 6–10), 60 min (Z 11–15), 3 h (Z 16–20).
- Beispiel: Stufe 1 hat 60 Zombies, Stufe 20 hat 4.163 Zombies mit je Angriff 77, Verteidigung 69 und LP 775.

**Zombie-Nest Stufe N** (1 bis 5)

- Nur per Sammelangriff einer Allianz angreifbar (Abschnitt 9).
- Anzahl Zombies: 3.000 × 2^(N−1). Werte je Zombie: Angriff 20 × 1,4^(N−1), Verteidigung 18 × 1,4^(N−1), LP 200 × 1,4^(N−1).
- Belohnung je Teilnehmer: je 20.000 × 2^(N−1) Nahrung und Holz, 8.000 × 2^(N−1) Stahl, N Beschleuniger à 60 min, Heldenerfahrung 1.000 × 2^(N−1).
- Zusätzlich erhält jedes Mitglied der siegreichen Allianz ein Allianzgeschenk (Abschnitt 9).

**Ressourcenfeld Stufe F** (1 bis 4)

- Vorrat: 30.000 × 2^(F−1) bei Nahrung und Holz, die Hälfte bei Stahl.
- Sammelrate pro Marsch: 4.000 + 1.000 × (F−1) pro Stunde bei Nahrung und Holz, die Hälfte bei Stahl. Die Rate hängt nicht von der Truppenzahl ab.
- Ein Feld wird immer nur von einem Marsch gleichzeitig besammelt.

**Umzug**

- Mit dem Item Umzugsgutschein zieht die Basis sofort auf ein frei gewähltes, leeres Feld.
- Erlaubte Zonen: Zone 1 immer, Zone 2 ab HQ 8, Zone 3 ab HQ 14.
- Ein Umzug ist nur möglich, wenn kein eigener Marsch unterwegs ist und kein fremder Marsch auf dem Weg zur Basis ist. Stationierte Verstärkungen kehren dabei zu ihren Besitzern zurück.

## 7. Märsche und Kampfsystem

Jede Aktion auf der Karte ist ein Marsch mit fester Laufzeit. Kämpfe berechnet der Server bei Ankunft sofort, rundenbasiert und ohne Zufall.

**Marscharten**

| Art | Ziel | Zusammensetzung | Ablauf |
| --- | --- | --- | --- |
| Angriff | Zombiegruppe, fremde Spielerbasis, fremder Sammelmarsch auf einem Feld | 1 Held + Truppen | Kampf, danach Rückweg |
| Sammeln | freies Ressourcenfeld | 1 Held + Truppen | sammelt bis Traglast voll oder Feld leer, danach Rückweg |
| Aufklärung | fremde Spielerbasis | kein Held, keine Truppen; kostet 500 Nahrung; Tempo 10 s pro Feld | Bericht, danach Rückweg |
| Verstärkung | Basis eines Allianzmitglieds | 1 Held + Truppen | bleibt stationiert bis zum Rückruf |
| Sammelangriff | Zombie-Nest oder fremde Spielerbasis | 1 Held + Truppen je Teilnehmer | siehe Abschnitt 9 |

- Ein Marsch enthält mindestens 1 und höchstens Marschgröße viele Einheiten. Beim Start werden sie vom Bestand zu Hause abgezogen, der Held ist belegt.
- Mitglieder der eigenen Allianz können weder angegriffen noch aufgeklärt werden.
- Aufklärungen zählen nicht gegen das Marschlimit. Pro Spieler läuft höchstens eine Aufklärung gleichzeitig. Logistik und g gelten auch für sie.
- Ein laufender Marsch reserviert sein Ziel nicht.
- Laufzeit mit Distanz d, Tempo s der langsamsten Einheit (größter Wert in s pro Feld), Marschtempo-Boni B und Spielgeschwindigkeit g. Der Rückweg wird genauso berechnet:

```latex
T = \left\lceil \frac{d \cdot s}{(1 + B_{Marsch}) \cdot g} \right\rceil
```

- Bei Ankunft prüft der Server das Ziel: Objekt noch vorhanden, Basis noch am Ort, kein Schild, Ziel nicht (mehr) in der eigenen Allianz. Ein Sammelmarsch braucht ein freies Feld, ein besetztes Feld ist für ihn ungültig.
- Ist das Ziel ungültig, gibt es den Bericht „Ziel nicht verfügbar“ und der Marsch kehrt um.
- Wer zuerst ankommt, handelt zuerst. Gleichzeitige Ankünfte werden in Reihenfolge der Marsch-ID verarbeitet.

**Zustände eines Marschs**

| Zustand | Bedeutung | Übergang |
| --- | --- | --- |
| UNTERWEGS | läuft zum Ziel | bei Ankunft: Kampf und RÜCKWEG, oder SAMMELT, STATIONIERT, WARTET |
| SAMMELT | sammelt auf einem Feld | bei Ende oder Rückruf: RÜCKWEG |
| STATIONIERT | Verstärkung in fremder Basis | bei Rückruf: RÜCKWEG |
| WARTET | Sammelangriff-Teilnehmer an der Basis des Starters | beim Start des Sammelangriffs: UNTERWEGS |
| RÜCKWEG | läuft heim | bei Ankunft: Truppen und Ladung nach Hause, Held frei, Marsch gelöscht |

**Rückruf:** Aus UNTERWEGS kehrt der Marsch sofort um und braucht zurück so lange, wie er schon gelaufen ist. Aus SAMMELT, STATIONIERT und WARTET startet der Rückweg sofort vom aktuellen Ort. Im RÜCKWEG und nach dem Start eines Sammelangriffs ist kein Rückruf möglich.

**Sammeln**

- Traglast eines Marschs = Summe über alle Einheiten aus Anzahl × Traglast, mal (1 + Traglast-Boni).
- Bei Ankunft plant der Server das Ende: Dauer = min(Traglast, Restvorrat) ÷ Sammelrate.
- Gesammelte Menge zu einem Zeitpunkt = min(Traglast, Restvorrat, ⌊Sammelrate × vergangene Zeit⌋).
- Bei Ende, Rückruf oder verlorenem Kampf wird der Feldvorrat um die gesammelte Menge reduziert. Bei Vorrat 0 verschwindet das Feld. Die Ladung wird bei der Heimkehr gutgeschrieben.

**Kampfalgorithmus**

Jede Seite besteht aus Stapeln. Ein Stapel sind alle Einheiten eines Besitzers mit gleichem Typ und gleicher Stufe. Eine Zombiegruppe oder ein Nest ist ein einziger Stapel.

- Effektive Werte je Stapel: Angriff a = Grundangriff × (1 + Angriffsboni), Verteidigung v = Grundverteidigung × (1 + Verteidigungsboni), LP h = Grund-LP. Kampfwerte sind Kommazahlen ohne Rundung.
- Angriffsboni: Forschung des Besitzers, Held des Marsches oder Verteidigungsheld, gegen Zombies zusätzlich Zombiekunde und Viktor.
- Verteidigungsboni: Forschung, Held, in einer Basis zusätzlich die Mauer des Basisbesitzers. Der Mauerbonus gilt auch für stationierte Verstärkungen.

Jede Runde, höchstens 20, läuft so ab:

1. Mit den Anzahlen vom Rundenbeginn berechnet der Server für jedes Paar aus Stapel X und gegnerischem Stapel Y den Schaden S. Dabei ist k = 1,25, wenn X den Typ von Y kontert, sonst 1.
2. Jeder Stapel Y sammelt den Schaden aller Gegner in seinem Übertrag Ü (Startwert 0). Verluste = min(n, ⌊Ü ÷ h⌋), danach Ü = Ü − Verluste × h.
3. Die Verluste beider Seiten werden gleichzeitig abgezogen. Hat eine Seite keine Einheiten mehr, endet der Kampf.

```latex
S_{X \to Y} = n_X \cdot a_X \cdot \frac{n_Y h_Y}{\sum_{Z \in \text{Gegner}} n_Z h_Z} \cdot k_{XY} \cdot \frac{100}{100 + v_Y}
```

- Der Angreifer siegt nur, wenn der Verteidiger keine Einheiten mehr hat und er selbst noch mindestens eine. Sonst siegt der Verteidiger, auch nach 20 Runden und bei gegenseitiger Vernichtung.
- Hat eine angegriffene Basis keine Verteidiger, siegt der Angreifer ohne Kampf.
- Als Verlust zählt jede Einheit, die den Kampf verlassen muss, egal ob sie danach verwundet oder tot ist.

**Rechenbeispiel (Testfall):** 200 Infanterie T1 ohne Boni greifen Zombies Stufe 1 an (60 Zombies). Runde 1: 1.851,85 Schaden gegen die Zombies, also 20 Verluste; 482,14 Schaden gegen die Infanterie, also 4 Verluste. Nach 3 Runden sind alle Zombies besiegt, die Infanterie hat 9 Verluste.

**Verluste: verwundet oder tot**

- Für jeden Besitzer kommen die Verluste ins eigene Lazarett, beginnend mit der höchsten Stufe (bei gleicher Stufe erst Infanterie, dann Fahrzeuge, dann Schützen), bis dessen freier Platz verbraucht ist. Der Rest stirbt.
- Das gilt in jedem Kampf gleich: Zombies, Nester, PvP, für Angreifer, Verteidiger und Verstärkungen. Verwundete Angreifer liegen sofort im Lazarett.
- Überlebende eines Angriffs laufen mit dem Rückweg heim.

**Beute**

Siegt ein Angreifer gegen eine Basis, ist je Ressource die Menge P plünderbar (Bestand zum Kampfzeitpunkt minus geschützte Menge, mindestens 0). C ist die Traglast aller überlebenden Angreifer. Die Beute je Ressource R ist:

```latex
B_R = \min\left(P_R,\ \left\lfloor C \cdot \frac{P_R}{\sum P} \right\rfloor\right)
```

- Ist die Summe aller P gleich 0, gibt es keine Beute. Die Beute wird beim Verteidiger sofort abgezogen und beim Angreifer bei der Heimkehr gutgeschrieben.
- Siegt ein Angreifer gegen einen Sammelmarsch, erhält er dessen Ladung bis zur eigenen Traglast, der Rest verfällt. Der Verlierer kehrt mit seinen Überlebenden ohne Ladung heim. Der Sieger besetzt das Feld nicht, sondern kehrt ebenfalls heim.

**Berichte**

- Kampfbericht für beide Seiten: Zeitpunkt, Ort, Teilnehmer, Helden, je Stapel Anzahl vorher sowie verwundet, tot und überlebt, Rundenzahl, Sieger, Beute, Heldenerfahrung, Drops.
- Aufklärungsbericht: Bestand und plünderbare Menge je Ressource, Truppen zu Hause je Typ und Stufe, Mauerstufe, Gesamtzahl stationierter Verstärkungen, Verteidigungsheld mit Stufe. Das Ziel erhält den Bericht „Du wurdest von X aufgeklärt“.
- Startet ein Angriff, Sammelangriff oder eine Aufklärung gegen eine Basis oder einen Sammelmarsch, sieht der Betroffene sofort Angreifer, Marschart und Ankunftszeit, aber keine Truppendetails.

## 8. PvP-Regeln und Schutzmechanismen

Jeder darf jeden angreifen, außer Mitglieder der eigenen Allianz und geschützte Basen. Es gibt keine Machtgrenze. Fünf Schutzmechanismen und ein Aufholbonus verhindern, dass Freunde dauerhaft abgefarmt werden.

| Mechanismus | Auslöser | Dauer |
| --- | --- | --- |
| Neulingsschutz | Registrierung | 72 h; endet vorzeitig bei HQ 6 oder bei eigener PvP-Aktion |
| Friedensschild | Spieler benutzt Item | 8 h oder 24 h |
| Erholungsschild | 3 verlorene Verteidigungen der Basis innerhalb von 12 h | 4 h; danach beginnt die Zählung neu |
| Inaktivitätsschild | 7 Tage ohne Login, geprüft beim Tagesreset | bis zum nächsten Login |
| Sperrschild | Admin sperrt oder schützt einen Spieler | Dauer der Sperre bzw. des Befehls `/shield` |

**Wirkung jedes Schutzes**

- Die Basis kann nicht angegriffen, aufgeklärt oder Ziel eines Sammelangriffs werden. Bereits laufende fremde Märsche finden bei Ankunft ein ungültiges Ziel und kehren um.
- Schilde lassen sich jederzeit aktivieren, auch wenn schon ein Angriff unterwegs ist. Das Schildende ist max(bisheriges Ende, jetzt + Dauer), Schilde addieren sich also nicht.
- Märsche auf der Karte sind nie geschützt. Das betrifft vor allem Sammelmärsche.
- Startet ein Spieler selbst einen Angriff, eine Aufklärung oder einen Sammelangriff gegen einen Spieler (Basis oder Sammelmarsch) oder tritt er einem solchen bei, enden sein Friedensschild und sein Neulingsschutz sofort. Aktionen gegen Zombies und Nester sowie Sammeln beenden keinen Schutz.

**Aufholbonus**

- Aktiv ist, wer sich in den letzten 7 Tagen eingeloggt hat. Beim täglichen Reset berechnet der Server den Median der HQ-Stufen aller aktiven Spieler.
- Wer eine HQ-Stufe ≤ Median − 3 hat, erhält bis zum nächsten Reset +50 % Bautempo, +50 % Forschungstempo und +25 % Produktion.
- Neue Spieler werden bei der Registrierung sofort mit dem aktuellen Median geprüft.
- Der Client zeigt einen aktiven Aufholbonus als Symbol in der oberen Leiste.

## 9. Allianzen

Eine Allianz hat bis zu 30 Mitglieder und bietet Timer-Hilfe, Verstärkung, Sammelangriffe, einen eigenen Chat und Geschenke. Jeder Spieler ist in höchstens einer Allianz.

**Gründung und Mitgliedschaft**

- Gründen ab HQ 4, kostenlos. Name 3 bis 20 Zeichen aus Buchstaben, Ziffern und Leerzeichen, eindeutig ohne Beachtung der Groß- und Kleinschreibung.
- Kürzel genau 3 Zeichen aus A–Z und 0–9, eindeutig. Beschreibung höchstens 500 Zeichen.
- Beitrittsmodus „offen“ (sofortiger Beitritt) oder „auf Anfrage“ (Anführer oder Offizier nimmt an oder lehnt ab). Anfragen verfallen nach 7 Tagen.
- Beitreten ist ab HQ 1 möglich. Nach Austritt oder Rauswurf gilt 12 h Beitrittssperre gegen Allianz-Hopping.
- Beim Austritt kehren eigene Verstärkungen und fremde Verstärkungen in der eigenen Basis heim. Eigene wartende Sammelangriff-Märsche werden zurückgerufen.

**Ränge und Rechte**

| Recht | Anführer | Offizier | Mitglied |
| --- | --- | --- | --- |
| Beschreibung und Beitrittsmodus ändern | ja | ja | nein |
| Anfragen annehmen oder ablehnen | ja | ja | nein |
| Spieler entfernen | alle | nur Mitglieder | nein |
| Befördern und degradieren | ja | nein | nein |
| Anführung übergeben | ja | nein | nein |
| Sammelangriff starten | ja | ja | ja |
| Allianz auflösen | nur als letztes Mitglied | nein | nein |

- Der Anführer kann erst austreten, wenn er die Anführung übergeben hat. Als letztes Mitglied löst sein Austritt die Allianz auf.
- Hat sich der Anführer 7 Tage nicht eingeloggt, geht die Anführung beim Tagesreset an den Offizier mit dem jüngsten Login, ohne Offiziere an das Mitglied mit dem jüngsten Login.

**Allianzhilfe**

- Für Bau-, Forschungs- und Heiltimer kann der Besitzer einmal Hilfe anfordern.
- Jedes andere Mitglied kann pro Timer einmal helfen, auch gesammelt per Knopf „Allen helfen“.
- Jede Hilfe verkürzt die Restzeit um max(60 s ÷ g, 1 % der ursprünglichen Gesamtdauer).
- Die Höchstzahl der Hilfen wird beim Anfordern festgelegt: 3 ohne Allianzzentrum, sonst 5 + ⌊L/2⌋ mit L = Stufe des Allianzzentrums des Anfragenden.

**Verstärkung**

- Ein Mitglied schickt einen Marsch in die Basis eines anderen. Dort verteidigt er mit eigener Forschung, eigenem Helden und dem Mauerbonus des Gastgebers.
- Platz beim Gastgeber: Verstärkungsplatz seines Allianzzentrums, gezählt über alle stationierten Einheiten. Ohne Allianzzentrum ist keine Verstärkung möglich.
- Pro Spender und Gastgeber gibt es höchstens einen Verstärkungsmarsch.
- Der Spender kann zurückrufen, der Gastgeber kann jede Verstärkung heimschicken. Verluste gehen ins Lazarett des Spenders.

**Sammelangriff**

1. Ein Mitglied (Starter) wählt ein Ziel (Zombie-Nest oder fremde Spielerbasis ohne Schild), eine Wartezeit von 5, 10 oder 30 min (geteilt durch g) und seinen Marsch. Dieser wartet sofort in seiner Basis.
2. Bis zu 4 weitere Mitglieder treten mit je einem Marsch bei. Der Marsch läuft zur Basis des Starters und muss vor Ablauf der Wartezeit ankommen, sonst lehnt der Server den Beitritt ab.
3. Wartende Märsche verteidigen die Basis des Starters nicht und können nicht angegriffen werden.
4. Nach Ablauf starten alle wartenden Märsche gemeinsam. Die Laufzeit richtet sich nach der langsamsten Einheit aller Märsche.
5. Im Kampf bilden alle Stapel aller Teilnehmer eine Seite. Jeder Stapel nutzt Forschung und Helden seines eigenen Besitzers.
6. Danach läuft jeder Marsch vom Ziel direkt zur eigenen Basis.

- Ein angegriffener Spieler sieht den Sammelangriff ab seiner Erstellung, mit Startzeitpunkt und Starter.
- Hat das Ziel bei Ankunft einen Schild, kehren alle um. Der Starter kann vor dem Start abbrechen, dann kehren alle heim.
- Beute gegen eine Basis wird nach dem Anteil der Traglast jedes Teilnehmers an der Gesamttraglast verteilt, abgerundet.
- Nest-Belohnungen erhält jeder Teilnehmer vollständig (Abschnitt 6).
- Ein Spieler kann an mehreren Sammelangriffen gleichzeitig teilnehmen, solange er freie Helden hat.

**Allianzgeschenke**

- Besiegt ein Sammelangriff ein Nest der Stufe N, erhält jedes Mitglied der Allianz ein Geschenk, auch wer nicht beteiligt war: je 5.000 × N Nahrung und Holz sowie 1 Beschleuniger 15 min.
- Geschenke werden im Allianzmenü abgeholt und verfallen nach 7 Tagen. Wer die Allianz verlässt, verliert offene Geschenke.

## 10. Items, Tagesaufgaben, Erfolge und Kosmetik

Alle Items gibt es nur als Spielbelohnung: aus Zombies, Nestern, Allianzgeschenken, Tagesaufgaben und Erfolgen. Items verfallen nie.

**Items**

| Item | ID | Wirkung |
| --- | --- | --- |
| Beschleuniger 1 min, 5 min, 15 min, 60 min, 3 h, 8 h | `SPEED_1M`, `SPEED_5M`, `SPEED_15M`, `SPEED_60M`, `SPEED_3H`, `SPEED_8H` | verkürzt einen eigenen Bau-, Forschungs-, Ausbildungs- oder Heiltimer um die Dauer geteilt durch g; Überschuss verfällt |
| Ressourcenkiste Nahrung klein, mittel, groß | `RES_FOOD_S`, `RES_FOOD_M`, `RES_FOOD_L` | 1.000, 10.000 oder 50.000 Nahrung |
| Ressourcenkiste Holz klein, mittel, groß | `RES_WOOD_S`, `RES_WOOD_M`, `RES_WOOD_L` | 1.000, 10.000 oder 50.000 Holz |
| Ressourcenkiste Stahl klein, mittel, groß | `RES_STEEL_S`, `RES_STEEL_M`, `RES_STEEL_L` | 500, 5.000 oder 25.000 Stahl |
| Friedensschild 8 h, 24 h | `SHIELD_8H`, `SHIELD_24H` | Schild nach Abschnitt 8 |
| Umzugsgutschein | `RELOCATE` | Umzug nach Abschnitt 6 |
| Heldenhandbuch klein, groß | `HERO_XP_S`, `HERO_XP_L` | 500 oder 2.000 Erfahrung für einen gewählten Helden |

Beschleuniger, Kisten und Handbücher lassen sich in beliebiger Anzahl auf einmal benutzen. Schild und Umzug werden einzeln benutzt.

**Tagesaufgaben**

- Reset täglich um 04:00 Uhr `Europe/Berlin`. Fortschritt zählt ab dem Reset.
- Belohnungen werden manuell abgeholt. Nicht abgeholte Belohnungen verfallen beim nächsten Reset.
- Die Größe der Ressourcenkisten richtet sich nach dem HQ beim Abholen: HQ 1–7 klein, HQ 8–14 mittel, HQ 15–20 groß.

| Aufgabe | Ziel am Tag | Belohnung |
| --- | --- | --- |
| Zombiejagd | 5 Zombiegruppen besiegen | 2 × Beschleuniger 15 min |
| Bauherr | 1 Bau- oder Ausbauauftrag starten | 1 Ressourcenkiste Nahrung |
| Drill | 200 Einheiten in Ausbildung geben | 1 × Beschleuniger 60 min |
| Sammler | 20.000 Ressourcen von Feldern heimbringen | 1 Ressourcenkiste Holz |
| Kamerad | 5 Allianzhilfen leisten | 1 Heldenhandbuch klein |
| Tagesbonus | 4 der 5 Aufgaben abgeholt | 1 × Beschleuniger 3 h und 1 Ressourcenkiste Stahl |

**Erfolge**

Erfolge sind einmalig und dienen zugleich als Tutorial. Die Basisansicht zeigt als „Nächstes Ziel“ den ersten noch nicht erfüllten Erfolg dieser Liste. Erfüllt werden können alle in beliebiger Reihenfolge, die Belohnung wird manuell abgeholt und verfällt nicht.

| Nr. | Erfolg | Belohnung |
| --- | --- | --- |
| 1 | HQ Stufe 2 | 2 × Beschleuniger 5 min |
| 2 | Eine Zombiegruppe besiegen | Heldenhandbuch klein |
| 3 | Mit Ladung von einem Ressourcenfeld heimkehren | Ressourcenkiste Nahrung klein |
| 4 | HQ Stufe 3 | 2 × Beschleuniger 15 min |
| 5 | Eine Forschung abschließen | Ressourcenkiste Stahl klein |
| 6 | Insgesamt 500 Einheiten ausbilden | Beschleuniger 60 min |
| 7 | Einer Allianz beitreten oder eine gründen | Umzugsgutschein |
| 8 | HQ Stufe 5 | Friedensschild 8 h |
| 9 | Zombiegruppe Stufe 5 besiegen | Beschleuniger 3 h |
| 10 | Einen Spieler aufklären | Ressourcenkiste Nahrung mittel |
| 11 | An einem Sammelangriff bis zum Kampf teilnehmen | Beschleuniger 3 h |
| 12 | HQ Stufe 10 | Basis-Skin „Wellblech“ und Beschleuniger 8 h |
| 13 | Zombiegruppe Stufe 10 besiegen | Heldenhandbuch groß |
| 14 | Eine Verteidigung der eigenen Basis gewinnen | Profilrahmen „Bollwerk“ |
| 15 | Ein Zombie-Nest Stufe 3 besiegen | Profilrahmen „Nestjäger“ |
| 16 | HQ Stufe 15 | Basis-Skin „Festung“ |
| 17 | Zombiegruppe Stufe 15 besiegen | 2 × Beschleuniger 8 h |
| 18 | Insgesamt 200 Zombiegruppen besiegen | Profilrahmen „Seuchenbrecher“ |
| 19 | Zombiegruppe Stufe 20 besiegen | Profilrahmen „Legende“ |
| 20 | HQ Stufe 20 | Basis-Skin „Zitadelle“ |

**Kosmetik**

- Basis-Skins ändern das Aussehen der eigenen Basis auf der Karte und den Hintergrund der Basisansicht: Standard (`SKIN_DEFAULT`), Wellblech (`SKIN_TIN`), Festung (`SKIN_FORT`), Zitadelle (`SKIN_CITADEL`).
- Profilrahmen umrahmen das Spielerbild in Chat, Profil und Rangliste: Standard (`FRAME_DEFAULT`), Bollwerk (`FRAME_BULWARK`), Nestjäger (`FRAME_NEST`), Seuchenbrecher (`FRAME_PLAGUE`), Legende (`FRAME_LEGEND`).
- Kosmetik hat keine Spielwerte und ist im Profil jederzeit wechselbar.

## 11. Chat, Berichte, Ranglisten und Moderation

Es gibt einen Weltchat und einen Allianzchat, Berichte als Postfach, drei Ranglisten und Admin-Befehle direkt im Chat. Privatnachrichten gibt es in v1 nicht.

**Chat**

- Kanäle `world` (alle) und `alliance` (eigene Allianz). Nachrichten haben höchstens 300 Zeichen, jeder Spieler darf höchstens 1 Nachricht pro 2 s senden.
- Der Client lädt beim Öffnen die letzten 50 Nachrichten und ältere seitenweise nach. Der Server hält den Verlauf 30 Tage.
- Langes Drücken auf eine Nachricht öffnet „Melden“. Meldungen landen in einer Admin-Liste. Admins sehen zusätzlich „Löschen“.
- Gelöschte Nachrichten erscheinen als „Nachricht entfernt“.

**Berichte**

- Arten: Kampf, Aufklärung, Sammeln (Ergebnis bei Heimkehr), System (Allianzereignisse, Admin-Durchsagen, Schildbeginn und -ende).
- Jeder Bericht hat den Status gelesen oder ungelesen und kann gelöscht werden. Der Server löscht Berichte nach 30 Tagen.
- Ein eingehender Angriff erscheint zusätzlich als rotes Banner bis zu seiner Ankunft.

**Ranglisten**

| Rangliste | Wert |
| --- | --- |
| Spielermacht | Summe der Kampfkraft aller eigenen Einheiten außer Verwundeten, + 50 je Gebäudestufe, + 100 je Forschungsstufe, + 200 je Heldenstufe |
| Allianzmacht | Summe der Spielermacht aller Mitglieder |
| Zombiejäger | Anzahl insgesamt besiegter Zombiegruppen |

Ranglisten zeigen die Top 100 und die eigene Position. Der Server berechnet sie bei jedem Abruf, bei 50 Spielern ist das trivial.

**Moderation**

- Rollen: `PLAYER` und `ADMIN`. Admins werden in der Serverkonfiguration per Benutzername festgelegt.
- Admin-Befehle werden im Chat eingegeben, beginnen mit `/` und werden nicht als Nachricht gesendet. Das Ergebnis erscheint als Systemmeldung nur für den Admin.
- Dauern im Format `30m`, `12h`, `7d` oder `perm`.
- Jede Admin-Aktion wird im Audit-Log gespeichert.

| Befehl | Wirkung |
| --- | --- |
| `/ban <name> <dauer> [grund]` | sperrt den Login, beendet alle Sitzungen, Basis erhält für die Dauer einen Schild |
| `/unban <name>` | hebt die Sperre auf |
| `/mute <name> <dauer>` | verbietet das Schreiben im Chat |
| `/unmute <name>` | hebt das Schreibverbot auf |
| `/del <nachricht-id>` | löscht eine Chatnachricht (alternativ per langem Drücken) |
| `/rename <alt> <neu>` | benennt einen Spieler um |
| `/password <name> <neues-passwort>` | setzt ein neues Passwort, z. B. wenn ein Spieler seins vergessen hat; alle Sitzungen des Spielers enden; das Audit-Log speichert das Passwort nicht |
| `/shield <name> <dauer>` | gibt einer Basis einen Schild, zum Schutz vor Schikane |
| `/announce <text>` | Systemdurchsage an alle als Bericht und Chat-Banner |
| `/reports` | listet offene Chat-Meldungen; `/resolve <id>` markiert eine als erledigt |

Nur bei `devMode=true` gibt es zusätzlich `/give <name> <item-id> <anzahl>`, `/res <name> <n> <h> <s>` und `/finish <name>` (alle Timer sofort fertig).

## 12. Client (Android mit LibGDX)

Der Client ist eine LibGDX-App im Hochformat mit drei Screens und scene2d-Dialogen. Er entscheidet nichts, sondern zeigt den Serverzustand an und zählt Timer lokal herunter.

**Rahmendaten**

- Paket `bayern.kickner.ruinborn`, minSdk 26 (Android 8.0), compileSdk und targetSdk so, wie gdx-liftoff 1.14.2.2 sie erzeugt, danach festgepinnt; einzige Berechtigung android.permission.INTERNET.
- Hochformat, virtuelle Auflösung 720 × 1280 mit `ExtendViewport`.
- Desktop-Start (LWJGL3) mit Fenster 540 × 960 zum Testen unter Linux.
- Die Server-URL kommt beim Build aus `gradle.properties` (`ruinborn.serverUrl`).
- Alle Texte stehen deutsch in `assets/i18n/strings.properties` und werden über `I18NBundle` geladen.

**Screens und Dialoge**

| Screen | Inhalt |
| --- | --- |
| LoginScreen | Anmelden oder Registrieren (Benutzername, Passwort, bei Registrierung Einladungscode); Versionsprüfung beim Start |
| BaseScreen | Basisansicht mit 20 Bauplätzen |
| MapScreen | Weltkarte |

Dialoge sind scene2d-Fenster über dem aktuellen Screen: Gebäude (Info, Ausbau, Abriss), Ausbildung, Forschung, Lazarett, Helden, Inventar, Marsch, Allianz (Übersicht, Mitglieder, Anfragen, Hilfe, Sammelangriffe, Geschenke), Chat, Berichte, Aufgaben und Erfolge, Ranglisten, Profil und Kosmetik, Einstellungen (Abmelden, Version).

**Layout**

- Obere Leiste: Bestand und Produktion pro Stunde der drei Ressourcen, Spielermacht, Schutzstatus, Aufholbonus-Symbol.
- Darunter: „Nächstes Ziel“ und das rote Banner für eingehende Angriffe.
- Untere Leiste: Basis/Karte-Umschalter, Helden, Allianz (mit Zähler offener Hilfen), Chat, Berichte (mit Zähler ungelesener), Mehr (Inventar, Aufgaben, Ranglisten, Profil, Einstellungen).
- Linke Timer-Leiste: alle laufenden Timer und Märsche mit Countdown. Tippen öffnet Beschleunigen, Hilfe anfordern, Abbrechen oder Rückruf.
- Aktionen, die länger als 1 h dauern oder sich gegen Spieler richten, fordern eine Bestätigung.

**BaseScreen**

- Hintergrund 1440 × 1920 px, per Wischen verschiebbar, ohne Zoom.
- 20 Bauplätze in einem Raster aus 4 Spalten und 5 Reihen. Jede Zelle ist 360 × 384 px groß, das Gebäudebild 256 × 256 px sitzt mittig. Die Positionen stehen in `assets/base_layout.json` (Platz-ID, x, y, Breite, Höhe).
- Belegung von oben nach unten: Reihe 1 `HQ`, `LAGER`, `LABOR`, `SAMMELPUNKT`; Reihe 2 `KASERNE`, `FABRIK`, `SCHIESSSTAND`, `LAZARETT`; Reihe 3 `MAUER`, `ALLIANZ`, `R1`, `R2`; Reihe 4 `R3` bis `R6`; Reihe 5 `R7` bis `R10`.
- Leere Plätze zeigen ein Baustellen-Symbol, gesperrte Plätze ein Schloss mit der nötigen HQ-Stufe. Tippen öffnet das Baumenü. Gebäude im Ausbau zeigen ein Gerüst mit Fortschrittsbalken.

**MapScreen**

- Ein Feld ist 64 × 64 px. `OrthographicCamera` mit Zoom 0,5 bis 2,0; Wischen verschiebt, zwei Finger zoomen (`GestureDetector`).
- Gezeichnet werden nur sichtbare Felder plus 1 Feld Rand. Jede Zone hat eine eigene Bodentönung.
- Farben für Basen und Märsche: eigene grün, Allianz blau, fremd rot. Zombies, Nester und Felder erscheinen als Symbol mit Stufenzahl.
- Märsche sind eine Linie von Start zu Ziel mit Symbol. Die Position wird linear aus Start- und Ankunftszeit berechnet.
- Tippen auf ein Feld öffnet ein Info-Panel mit den möglichen Aktionen: Angreifen, Sammeln, Aufklären, Verstärken, Sammelangriff starten, Hierher umziehen. Ein Knopf zentriert die eigene Basis.

**Marsch-Dialog:** Auswahl eines freien Helden, ein Schieberegler je Typ und Stufe, Knopf „Maximum“ (füllt mit höchster Stufe zuerst), Anzeige von Einheiten und Marschgröße, Kampfkraft, Traglast und Laufzeit.

**Grafik (Platzhalter)**

- Alle Sprites sind einfache flache SVG-Grafiken in `assets-raw/svg/`, erstellt von Claude: klare Formen, eine Farbe je Gebäudetyp. Stufenzahlen zeichnet das Spiel als Text darüber.
- Benötigt: 13 Gebäude, Baustelle, Gerüst, 3 Truppentypen, 5 Heldenporträts, Zombiegruppe, Nest, 3 Ressourcenfelder, 3 Bodenkacheln, 4 Basis-Skins, 5 Profilrahmen, 3 Ressourcen-Icons, 7 Item-Icons sowie UI-Symbole (Schild, Uhr, Schwert, Lupe, Pfeil, Chat, Bericht, Allianz, Zahnrad).
- Umwandlung mit `tools/svg2png.sh`, das je Datei `rsvg-convert -w 128 -h 128 in.svg -o out.png` aufruft (Debian-Paket `librsvg2-bin`), nach `assets-raw/png/`.
- `./gradlew :tools:packTextures` packt mit dem TexturePacker aus gdx-tools alles in `assets/atlas/game.atlas`.
- Schrift: Noto Sans (SIL Open Font License) als fertige Bitmap-Schriften in 16, 20 und 28 px, erzeugt in 1,5-facher Größe. `./gradlew :tools:generateFonts` erzeugt sie einmalig lokal mit FreeType und `BitmapFontWriter` aus gdx-tools, inklusive Umlauten, ß und €. Das Ergebnis liegt im Repository unter `assets/fonts/`, die App selbst enthält kein FreeType.
- Der UI-Skin wird im Code definiert (ktx-style), ohne Skin-JSON.

**Zustand und Netzwerk**

- Der Spielzustand liegt in einem `GameState` aus unveränderlichen Datenklassen des shared-Moduls und wird nur auf dem Render-Thread ersetzt.
- Netzwerkaufrufe laufen über den Ktor-Client als Coroutines auf `Dispatchers.IO`, Ergebnisse kommen per `onRenderingThread` (ktx-async) zurück. Die HTTP-Engine übergibt der Launcher: OkHttp auf Android, CIO auf dem Desktop.
- Jede Befehlsantwort enthält den neuen `PlayerState`. Das WebSocket-Ereignis `state_changed` löst `GET /api/state` aus.
- Countdowns nutzen den Versatz zwischen Serverzeit (Header `X-Server-Time`, bei jeder Antwort) und lokaler Uhr. Erreicht ein Timer 0, lädt der Client den Zustand neu.
- Die Balance-Daten lädt der Client nach dem Login (`GET /api/balance` mit ETag). Für Kosten- und Zeitanzeigen nutzt er dieselben Formeln wie der Server.
- Das Sitzungs-Token liegt in den LibGDX-Preferences `ruinborn`.
- Logging über Klogger mit `logToCustom { level, tag, msg -> Gdx.app.log(tag, msg) }`. So landet alles in Logcat bzw. auf der Konsole.

**Fehlerbehandlung**

- Verbindungsabbruch: Banner „Verbindung verloren – verbinde neu …“, Aktionen gesperrt, Neuversuch nach 1, 2, 5 und 10 s, danach alle 30 s. Nach erfolgreicher Verbindung lädt der Client Zustand, Karte und Balance-Daten (per ETag) neu.
- Zeitlimit je HTTP-Anfrage 10 s. Nach einem Timeout wiederholt der Client den Befehl einmal mit derselben `X-Request-Id`, der Server führt ihn nicht doppelt aus.
- Serverfehler: kurze Einblendung mit deutschem Text zum Fehlercode (Abschnitt 15). Status 401 führt zum Login, 426 zum blockierenden Update-Dialog mit Download-Link.
- Unter Einstellungen kann der Spieler sein Passwort ändern (`POST /api/auth/password`).

## 13. Technische Architektur

Ein Gradle-Projekt mit sechs Modulen, komplett in Kotlin. Der Server verarbeitet alle Schreibvorgänge nacheinander in einem einzigen Thread, die SQLite-Datenbank ist die einzige Wahrheit.

**Module**

| Modul | Art | Inhalt | Abhängigkeiten |
| --- | --- | --- | --- |
| `shared` | Kotlin/JVM-Bibliothek | DTOs, IDs und Enums, Balance-Datenklassen, Formeln, Ressourcenberechnung, Kampfsimulation, Validierungsregeln | kotlinx.serialization |
| `server` | Ktor-Anwendung als Fat-JAR | HTTP-API, WebSocket, Spiel-Engine, Datenbankzugriff, Migrationen, Jobs, Admin-Befehle, Balancing-Simulation | shared, Ktor Server, Exposed, sqlite-jdbc, Klogger, KotNexLib |
| `core` | Kotlin/JVM-Bibliothek | gesamter Client-Spielcode: Screens, Dialoge, Rendering, Netzwerk | shared, libGDX, KTX, Ktor Client, Klogger |
| `android` | Android-App | Launcher, Manifest, Signierung | core, gdx-backend-android, Ktor-Engine OkHttp |
| `lwjgl3` | Desktop-App | Launcher zum Testen unter Linux | core, gdx-backend-lwjgl3, Ktor-Engine CIO |
| `tools` | Build-Werkzeug | Texture-Packing, Schrift-Erzeugung | gdx-tools, gdx-freetype |

**Projektstruktur und Konventionen**

- Ein privates GitHub-Repository `ruinborn` enthält alle sechs Module. Im Wurzelverzeichnis liegt eine `CLAUDE.md` mit den Konventionen dieses Abschnitts.
- Pakete: `bayern.kickner.ruinborn.shared` (DTOs, Regeln, Formeln, Kampf), `bayern.kickner.ruinborn.server` mit den Unterpaketen `api`, `engine`, `db`, `jobs`, `admin`, `sim`, `bayern.kickner.ruinborn.client` mit `screen`, `ui`, `net`, `state`, `render` sowie `bayern.kickner.ruinborn.tools`.
- Code-Stil wie im Referenzprojekt DemoAiProject: `runCatching` statt `try/catch`, `.not()` statt `!`, sealed Klassen gegen ungültige Zustände. Im Server sind erwartbare Fehler `ResultOf2`-Werte aus KotNexLib, keine Exceptions.
- Die App-Version steht einmal in `gradle.properties` (`ruinborn.versionCode`). Android übernimmt sie als `versionCode`, ein Gradle-Task erzeugt daraus `BuildInfo.kt` im `core`-Modul für den Header `X-Client-Version`.
- Zufall (Spawns, Drops) kommt aus einem injizierbaren `Random`, Zeit aus der injizierbaren `Clock`. Tests setzen beide fest.
- Datenbankverbindungen: Exposed öffnet je Transaktion eine eigene Verbindung (`Database.connect(getNewConnection = { … })`), die Pragmas setzt dieses Lambda. `journal_mode=WAL` setzt der Server einmal beim Start.
- Die Schreib-Datenbank nutzt nur die Engine. Die Lese-Datenbank (`SQLiteConfig.setReadOnly(true)`) nutzen die HTTP-Handler auf `Dispatchers.IO`.
- Das Backup läuft auf einer eigenen, normalen Verbindung auf `Dispatchers.IO`. Die Engine stößt es nur an und wartet nicht darauf.
- Beim Beenden (SIGTERM) nimmt der Server keine Anfragen mehr an, die Engine beendet die aktuelle Nachricht, danach schließt er alle Verbindungen.

**Bibliotheken (abschließende Liste)**

- kotlinx.serialization-json und kotlinx.coroutines.
- Ktor Server: `ktor-server-core`, `ktor-server-cio`, `ktor-server-websockets`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-server-status-pages`.
- Ktor Client: `ktor-client-core`, `ktor-client-websockets`, `ktor-client-content-negotiation`; als Engine `ktor-client-okhttp` auf Android und `ktor-client-cio` auf dem Desktop.
- Datenbank: `org.xerial:sqlite-jdbc` ab Version 3.51.3.0 (enthält den Fix für den WAL-Reset-Fehler von SQLite) und Exposed nur als DSL (`exposed-core`, `exposed-jdbc`).
- Eigene Bibliotheken aus `https://maven.kickner.bayern/releases`: `bayern.kickner:Klogger:0.1.0` für das Logging in Server und Client, `bayern.kickner:KotNexLib:4.3.0` für Hashing, nur im Server.
- Die internen SLF4J-Meldungen von Ktor leitet eine eigene Brücke (ein `SLF4JServiceProvider`, rund 40 Zeilen) an Klogger weiter. `slf4j-api` bringt Ktor ohnehin mit.
- libGDX: `gdx`, `gdx-backend-android`, `gdx-backend-lwjgl3`; `gdx-tools` und `gdx-freetype` nur im `tools`-Modul.
- KTX: `ktx-app`, `ktx-scene2d`, `ktx-actors`, `ktx-style`, `ktx-async`.
- Tests: `kotlin-test` (JUnit 5) und `ktor-server-test-host`.
- Bewusst nicht verwendet: Migrations-Framework, DI-Framework, Logback, slf4j-simple, Firebase, Argon2, FreeType zur Laufzeit.

Alle Versionen stehen in `gradle/libs.versions.toml` und werden nur bewusst angehoben. Stand 25.09.2026: Kotlin 2.4.20, Ktor 3.6.0, libGDX 1.14.2 mit der KTX-Version, die gdx-liftoff 1.14.2.2 einträgt, Gradle 9.7.1, sqlite-jdbc 3.53.4.0 und Exposed 1.x (stabile API innerhalb von 1.x; gepinnt wird die beim Projektstart aktuelle 1.x-Version). Gebaut wird mit Amazon Corretto 25 als Gradle-Toolchain (`languageVersion = 25`, `vendor = AMAZON`). `shared`, `core` und `android` erzeugen Bytecode für JVM 17 wegen Android, `server` für JVM 25.

**Spiel-Engine (Single-Writer)**

- Genau eine Coroutine auf einem Single-Thread-Dispatcher arbeitet einen `Channel<EngineMessage>` ab. HTTP-Handler legen einen Befehl mit `CompletableDeferred` hinein und warten auf die Antwort, ein `ResultOf2<PlayerState, GameError>`.
- Alles, was schreibt oder den Spielerzustand aktualisieren muss, läuft durch die Engine, auch `GET /api/state` und Chatnachrichten. Reine Lesezugriffe (Karte, Chatverlauf, Berichte, Ranglisten, Allianzlisten) nutzen eine zweite, nur lesende Verbindung.
- Ablauf jedes Befehls: Exposed-Transaktion öffnen, `settle` für alle betroffenen Spieler, prüfen, ändern, Folgeereignisse eintragen, Commit, danach WebSocket-Nachrichten senden.
- Bei einem Fehler: Rollback und Fehlercode an den Client. Neue Ereignisse kommen erst nach dem Commit in die Warteschlange im Speicher.
- Nichts Langsames läuft in der Engine: Passwort-Hashing, Backups und große Lesezugriffe laufen außerhalb.
- Es gibt keinen Daten-Cache. Einzige Struktur im Speicher ist die Ereigniswarteschlange, und die lässt sich jederzeit aus der Datenbank neu aufbauen.

**Spielereigene Timer (lazy)**

Bau, Forschung, Ausbildung und Heilung brauchen keine geplanten Ereignisse. Vor jedem Zugriff läuft `settle(spieler, t)`:

1. Alle Timer mit Ende ≤ t werden aufsteigend nach Ende sortiert, bei Gleichstand nach ID.
2. Für jeden Timer: Ressourcen bis zu seinem Ende materialisieren, Effekt anwenden (Stufe +1, Einheiten gutschreiben, Forschung +1, Geheilte zurück), Timer löschen, Erfolge und Tagesaufgaben prüfen.
3. Zum Schluss Ressourcen bis t materialisieren.

So stimmen Produktionsraten exakt, auch nach Tagen offline.

**Geplante Ereignisse**

Alles, was mehrere Spieler oder die Welt betrifft, ist eine Zeile in `scheduled_event`, gespiegelt in einer `PriorityQueue` (nach Fälligkeit, dann ID). Ein Wecker-Job wartet per `delay` bis zur nächsten Fälligkeit und legt dann ein `Wakeup` in denselben Kanal. Nach jeder Nachricht verarbeitet die Engine alle fälligen Ereignisse und stellt den Wecker neu: alten Job abbrechen, neuen starten. Das nutzt nur stabile Coroutine-APIs, und kein Befehl kann durch einen Timeout verloren gehen. Ein Ereignis wird mit seiner Fälligkeit als „jetzt“ verarbeitet, nicht mit der Wanduhr.

```kotlin
for (msg in inbox) {                 // Befehle und Wakeups aus einem Kanal
    if (msg is Command) msg.reply.complete(handle(msg))
    processDueEvents(clock.now())    // alle fälligen Ereignisse der Reihe nach
    rescheduleWakeup()               // wakeJob?.cancel(); wakeJob = launch { delay(bis nächste Fälligkeit); inbox.send(Wakeup) }
}
```

| Ereignis | Zeitpunkt | Wirkung |
| --- | --- | --- |
| `MARCH_ARRIVE` | Ankunft eines Marschs | Zielprüfung, Kampf, Sammelbeginn, Stationierung oder Aufklärung |
| `MARCH_HOME` | Heimkehr | Truppen und Ladung gutschreiben, Held frei |
| `GATHER_END` | Sammelende | Feldvorrat reduzieren, Rückweg starten |
| `RALLY_LAUNCH` | Ende der Wartezeit | gemeinsamen Marsch starten |
| `SPAWN` | alle 5 min | Kartenobjekte auffüllen |
| `DAILY_RESET` | täglich 04:00 | Tagesaufgaben, Aufholbonus, Inaktivitätsschilde, Anführerwechsel |
| `BACKUP` | täglich 03:30 | Datenbanksicherung (Abschnitt 16) |
| `CLEANUP` | täglich 03:45 | löscht Berichte und Chat älter als 30 Tage, abgelaufene Sitzungen, Anfragen und Geschenke, Request-IDs älter als 24 h |

- Wiederkehrende Ereignisse planen sich bei der Verarbeitung selbst neu.
- Beim Start lädt der Server alle Ereignisse und holt überfällige sofort in Reihenfolge nach. Ein Absturz verliert deshalb nichts.
- Die Zeitquelle ist ein Interface `Clock`. Tests ersetzen es durch eine steuerbare Uhr.

**Balance-Datei**

`balance.json` enthält jede Zahl aus den Abschnitten 3 bis 10. Der Server prüft sie beim Start vollständig (alle Schlüssel vorhanden, Werte positiv) und startet bei einem Fehler nicht. Änderungen erfordern einen Neustart. Auszug:

```json
{
  "version": 1,
  "buildings": {
    "HQ":   { "baseCost": [500, 500, 250], "baseTimeSec": 120, "costGrowth": 1.40, "timeGrowth": 1.30, "maxLevel": 20, "unlockHq": 1 },
    "FARM": { "baseCost": [120, 150, 50], "baseTimeSec": 45, "costGrowth": 1.40, "timeGrowth": 1.30, "maxLevel": 20, "unlockHq": 1, "prodPerHour": 100, "prodGrowth": 1.25 }
  },
  "units": {
    "INFANTRY": { "atk": 10, "def": 12, "hp": 100, "load": 12, "secPerTile": 30, "cost": [30, 20, 5], "trainSec": 10 }
  },
  "tiers": { "statGrowth": 1.6, "loadGrowth": 1.3, "trainGrowth": 1.4, "unlockLevels": [1, 5, 9, 13, 17] },
  "combat": { "maxRounds": 20, "counterMultiplier": 1.25, "defenseConstant": 100 }
}
```

Oberste Schlüssel der vollständigen Datei, jeweils mit allen Werten des genannten Abschnitts: `start` (2), `buildings` und `resourcePlots` (3), `research` (4), `units`, `tiers`, `hospital` und `heroes` (5), `map`, `zombies`, `nests`, `fields`, `spawn` und `relocation` (6), `marches`, `combat` und `scouting` (7), `protection` und `catchup` (8), `alliance`, `rally` und `gifts` (9), `items`, `daily`, `achievements` und `cosmetics` (10), `chat` und `rankings` (11). Jeder Schlüssel entspricht genau einer Datenklasse in `shared`.

Die Standarddatei liegt in `server/src/main/resources/balance.json`. Fehlt die Datei unter `balancePath`, schreibt der Server beim ersten Start die Standarddatei dorthin.

**Sicherheit**

- Passwörter: gesalzener, iterierter SHA-256 über KotNexLib, `"$salt:$passwort".hashIter(HashAlgorithm.SHA_256, iterations = 100_000)`, mit 16 Byte Salt aus `SecureRandom`. Gespeichert als `v1$<salt>$<hash>`; das Präfix erlaubt später einen Wechsel des Verfahrens beim nächsten Login.
- Der Vergleich ist zeitkonstant (`MessageDigest.isEqual`). Hashing läuft im HTTP-Handler auf `Dispatchers.Default`, nie in der Engine. Passwortlänge 8 bis 128 Zeichen.
- Benutzername 3 bis 16 Zeichen aus `A–Z`, `a–z`, `0–9` und `_`, eindeutig ohne Beachtung der Groß- und Kleinschreibung. Er ist zugleich der Spielername.
- Sitzungs-Token: 32 Byte aus `SecureRandom`, Base64url. Die Datenbank speichert nur den SHA-256-Hash (`hash(HashAlgorithm.SHA_256)` aus KotNexLib). Gültig 30 Tage ab letzter Nutzung.
- Registrierung nur mit dem Einladungscode aus der Serverkonfiguration und nur, solange weniger als maxPlayers Konten existieren (sonst Fehler SERVER\_FULL).
- HTTPS terminiert Caddy. Der Server lauscht nur im internen Netz auf Port 8080.
- Rate-Limits über einen eigenen Token-Bucket im Speicher: 20 Anfragen pro Sekunde je Token, 5 Login-Versuche pro Minute je IP (aus `X-Forwarded-For` von Caddy), Chat wie in Abschnitt 11.
- Jede Eingabe wird serverseitig geprüft: Längen, Zeichensätze, Mengen größer 0, Bestände, Besitz, erlaubte Ziele.
- Cheat-Schutz: Da der Server alles entscheidet, kann ein manipulierter Client nur erlaubte Befehle senden. Später möglich ist eine Auswertung auffälliger Befehlsraten im Log.

## 14. Datenmodell

Die Datenbank ist eine einzelne SQLite-Datei mit 29 Tabellen. Verschachtelte Werte wie Truppenlisten liegen als JSON im Format der DTOs aus `shared`.

**Konventionen**

- Zeitstempel: INTEGER, Millisekunden UTC. IDs: `INTEGER PRIMARY KEY`. Wahrheitswerte: INTEGER 0 oder 1. JSON: TEXT.
- Bei jeder Verbindung: `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`.
- Zugriff über die Exposed-DSL. Die Tabellenobjekte spiegeln die SQL-Migrationen, `SchemaUtils.create` wird nicht verwendet.
- SQLite kennt nur zwei Isolationsstufen, deshalb gilt `TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE`.
- Fremdschlüssel auf Konto und Spieler mit `ON DELETE CASCADE`.
- Truppenlisten als JSON, z. B. `[{"type":"INFANTRY","tier":1,"count":150}]`. Kosten als `{"food":0,"wood":0,"steel":0}`.
- Migrationen: `server/src/main/resources/db/V001__init.sql`, `V002__….sql` usw. Beim Start führt der Server in einer Transaktion alle Dateien mit Nummer größer `schema_version` aus. Das sind rund 40 Zeilen eigener Code.

**Tabellen**

| Tabelle | Spalten | Zweck |
| --- | --- | --- |
| `schema_version` | version | Migrationsstand |
| `server_meta` | key (PK), value | Weltstart, aktueller HQ-Median u. Ä. |
| `account` | id, username (unique, nocase), pw\_hash (Format v1$salt$hash), role, created\_at, last\_login\_at, banned\_until, ban\_reason, muted\_until | Konten |
| `session` | token\_hash (PK), account\_id, created\_at, expires\_at | Sitzungen |
| `player` | id (= account.id), food, wood, steel, res\_at, protection\_until, shield\_until, catchup\_until, last\_active\_at, max\_zombie\_level, zombies\_defeated, troops\_trained, skin, frame, alliance\_block\_until | Spielstand je Spieler |
| `building` | player\_id, plot, type, level; PK (player\_id, plot) | Gebäude |
| `timer` | id, player\_id, kind (BUILD, RESEARCH, TRAIN, HEAL), target, payload, cost, started\_at, ends\_at, total\_ms, help\_max, help\_count | laufende Aufträge |
| `timer_help` | timer\_id, helper\_id; PK beide | geleistete Hilfen |
| `research` | player\_id, tech, level; PK (player\_id, tech) | Forschungsstand |
| `troop` | player\_id, type, tier, home, wounded; PK (player\_id, type, tier) | Truppen zu Hause und verwundet |
| `hero` | player\_id, hero, level, xp, march\_id; PK (player\_id, hero) | Helden |
| `item` | player\_id, item, count; PK (player\_id, item) | Inventar |
| `map_object` | id, kind (BASE, ZOMBIE, NEST, FIELD), x, y, zone, level, res\_type, amount, player\_id, occupied\_by; unique (x, y) | alle Objekte auf der Karte |
| `march` | id, player\_id, kind, hero, troops, from\_x, from\_y, to\_x, to\_y, target\_id, state, depart\_at, arrive\_at, gather\_start, gather\_rate, cargo, rally\_id, host\_id | Märsche; from/to beschreiben immer den aktuellen Abschnitt |
| `rally` | id, alliance\_id, leader\_id, target\_id, launch\_at, state | Sammelangriffe |
| `alliance` | id, name (unique, nocase), tag (unique), leader\_id, join\_mode, description, created\_at | Allianzen |
| `alliance_member` | player\_id (PK), alliance\_id, rank, joined\_at | Mitgliedschaften |
| `alliance_request` | alliance\_id, player\_id, created\_at; PK beide | Beitrittsanfragen |
| `alliance_gift` | id, player\_id, level, created\_at, expires\_at, claimed | Allianzgeschenke |
| `chat_message` | id, channel, sender\_id, text, created\_at, deleted | Chat |
| `chat_report` | id, message\_id, reporter\_id, created\_at, resolved | Meldungen |
| `report` | id, player\_id, kind, created\_at, read, payload | Berichte |
| `daily_progress` | player\_id, day (YYYY-MM-DD), task, progress, claimed; PK (player\_id, day, task) | Tagesaufgaben |
| `achievement` | player\_id, achievement, completed\_at, claimed\_at; PK beide | Erfolge |
| `cosmetic` | player\_id, cosmetic; PK beide | freigeschaltete Kosmetik |
| `defense_loss` | player\_id, at | verlorene Verteidigungen für den Erholungsschild |
| `scheduled_event` | id, due\_at, type, payload | geplante Ereignisse |
| `processed_request` | player\_id, request\_id, created\_at, status, response; PK (player\_id, request\_id) | Schutz vor doppelter Ausführung |
| `audit_log` | id, admin\_id, action, target, details, created\_at | Admin-Aktionen |

**Indizes:** `timer(player_id)`, `march(player_id)`, `march(target_id)`, `map_object(kind)`, `chat_message(channel, id)`, `report(player_id, id)`, `scheduled_event(due_at)`.

**Wertebereiche und Regeln**

- Die Position einer Basis steht nur in `map_object` (`kind = BASE`, `player_id`).
- `account.role`: `PLAYER`, `ADMIN`. `alliance_member.rank`: `LEADER`, `OFFICER`, `MEMBER`. `alliance.join_mode`: `OPEN`, `REQUEST`.
- `timer.kind`: `BUILD`, `RESEARCH`, `TRAIN`, `HEAL`. `march.kind`: `ATTACK`, `GATHER`, `SCOUT`, `REINFORCE`, `RALLY`.
- `march.state`: `OUTBOUND`, `GATHERING`, `STATIONED`, `WAITING`, `RETURNING`, entsprechend UNTERWEGS, SAMMELT, STATIONIERT, WARTET, RÜCKWEG.
- `rally.state`: `WAITING`, `MARCHING`, `DONE`, `CANCELLED`. `report.kind`: `BATTLE`, `SCOUT`, `GATHER`, `SYSTEM`. `map_object.res_type`: `FOOD`, `WOOD`, `STEEL`.
- `chat_message.channel`: `world` oder `alliance:<id>`. Der API-Pfad `alliance` meint immer die eigene Allianz.
- `daily_progress.day` ist das Datum des letzten 04:00-Resets in `Europe/Berlin`. Der Tagesbonus hat `task = BONUS`.
- `account.pw_hash` hat das Format `v1$<Salt Base64>$<Hash Hex>`. `session.token_hash` ist der SHA-256 des Tokens als Hex-Text.

## 15. API und Protokoll

Befehle und Abfragen laufen über HTTP mit JSON. Ein WebSocket dient nur dazu, dass der Server den Client über Änderungen informiert. So bleibt jeder Befehl einzeln mit `curl` testbar.

**Grundregeln**

- JSON in UTF-8, Serialisierung mit den DTOs aus `shared`.
- Header bei authentifizierten Aufrufen: `Authorization: Bearer <token>` und `X-Client-Version: <versionCode>`. Jeder POST und DELETE trägt zusätzlich `X-Request-Id: <UUID>`.
- Jede Antwort trägt `X-Server-Time` (ms UTC). Schreibende Befehle antworten mit dem neuen `PlayerState`.
- Fehler antworten mit HTTP-Status und `{"code":"…","message":"…"}`.

**Endpunkte ohne Anmeldung**

| Methode und Pfad | Zweck |
| --- | --- |
| `GET /api/health` | liefert `ok`, für Monitoring |
| `GET /api/version` | `minClientVersion`, `latestClientVersion`, `apkUrl` |
| `POST /api/auth/register` | `{username, password, inviteCode}` → `{token}` |
| `POST /api/auth/login` | `{username, password}` → `{token}` |
| `GET /download/ruinborn.apk` | aktuelle App |

**Endpunkte mit Anmeldung**

| Methode und Pfad | Zweck |
| --- | --- |
| `POST /api/auth/logout` | Sitzung beenden |
| `GET /api/balance` | Balance-Daten, mit ETag |
| `GET /api/state` | eigener Spielstand (`PlayerState`) |
| `POST /api/build` | `{plot, type?}`: Neubau mit `type`, Ausbau ohne |
| `POST /api/demolish` | `{plot}`: Ressourcengebäude abreißen |
| `POST /api/research` | `{tech}` |
| `POST /api/train` | `{building, tier, count}` |
| `POST /api/heal` | `{units: [{type, tier, count}]}` |
| `POST /api/timers/{id}/speedup` | `{item, count}` |
| `POST /api/timers/{id}/cancel` | abbrechen, 50 % Erstattung |
| `POST /api/timers/{id}/help` | Allianzhilfe anfordern |
| `POST /api/items/use` | `{item, count, heroId?, x?, y?}` |
| `GET /api/map` | alle Kartenobjekte und sichtbaren Märsche |
| `POST /api/marches` | `{kind, heroId?, troops, x, y}` |
| `POST /api/marches/{id}/recall` | Rückruf |
| `POST /api/marches/{id}/send-home` | Gastgeber schickt Verstärkung heim |
| `POST /api/rallies` | `{x, y, waitMinutes, heroId, troops}` |
| `POST /api/rallies/{id}/join` | `{heroId, troops}` |
| `POST /api/rallies/{id}/cancel` | nur Starter |
| `GET /api/alliances?query=` | Allianzen suchen |
| `GET /api/alliances/{id}` | Allianzdetails und Mitglieder |
| `POST /api/alliances` | `{name, tag, joinMode, description}` gründen |
| `POST /api/alliances/{id}/join` | beitreten oder anfragen |
| `POST /api/alliance/leave` | austreten |
| `POST /api/alliance/settings` | `{joinMode, description}` |
| `GET /api/alliance/requests` | offene Anfragen |
| `POST /api/alliance/requests/{playerId}/{accept or reject}` | Anfrage bearbeiten |
| `POST /api/alliance/members/{playerId}/{kick, promote, demote, make-leader}` | Mitglied verwalten |
| `POST /api/alliance/disband` | auflösen |
| `GET /api/alliance/help` | offene Hilfeanfragen |
| `POST /api/alliance/help-all` | allen helfen |
| `GET /api/alliance/rallies` | laufende Sammelangriffe |
| `GET /api/alliance/gifts` | offene Geschenke |
| `POST /api/alliance/gifts/{id}/claim` | Geschenk abholen |
| `GET /api/chat/{channel}?before=&limit=50` | Verlauf, `channel` = `world` oder `alliance` |
| `POST /api/chat/{channel}` | `{text}`; Admin-Befehle beginnen mit `/` |
| `POST /api/chat/messages/{id}/report` | Nachricht melden |
| `DELETE /api/chat/messages/{id}` | nur Admin |
| `GET /api/reports?before=&limit=50` | Berichtsliste |
| `GET /api/reports/{id}` | Bericht, markiert ihn als gelesen |
| `DELETE /api/reports/{id}` | Bericht löschen |
| `POST /api/daily/{task}/claim` | Tagesaufgabe abholen |
| `POST /api/achievements/{id}/claim` | Erfolg abholen |
| `GET /api/rankings/{kind}` | `player-power`, `alliance-power`, `zombies` |
| `GET /api/players/{id}` | öffentliches Profil |
| `POST /api/cosmetics/equip` | `{skin?, frame?}` |
| `GET /ws` | WebSocket, Anmeldung per `Authorization`-Header |

Zusätzlich ändert `POST /api/auth/password` mit `{oldPassword, newPassword}` das eigene Passwort und beendet alle anderen Sitzungen. Konten werden nie gelöscht.

**WebSocket-Ereignisse** (Server an Client, JSON mit Feld `type`; Ping alle 30 s)

| type | Inhalt | Reaktion des Clients |
| --- | --- | --- |
| `state_changed` | keiner | `GET /api/state` |
| `map_changed` | geänderte Objekte und Märsche, entfernte IDs | Karte aktualisieren |
| `chat` | neue Nachricht oder Löschung | Chat aktualisieren |
| `report` | ID und Art eines neuen Berichts | Zähler erhöhen |
| `incoming` | Marsch-ID, Angreifer, Art, Ankunft | Banner anzeigen |
| `alliance_changed` | keiner | Allianzdaten neu laden |
| `notice` | Text einer Durchsage | Banner anzeigen |
| `session_ended` | Grund (Abmeldung, Sperre) | zum Login |

Jede WebSocket-Nachricht ist ein JSON-Objekt mit `type` und den Feldern der Tabelle, z. B. `{"type":"incoming","marchId":12,"attacker":"Max","kind":"ATTACK","arriveAt":1790000000000}`.

**Wichtigste DTOs** (in `shared`, alle `@Serializable`, neue Felder immer mit Standardwert)

- `PlayerState`: serverTime, resources (je Ressource Bestand, Kapazität, geschützte Menge, Rate pro Stunde), buildings (plot, type, level), timers (id, kind, target, startedAt, endsAt, helpRequested, helpCount, helpMax), research (tech, level), troops (type, tier, home, wounded), heroes (hero, level, xp, marchId), items (item, count), marches, base (x, y), protectionUntil, shieldUntil, catchupUntil, alliance (id, name, tag, rank oder null), daily (task, progress, target, claimed), achievements (id, completed, claimed), cosmetics (unlocked, skin, frame), maxZombieLevel, unreadReports, power.
- `MapSnapshot`: width, height, objects, marches. Ein `MapObjectDto` hat id, kind, x, y, level, resType, amount, playerId, playerName, allianceTag, skin, shielded, occupiedByMarchId.
- `MarchDto`: id, playerId, kind, state, fromX, fromY, toX, toY, departAt, arriveAt, troopCount, heroId. Bei fremden Märschen fehlt heroId, und troopCount ist nur die Gesamtzahl.
- `ReportDto`: id, kind, createdAt, read, payload. Die Payload ist eine sealed Klasse: `BattleReport`, `ScoutReport`, `GatherReport` oder `SystemReport` mit den Inhalten aus Abschnitt 7 und 11.
- `ChatMessageDto`: id, channel, senderId, senderName, frame, text, createdAt, deleted. `ErrorDto`: code, message.

**Fehlercodes**

| Code | HTTP |
| --- | --- |
| `VALIDATION`, `INVALID_INVITE` | 400 |
| `NOT_ENOUGH_RESOURCES`, `NOT_ENOUGH_ITEMS`, `NOT_ENOUGH_TROOPS`, `QUEUE_FULL`, `REQUIREMENT_NOT_MET`, `MAX_LEVEL`, `HERO_BUSY`, `MARCH_LIMIT`, `MARCH_SIZE`, `TARGET_INVALID`, `TARGET_SHIELDED`, `ZOMBIE_LEVEL_LOCKED`, `ALLIANCE_FULL`, `ALREADY_IN_ALLIANCE`, `NOT_IN_ALLIANCE`, `ALLIANCE_BLOCKED`, `NAME_TAKEN`, `SERVER_FULL` | 409 |
| `UNAUTHORIZED`, auch bei falschem Login, ohne Hinweis, ob Name oder Passwort falsch war | 401 |
| `FORBIDDEN`, `BANNED`, `MUTED` | 403 |
| `NOT_FOUND` | 404 |
| `CLIENT_OUTDATED` | 426 |
| `RATE_LIMITED` | 429 |
| `INTERNAL` | 500 |

**Versionierung**

- Der Client sendet seinen `versionCode`. Liegt er unter `minClientVersion`, antwortet der Server mit 426 und der Client zeigt den Update-Dialog.
- Liegt er unter `latestClientVersion`, zeigt der Client nach dem Login einen Hinweis mit Download-Link.
- `minClientVersion` wird nur angehoben, wenn sich DTOs inkompatibel ändern. Neue Felder in DTOs sind immer optional mit Standardwert, der Client ignoriert unbekannte Felder.

## 16. Betrieb

Der Server läuft als Fat-JAR mit systemd in einem eigenen Incus-Container (Debian 13) hinter dem zentralen Caddy, ohne Docker. Die App wird als APK direkt vom Spielserver verteilt.

**Build unter Linux**

```bash
./gradlew build                                        # alle Module bauen und testen
./gradlew :lwjgl3:run                                  # Desktop-Client starten
./gradlew :server:run --args="--config dev/config.json"  # Server lokal starten
./gradlew :server:buildFatJar                          # ergibt server/build/libs/ruinborn-server.jar
./gradlew :android:assembleRelease                     # ergibt android/build/outputs/apk/release/android-release.apk
./gradlew :tools:packTextures                          # Texturatlas neu erzeugen
```

`buildFatJar` kommt vom Ktor-Gradle-Plugin, der Dateiname wird dort mit `ktor { fatJar { archiveFileName.set("ruinborn-server.jar") } }` festgelegt.

**App-Signierung (einmalig)**

```bash
keytool -genkeypair -v -keystore ~/.android/ruinborn.jks -alias ruinborn -keyalg RSA -keysize 4096 -validity 10000
```

In `~/.gradle/gradle.properties` stehen `ruinborn.storeFile`, `ruinborn.storePassword`, `ruinborn.keyAlias`, `ruinborn.keyPassword` und `ruinborn.serverUrl`. Der Keystore muss gesichert werden: Ohne ihn lassen sich installierte Apps nicht mehr aktualisieren.

**Einrichtung (einmalig)**

Auf dem Incus-Host:

```bash
incus launch images:debian/13 ruinborn -c limits.memory=1GiB
incus config device add ruinborn web proxy listen=tcp:0.0.0.0:8080 connect=tcp:127.0.0.1:8080
```

Im Container:

```bash
apt install -y wget gpg
wget -O - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main" > /etc/apt/sources.list.d/corretto.list
apt update && apt install -y java-25-amazon-corretto-jdk
useradd --system --home /var/lib/ruinborn --shell /usr/sbin/nologin ruinborn
mkdir -p /opt/ruinborn /etc/ruinborn /var/lib/ruinborn/backups /var/lib/ruinborn/apk
chown -R ruinborn:ruinborn /var/lib/ruinborn
```

Port 8080 des Hosts wird in der Firewall des Anbieters nur für die IP des Caddy-Proxys freigegeben.

| Pfad im Container | Inhalt |
| --- | --- |
| `/opt/ruinborn/ruinborn-server.jar` | Programm |
| `/etc/ruinborn/config.json` | Konfiguration |
| `/etc/ruinborn/balance.json` | Balance-Werte |
| `/var/lib/ruinborn/ruinborn.db` | Datenbank |
| `/var/lib/ruinborn/backups/` | tägliche Sicherungen |
| `/var/lib/ruinborn/apk/ruinborn.apk` | aktuelle App |

**systemd-Unit** `/etc/systemd/system/ruinborn.service`

```ini
[Unit]
Description=Ruinborn Game Server
After=network-online.target
Wants=network-online.target

[Service]
User=ruinborn
ExecStart=/usr/bin/java -Xmx512m -jar /opt/ruinborn/ruinborn-server.jar --config /etc/ruinborn/config.json
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Aktivieren mit `systemctl daemon-reload && systemctl enable --now ruinborn`, Logs mit `journalctl -u ruinborn -f`.

**Caddy** (auf dem Ingress-Server)

```
ruinborn.deine-domain.de {
    encode gzip
    reverse_proxy <host-ip>:8080
}
```

`reverse_proxy` leitet WebSockets automatisch weiter. `encode gzip` komprimiert vor allem den Kartenabruf (rund 150 KB JSON).

**config.json**

```json
{
  "bindHost": "127.0.0.1",
  "port": 8080,
  "publicUrl": "https://ruinborn.deine-domain.de",
  "dbPath": "/var/lib/ruinborn/ruinborn.db",
  "balancePath": "/etc/ruinborn/balance.json",
  "backupDir": "/var/lib/ruinborn/backups",
  "backupKeep": 14,
  "apkPath": "/var/lib/ruinborn/apk/ruinborn.apk",
  "inviteCode": "langer-zufaelliger-code",
  "admins": ["deinBenutzername"],
  "maxPlayers": 50,
  "gameSpeed": 1.0,
  "timezone": "Europe/Berlin",
  "dailyResetTime": "04:00",
  "minClientVersion": 1,
  "latestClientVersion": 1,
  "logLevel": "INFO",
  "devMode": false
}
```

`apkUrl` in `/api/version` ist `publicUrl` + `/download/ruinborn.apk`.

**Beim Deployment einzusetzen**

- `ruinborn.deine-domain.de`: deine Domain, gleich in Caddy, `publicUrl` und `ruinborn.serverUrl`.
- `<host-ip>`: IP des Incus-Hosts, wie Caddy ihn erreicht.
- `inviteCode`: zufälliger Code, z. B. aus `openssl rand -hex 16`.
- `admins`: dein Benutzername, mit dem du dich danach normal registrierst.

**Lokale Entwicklung**

- `dev/config.json` liegt im Repository. Sie entspricht der Produktivkonfiguration mit diesen Abweichungen: `bindHost` `0.0.0.0`, `publicUrl` `http://localhost:8080`, Pfade unter `dev/data/`, `balancePath` `dev/balance.json`, `inviteCode` `dev`, `admins` `["admin"]`, `gameSpeed` 20, `logLevel` `DEBUG`, `devMode` true.
- Der Desktop-Client nutzt `http://localhost:8080`, der Android-Emulator `http://10.0.2.2:8080`. Überschreiben geht per Gradle-Property, z. B. `./gradlew :android:installDebug -Pruinborn.serverUrl=http://10.0.2.2:8080`.
- Nur der Debug-Build erlaubt unverschlüsseltes HTTP über `android/src/debug/AndroidManifest.xml` mit `android:usesCleartextTraffic="true"`. Release-Builds sprechen ausschließlich HTTPS.

**Backups**

- Der Server sichert sich täglich um 03:30 selbst mit `VACUUM INTO '/var/lib/ruinborn/backups/ruinborn-JJJJ-MM-TT.db'`. Das ist im laufenden Betrieb konsistent. Die 14 neuesten Sicherungen bleiben erhalten.
- Zusätzlich sichert der Hoster den Server bzw. Incus den Container per Snapshot.
- Wiederherstellen: `systemctl stop ruinborn`, die Sicherung nach `ruinborn.db` kopieren, `ruinborn.db-wal` und `ruinborn.db-shm` löschen, `systemctl start ruinborn`. Überfällige Ereignisse holt der Server beim Start nach.

**Updates**

- Server: neue JAR nach `/opt/ruinborn/` kopieren und `systemctl restart ruinborn`. Migrationen laufen automatisch. Die Pause dauert Sekunden, Timer laufen weiter, weil alles zeitbasiert berechnet wird.
- App: `versionCode` erhöhen, Release-APK bauen, nach `/var/lib/ruinborn/apk/ruinborn.apk` kopieren, `latestClientVersion` (bei inkompatiblen Änderungen auch `minClientVersion`) anpassen, Server neu starten. Freunde erlauben einmalig die Installation aus unbekannten Quellen und installieren über den Link in der App.
- Neue Welt: bei gestopptem Dienst `java -jar ruinborn-server.jar --config /etc/ruinborn/config.json --new-world`. Der Befehl legt erst eine Sicherung an, verlangt die Eingabe `NEUE WELT`, löscht alle Spielstände und behält die Konten. Beim nächsten Login erhält jeder Spieler den Startzustand aus Abschnitt 2.
- Monitoring: `GET /api/health` lässt sich in jedes Uptime-Monitoring einhängen.

## 17. Tests und Balancing-Simulation

Alle Spielregeln liegen im `shared`-Modul und sind ohne Server testbar. Eine Simulation prüft vor der Beta, ob das Spieltempo die Zielwerte trifft.

**Automatische Tests** (`./gradlew test`)

| Modul | Prüfgegenstand | Mittel |
| --- | --- | --- |
| shared | Formeln mit Stichproben aus diesem Dokument, z. B. HQ 10 kostet 10.330 Nahrung und dauert 1.273 s | kotlin-test |
| shared | Ressourcen: Kapazitätsgrenze, Materialisierung, Beute und Kisten über der Kapazität | kotlin-test |
| shared | Kampf: Rechenbeispiel aus Abschnitt 7, gleiche Armeen ergeben gleiche Verluste, Konterbonus, 20-Runden-Grenze, leere Basis | kotlin-test |
| server | Abläufe: Registrierung, Bau, Uhr vorspulen, `settle`; Marsch, Kampf, Bericht, Heimkehr; Sammelangriff mit 3 Teilnehmern; Schutzregeln; Allianzhilfe; doppelte `X-Request-Id` | ktor-server-test-host, temporäre SQLite-Datei, steuerbare Uhr |
| server | Neustart: überfällige Ereignisse werden in richtiger Reihenfolge nachgeholt | Engine zweimal starten |
| server | Migrationen: leere Datenbank bis zur aktuellen Version | neue Datei je Test |

GitHub Actions führt bei jedem Push `./gradlew build` auf `ubuntu-latest` mit Amazon Corretto 25 aus (actions/setup-java mit distribution corretto und java-version 25).

**Balancing-Simulation**

- `./gradlew :server:runSim` startet die echte Engine mit steuerbarer Uhr und simuliert 60 Tage.
- Drei Spielertypen: Gelegenheit (2 Logins pro Tag), Normal (4) und Aktiv (8).
- Bei jedem Login: Belohnungen abholen, Bauwarteschlangen füllen (erst HQ-Voraussetzungen, dann Produktion, dann Militär), Forschung und Ausbildung starten, die höchsten erlaubten Zombies angreifen, freie Märsche sammeln lassen.
- Ausgabe: `sim-result.csv` mit Tag, Spielertyp, HQ-Stufe, Macht und Ressourcenbeständen.
- Zielwerte für den Normal-Spieler: HQ 10 nach 7 bis 10 Tagen, HQ 15 nach 21 bis 28 Tagen, HQ 20 nach 45 bis 60 Tagen. Der Gelegenheitsspieler darf höchstens 30 % langsamer sein.
- Bei Abweichungen werden nur Werte in `balance.json` geändert und die Simulation erneut ausgeführt, bis alle Zielwerte passen.

`runSim` ist ein Gradle-Task vom Typ `JavaExec` im `server`-Modul mit der Hauptklasse `bayern.kickner.ruinborn.server.sim.SimMainKt`. Er nutzt eine temporäre Datenbank und die `balance.json` aus `dev/`.

**Manuelle Tests**

- Lokal: Server mit `gameSpeed` 20 und `devMode` an, dazu mehrere Desktop-Clients mit Testkonten.
- Danach Test auf echten Android-Geräten und zwei Wochen Beta mit Freunden (Meilenstein M6).

**Grenzfälle mit Pflicht-Test**

| Fall | Erwartetes Verhalten | Test |
| --- | --- | --- |
| Server stürzt ab oder startet neu, während Märsche laufen | überfällige Ereignisse werden nach dem Start in Reihenfolge ihrer Fälligkeit verarbeitet, als wäre nichts passiert | Engine zweimal starten |
| Zwei Märsche erreichen dasselbe Ziel in derselben Millisekunde | Verarbeitung nach Marsch-ID; der zweite findet ein verändertes oder ungültiges Ziel | Servertest |
| Client sendet denselben Befehl doppelt (Doppeltipp, Timeout) | genau eine Ausführung, beide Antworten gleich | Idempotenztest |
| Spieler war tagelang offline, mehrere Timer sind fertig, das Lager ist voll | `settle` arbeitet die Timer der Reihe nach ab, Produktion stoppt exakt an der Kapazität | shared-Test |
| Tagesreset am Tag der Zeitumstellung in `Europe/Berlin` | Reset genau einmal um 04:00 Ortszeit, berechnet mit `ZonedDateTime` | steuerbare Uhr an beiden Umstellungstagen |
| Angriff auf eine Basis ohne Truppen und ohne plünderbare Ressourcen | Sieg ohne Kampf, Beute 0, keine Division durch 0 | shared-Test |
| Spieler verlässt die Allianz mit wartendem Sammelangriff-Marsch und stationierter Verstärkung | beide Märsche kehren heim, offene Geschenke verfallen | Servertest |
| Klogger auf Android | nur `logToCustom` wird konfiguriert, keine anderen Ziele wie Loki | manuell auf dem Gerät |

## 18. Meilensteine

v1.0 entsteht in sieben Meilensteinen. Jeder endet mit einem spielbaren, getesteten Stand, und der nächste beginnt erst nach erfülltem Abnahmekriterium.

| Meilenstein | Inhalt | Abnahmekriterium |
| --- | --- | --- |
| M0 Grundgerüst | Projekt per gdx-liftoff (Kotlin, Android, LWJGL3, KTX), Module `shared`, `server`, `tools` ergänzt, `libs.versions.toml`, GitHub Actions, Laden und Prüfen von Konfiguration und `balance.json`, Migrationen, `/api/health`, `/api/version` | `./gradlew build` grün; Server startet mit leerer Datenbank; Desktop-Client zeigt den LoginScreen |
| M1 Konto und Basis | Registrierung, Login, Sitzungen, Engine mit `settle`, Ressourcen, alle Gebäude und Bauplätze, 2 Bauwarteschlangen, Abbrechen, Beschleuniger, BaseScreen mit Platzhaltergrafik, obere Leiste, Timer-Leiste | Bei `gameSpeed` 20 ist HQ 1 bis 5 spielbar; ein Serverneustart verliert nichts; Formeltests grün |
| M2 Militär und Fortschritt | Ausbildung, Lazarett, Heilen, Forschung, Helden, Items, Inventar, Tagesaufgaben, Erfolge, Aufholbonus | Alle zugehörigen Dialoge bedienbar; Tagesreset mit steuerbarer Uhr getestet |
| M3 Karte und PvE | Kartenerzeugung, Spawn, MapScreen, Märsche, Kampf, Zombies, Sammeln, Berichte, Umzug, WebSocket-Ereignisse | Zombies Stufe 1 bis 5 besiegbar; Sammeln mit Heimkehr; Rechenbeispiel aus Abschnitt 7 als Test grün |
| M4 PvP und Allianzen | Angriffe auf Basen und Sammelmärsche, Beute, Aufklärung, alle Schutzmechanismen, Allianzen mit Rängen, Hilfe, Verstärkung, Welt- und Allianzchat | Zwei Testkonten greifen sich gegenseitig an; alle Schildregeln durch Servertests abgedeckt |
| M5 Sammelangriffe und Abschluss | Sammelangriffe, Nester, Allianzgeschenke, Ranglisten, Kosmetik, Admin-Befehle, Audit-Log, Backups, APK-Verteilung, Update-Hinweis, Deployment | Nest Stufe 1 mit 3 Konten besiegt; Sicherung und Wiederherstellung einmal durchgespielt; Server läuft im Incus-Container hinter Caddy |
| M6 Beta und Balancing | Simulation bis zu den Zielwerten, Beta mit 3 bis 5 Freunden über 2 Wochen, Fehlerbehebung | Keine offenen Fehler, die das Spielen blockieren; Simulationsziele erreicht; Ergebnis ist Version 1.0 |

Für jeden Meilenstein entsteht vor Beginn ein eigener Umsetzungsplan mit dem Skill superpowers:writing-plans: Aufgaben, betroffene Dateien, Tests. Dieses Konzept ist dafür die verbindliche Vorlage. Weicht die Umsetzung davon ab, wird das Konzept zuerst angepasst.

Nach v1.0 folgt v1.1 mit Push-Benachrichtigungen über ntfy und UnifiedPush (ohne Firebase), Sound und ersten Events.

## 19. Entscheidungsprotokoll

Jede Frage, die beim Konzept offen war, ist hier entschieden und begründet.

| Frage | Entscheidung | Begründung |
| --- | --- | --- |
| Engine | LibGDX mit KTX | stabil, große Community, Android im Fokus, iOS später über MobiVM möglich |
| Kaufbare kleine Boni | nein, nur Kosmetik | kleine Boni sind der Einstieg in Pay-to-win |
| Premium-Währung | keine | jede Premium-Währung wird zum Wechselkurs für Vorteile |
| Zweite Bauwarteschlange | für alle kostenlos | beim Vorbild ein Bezahlvorteil |
| Ausdauer oder Energie | keine | Marschzeit und Heilkosten begrenzen ausreichend |
| Truppenunterhalt | keiner | KISS; Ressourcen begrenzen die Armee bereits über die Kosten |
| Zufall im Kampf | keiner | nachvollziehbar und exakt testbar |
| Heldenerwerb | feste Freischaltung über HQ | kein Glücksspiel |
| Truppen aufwerten oder entlassen | nicht möglich | weniger Sonderfälle |
| Gebäude während des Ausbaus | voll nutzbar, mit alter Stufe | weniger Sonderfälle |
| Abbrechen von Timern | 50 % Erstattung | verhindert das Verstecken von Ressourcen vor Plünderung |
| Kampfverluste | Lazarett bis voll, Rest tot, überall gleich | eine einzige Regel |
| Zombie-Belohnungen | sofort, ohne Traglast-Grenze | einfacher, PvE fühlt sich lohnend an |
| Kartengröße | 100 × 100, Basis belegt 1 Feld | reicht für 50 Spieler, ganze Karte in einem Abruf |
| Maximale Spielerzahl | 50, in der Konfiguration änderbar | passt zu Kartengröße und Zielgruppe |
| Nebel des Krieges | keiner | einfacher und für Freunde transparent |
| Basislayout | festes Raster aus 4 × 5 Plätzen | KISS, Platzhaltergrafik passt sofort |
| Kommunikation | HTTP für Befehle, WebSocket nur für Hinweise | mit `curl` testbar, robust bei Verbindungsabbrüchen |
| Nebenläufigkeit | ein einziger Schreib-Thread | Spiellogik muss ohnehin der Reihe nach laufen; keine Sperren, keine Race Conditions |
| Datenbank | SQLite im WAL-Modus, kein Cache | Leser blockieren den Schreiber nicht; Last liegt weit unter der Kapazität; eine Datei, einfache Backups |
| Datenbankzugriff | Exposed (nur DSL), eigene SQL-Migrationen | typsicher bei 29 Tabellen; späterer Wechsel zu PostgreSQL wäre nur eine Treiberfrage |
| Datenbankverbindungen | je Transaktion eine neue Verbindung, getrennt nach Schreiben und Lesen | kein Verbindungspool nötig, SQLite öffnet Verbindungen schnell |
| Timer | lazy per `settle`, nur Weltereignisse geplant | wenig Ereignisse, exakte Produktion |
| Engine-Schleife | Wecker-Job sendet `Wakeup` in den Befehlskanal | `select` mit `onTimeout` ist experimentelle API; so gehen keine Befehle verloren |
| Logging | Klogger, SLF4J-Brücke für Ktor | eigene Bibliothek, keine weitere Logging-Bibliothek |
| Passwort-Hashing | gesalzener, iterierter SHA-256 aus KotNexLib, Format mit Versionspräfix | reicht für einen privaten Server; späterer Wechsel beim Login möglich |
| Vergessenes Passwort | Admin-Befehl `/password`, eigene Änderung in den Einstellungen | kein E-Mail-Versand nötig |
| Konten löschen | nie | Spielstände und Berichte bleiben nachvollziehbar |
| HTTP-Client-Engine | OkHttp auf Android, CIO auf dem Desktop | OkHttp ist auf Android der robuste Standard; die Client-API bleibt gleich |
| Ktor-Server-Engine | CIO statt Netty | weniger Abhängigkeiten, HTTP/2 und HTTP/3 werden nicht gebraucht |
| Schriften | beim Build erzeugte Bitmap-Schriften | kein FreeType in der App, gleiche Qualität |
| Push-Benachrichtigungen | erst v1.1 über ntfy | kein Google-Zwang, selbst hostbar |
| Echtgeld | nicht in v1 | privates Projekt |
| Plattform | Android im Hochformat | wie beim Vorbild; iOS erst bei großem Erfolg |
| Sprache | Deutsch, Texte übersetzbar abgelegt | Zielgruppe; weitere Sprachen ohne Umbau |
| Registrierung | nur mit Einladungscode | privater Server |
| Schilde | jederzeit aktivierbar, auch bei laufendem Angriff | Freunde sollen sich nicht gegenseitig vergraulen |
| Beschleuniger bei `gameSpeed` | Wirkung durch `gameSpeed` geteilt | Testserver behält die Proportionen |
| Java | Amazon Corretto 25 als Toolchain und auf dem Server, Bytecode JVM 17 für Android-Module | aktuelles LTS, Standard in deinen Projekten |
| Name und Paket | Ruinborn (Arbeitstitel), `bayern.kickner.ruinborn` | eigener Name ohne Bezug zum Vorbild |
| Grafik | SVG-Platzhalter von Claude, gepackt mit TexturePacker | sofort spielbar, später austauschbar |
| Umsetzungsweg | je Meilenstein ein eigener Plan mit superpowers:writing-plans | kleine, prüfbare Schritte; das Konzept bleibt die Vorlage |

## 20. Änderungen bei der Umsetzung (30.09.2026)

Die Umsetzung folgt diesem Konzept. Wo sie bewusst abweicht oder eine offene Frage entscheiden musste, ist das hier
festgehalten; Begründungen stehen in `docs/DECISIONS.md` (Nummern E-01 bis E-28).

| Abschnitt | Änderung |
| --- | --- |
| 12, 13 | Kotlin-Multiplatform-Projekt: `shared` und neues Modul `client` (HTTP, WebSocket, Zustand) liegen in `commonMain`, vorerst nur mit JVM-Ziel; das Modul `android` ist wie in Abschnitt 13 vorhanden (E-01, E-02). |
| 12, 13 | Android: compileSdk und targetSdk 37 statt der gdx-liftoff-Werte, weil OkHttp 5.5 aus Ktor 3.6 compileSdk ≥ 37 verlangt; kein Shrinking; `:android` wird nur mit vorhandenem Android SDK eingebunden; Zurück-Taste schließt Dialoge bzw. führt von der Karte zur Basis (E-25 bis E-27). |
| 12 | Grafik: isometrische Assets (Rauten 128 × 64) statt flacher SVG-Platzhalter; Basis und Karte werden isometrisch gezeichnet, `base_layout.json` enthält Kacheln; fehlende Sprites mit dem Python-Generator unter `tools/sprites/` ergänzt, `svg2png.sh` entfällt; Kartenzoom 0,5–3,0 (E-05). |
| 12 | Nachrichten werden per Tippen (statt langem Drücken) gemeldet bzw. gelöscht; Desktop-Startoptionen für Profile, automatischen Login und Bildschirmfotos. |
| 13 | Versionen: Gradle 9.8.0, Klogger 0.2.0, KotNexLib 4.4.1, Exposed 1.5.0, KTX 1.14.2-rc2 (neue Gruppe `io.github.quillraven.libktx`) (E-04). Logging in `commonMain` über eine kleine Fassade, verbunden mit Klogger (E-03). |
| 13 | Standard-Balance liegt in `shared/balance/balance.json` und wird eingebettet (E-06); zusätzliche Schlüssel `timers` und `reports`, Konter-Tabelle und Lazarett-Reihenfolge in `combat` (E-08). |
| 3, 14 | **Korrektur:** Ressourcenbestände sind Kommazahlen; Bruchteile verfallen nicht mehr bei jeder Materialisierung, weil sonst bei häufigen Aktionen keine Produktion ankäme (E-07). |
| 13, 14 | Zusätzliches Ereignis `SHIELD_END` (E-09); alle ID-Spalten mit `AUTOINCREMENT` (E-14). |
| 5, 7, 8 | PvP-Erfahrung zählt alle Verluste der Gegenseite (E-10); eine eigene PvP-Aktion beendet jeden laufenden Schild außer dem Inaktivitätsschild (E-11); Aktivität bemisst sich am letzten Abruf des Spielstands (E-17). |
| 9 | Laufzeit eines Sammelangriffs mit dem Logistik-Bonus des Starters; Teilnehmer müssen strikt vor dem Start ankommen (E-18). |
| 13, 15 | Registrierung eigenes Rate-Limit 10/min je IP (E-15); `X-Forwarded-For` zählt nur von Proxys im eigenen Netz (E-28); `X-Request-Id` und `X-Client-Version` optional (E-16); Kartenmärsche in öffentlicher Sicht (E-12); `passwordIterations` konfigurierbar (E-13); `PlayerState` enthält zusätzlich `incoming`, `limits`, `gameSpeed` u. a. |
| 16 | systemd startet Java mit `--enable-native-access=ALL-UNNAMED`; `--new-world` beendet auch alle Sitzungen (E-19). |
| 17 | Simulation vorhanden; mit den Startwerten erreicht der Normal-Spieler HQ 10/15/20 an Tag 9/17/30. Das Feintuning erfolgt in M6 mit Beta-Daten (E-20). |
