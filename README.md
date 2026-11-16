# SnouliBedrockBridge

Macht ItemsAdder-Inhalte für Bedrock-Spieler (Geyser) sichtbar – automatisch nach jedem `/iazip`.

| Teil | Läuft auf | Datei |
|---|---|---|
| **SnouliBedrockBridge** (Paper-Plugin) | Hub (Paper, Java 21+) | `hub-plugin/target/SnouliBedrockBridge-1.0.0.jar` |
| **SnouliBedrockBridge-Geyser** (Geyser-Extension) | Proxy (Geyser-BungeeCord 2.12.x) | `geyser-extension/target/SnouliBedrockBridge-Geyser-1.0.0.jar` |

Stand: **Phase 1** (Font-Bilder/Rang-Icons, flache 2D-Items, einfache Würfel-Blöcke),
**Phase 2** (3D-Modelle und Möbel) und **Phase 3** (Rüstungen, Item-Eigenschaften). Für Möbel an Item-Displays braucht der Proxy zusätzlich die
Community-Extension **GeyserDisplayEntity**, siehe [Möbel](#möbel-und-3d-modelle-phase-2).

## Ablauf

1. Admin macht `/iazip` auf dem Hub. ItemsAdder schreibt `plugins/ItemsAdder/output/generated.zip`.
2. Das Hub-Plugin bemerkt die neue Datei (es prüft alle 2 s Grösse und Änderungszeit und wartet, bis
   die Datei fertig geschrieben ist), liest das Paket und die ItemsAdder-API und baut asynchron:
   - `pack.mcpack` – Bedrock-Ressourcenpaket (gleiche UUID, neue Version bei jeder Änderung)
   - `mappings.json` – Geyser-Mappings (Items im Format v2, Blöcke im Format v1) und die
     Möbel-Liste für GeyserDisplayEntity
   - `version.json` – Versionsnummer, Zeitstempel, SHA-1 beider Dateien
   - `report.txt` – was umgewandelt und was warum übersprungen wurde
3. Der eingebaute HTTP-Server liefert die drei Dateien aus (nur GET, nur mit Token).
4. Die Geyser-Extension fragt alle 60 s `version.json` ab und lädt Änderungen herunter.
   - **Paket:** bekommt jeder Bedrock-Spieler beim Join → neue Texturen ohne Proxy-Neustart.
   - **Mappings:** landen in Geysers `custom_mappings/`. Geyser liest sie nur beim Start. Haben sie sich
     geändert, steht im Proxy-Log „Neue Bedrock-Items - Proxy-Neustart noetig“; Spieler mit der Permission
     `snouli.bedrock.admin` bekommen die Meldung im Chat (auf dem Hub alle, auf dem Proxy Bedrock-Spieler).
     Es gibt keinen automatischen Neustart.

Schlägt ein Build fehl, bleibt die alte Version online. Ändert sich am Inhalt nichts, gibt es keine neue Version.

## Installation

### 1. Hub (176.57.181.237)

1. `SnouliBedrockBridge-1.0.0.jar` nach `plugins/` kopieren, Server starten.
2. In G-Portal muss Port **25887** (TCP) frei/erreichbar sein (25886 nutzt der ItemsAdder-Self-Host).
3. Beim ersten Start erzeugt das Plugin ein Zufalls-Token. Es steht danach in
   `plugins/SnouliBedrockBridge/config.yml` unter `http.token` – diesen Wert für den Proxy kopieren.
4. `/iazip` ausführen (oder `/bedrockpack build`). Danach `/bedrockpack status`.

```yaml
# plugins/SnouliBedrockBridge/config.yml
http:
  bind: 0.0.0.0
  port: 25887
  token: "LANGER-ZUFALLSWERT"   # wird beim ersten Start ersetzt
auto-build-on-iazip: true
pack-name: "Snouli.net"
skip-namespaces: []             # z. B. [tutorial]
itemsadder-pack: plugins/ItemsAdder/output/generated.zip
max-glyph-size: 64              # grössere Font-Bilder gelten als GUI-Bild
models-3d:
  enabled: true
  display-context: head         # display-Eintrag des Modells für Item-Displays (oder none)
  display-rotation: [0, 0, 0]   # Korrektur, falls Möbel auf Bedrock verdreht stehen
  display-translation: [0, 0, 0]
  per-item: {}                  # Verschiebung pro Möbel, z. B. park_bench: [0, 1.5, 0]
armor:
  enabled: true                 # Rüstungsmodell am Körper für eigene Rüstungen
item-components: true           # Stapelgrösse, Haltbarkeit, Essen, Glint ... übernehmen
```

### 2. Proxy (176.57.168.151)

1. `SnouliBedrockBridge-Geyser-1.0.0.jar` nach `plugins/Geyser-BungeeCord/extensions/` kopieren, Proxy starten.
2. `plugins/Geyser-BungeeCord/extensions/snoulibedrockbridge/config.yml` bearbeiten:

   ```yaml
   url: http://176.57.181.237:25887
   token: <http.token vom Hub>
   check-interval-seconds: 60
   notify-permission: snouli.bedrock.admin
   ```
3. In der Geyser-Config: `force-resource-packs: true`, `enable-integrated-pack: true` und
   Custom-Inhalte eingeschaltet lassen (`enable-custom-content: true`, in älteren Configs `add-non-bedrock-items`).
4. Proxy neu starten. Im Log steht „Hub-Version N heruntergeladen“.

Die Extension schreibt zwei Dateien nach `plugins/Geyser-BungeeCord/custom_mappings/`
(`snouli_bedrock_bridge_items.json`, `snouli_bedrock_bridge_blocks.json`) – nicht von Hand bearbeiten.
Wer vorher Rainbow-Mappings für dieselben Items dort liegen hat, muss sie entfernen (sonst doppelt).

## Befehle (Hub, Permission `snouli.bedrock.admin`)

| Befehl | Wirkung |
|---|---|
| `/bedrockpack build` | sofort neu bauen (ohne `/iazip`) |
| `/bedrockpack status` | Version, Anzahl Glyphen/Items/Blöcke, HTTP-Server, letzter Fehler |
| `/bedrockpack report` | Kurzfassung des Berichts im Chat (voll: `plugins/SnouliBedrockBridge/report.txt`) |

## Was umgewandelt wird

**Font-Bilder.** Alle `bitmap`-Zeichen aus `assets/minecraft/font/default.json` im Bereich U+E000–U+F8FF
werden an dieselbe Stelle in `font/glyph_XX.png` gezeichnet (16×16 Raster). Grenzen von Bedrock:

- Ein Glyph ist **quadratisch** und eine Textzeile hoch. Breite Bilder (z. B. ein 41×9-Rang-Schild) werden
  deshalb klein. Quadratische Icons (16×16) bleiben gross. Der Bericht warnt bei jedem breiten Bild.
- Keine negativen Abstände und kein vertikaler Versatz: ItemsAdders Abstands-Zeichen (U+F801 …) und
  GUI-Hintergründe werden nicht umgewandelt.
- ItemsAdder vergibt Zeichen ab U+E000. `glyph_E0.png`/`glyph_E1.png` gibt es in Bedrock schon
  (Tasten-Symbole für Controller/Touch). Unser Sheet ersetzt sie; diese Bedrock-Symbole fehlen dann.
- Zeichen ausserhalb E000–F8FF: Warnung im Log und im Bericht.

**Items.** Items mit Custom-Model-Data (aus `assets/minecraft/items/*.json` bzw. alten `overrides`) und Items
mit `minecraft:item_model`-Component (über die ItemsAdder-API). Flache Modelle (`item/generated`,
`item/handheld`) bekommen ihr Icon; mehrere Ebenen werden zusammengelegt. Block-Items bekommen ein flaches
Icon. Rüstungen und Item-Eigenschaften: siehe [Phase 3](#rüstungen-und-item-eigenschaften-phase-3).

**Blöcke.** Zustände von `note_block`, `brown_mushroom_block`, `red_mushroom_block`, `mushroom_stem` und
`chorus_plant`, deren Modell ein voller Würfel mit 1–6 Texturen ist. Alles andere wird übersprungen.

Es werden nur Texturen aus dem ItemsAdder-Paket kopiert, nie Mojang-Texturen. 2D-Items und Blöcke, die auf
Vanilla-Texturen zeigen, werden übersprungen; 3D-Modelle verweisen stattdessen auf die Textur, die Bedrock
selbst mitbringt.

ItemsAdders Paket-Schutz (absichtlich falsche ZIP- und PNG-Prüfsummen) wird gelesen.

## Möbel und 3D-Modelle (Phase 2)

Jedes 3D-Item-Modell (Java-Modell mit `elements`) wird zu einem Bedrock-Custom-Item mit **Attachable**:
Geometrie aus den Würfeln des Modells, dazu Animationen für Hand, Kopf und Item-Display. Im Inventar
erscheint eine vereinfachte Vorschau als Icon.

| Wo das Modell erscheint | Was es braucht |
|---|---|
| Spieler hält das Item | nichts weiter |
| Möbel auf einem Rüstungsständer (Kopf) | nichts weiter |
| Möbel an einem **Item-Display** (ItemsAdder-Standard) | **GeyserDisplayEntity** auf dem Proxy |

Geyser zeigt Item-Displays von sich aus nicht an, und über die offizielle Geyser-API lassen sie sich nicht
ersetzen (Geyser verwirft sie vor dem Spawn-Event). GeyserDisplayEntity
(<https://github.com/GeyserExtensionists/GeyserDisplayEntity>, AGPL-3.0) löst das: sie spawnt ein Bedrock-Entity
`geyser:item_display`, das das Item trägt, und überträgt Position, Drehung und Skalierung. Wir liefern dazu:

- die Client-Definition von `geyser:item_display` im Bedrock-Paket (passend zu unseren Attachables),
- `Mappings/snouli_bedrock_bridge.yml` im Ordner der Extension (schreibt unsere Geyser-Extension automatisch).

**Installation:** `GeyserDisplayEntity-<version>.jar` nach `plugins/Geyser-BungeeCord/extensions/` legen, Proxy
starten, dann Proxy **noch einmal** neu starten (beim ersten Start gibt es die Mapping-Datei evtl. noch nicht).
In `extensions/geyserdisplayentity/config.yml` muss `hand: false` bleiben (Standard). Kein zweites Paket mit
einer eigenen `geyser:item_display`-Definition verwenden.

**Texturen:** Mehrere Texturen pro Modell sind erlaubt. Vanilla-Texturen (z. B. `block/spruce_planks`) werden
auf den Bedrock-Namen umgerechnet (`textures/blocks/planks_spruce`) und nicht kopiert. Unbekannte Namen landen
im Bericht.

**Grenzen:** nur Würfel-Elemente, Drehung um eine Achse pro Element; `rescale` und Tint werden ignoriert.

**Wenn Möbel falsch stehen** (verdreht, zu hoch/tief): `models-3d.display-rotation` bzw.
`display-translation` in der Hub-Config anpassen, `/bedrockpack build`, Proxy neu starten. Zeigt ItemsAdder
das Möbel mit einem anderen Display-Kontext, `display-context` anpassen (`none` = Modell ohne Transformation).

**Licht und Sitzen:** Licht kommt von ItemsAdders Licht-Block und ist auf Bedrock von selbst da. Zum Sitzen
setzt ItemsAdder den Spieler auf ein unsichtbares Entity; das übersetzt Geyser selbst – bitte testen.

## Rüstungen und Item-Eigenschaften (Phase 3)

### Rüstungen

Jedes eigene Rüstungsteil bekommt ein Bedrock-**Attachable**: am Körper Bedrocks eigene Rüstungs-Geometrie
(`geometry.humanoid.armor.helmet` / `.chestplate` / `.leggings` / `.boots`) mit der Textur aus dem
ItemsAdder-Paket, in der Hand das Icon. Erkannt werden zwei Arten:

| Art | Woran erkannt | Textur |
|---|---|---|
| **Equipment-Asset** (ab 1.21.2) | `equippable`-Component mit `asset_id` | `assets/<ns>/equipment/<name>.json` → `textures/entity/equipment/humanoid[_leggings]/` |
| **Gefärbte Leder-Rüstung** (ItemsAdder-Shader) | `leather_*` + `dyed_color` | 64×32-Feld im Streifen `…/equipment/humanoid[_leggings]/leather.png`, dessen erstes Pixel die Farbe trägt |

Beide Texturen haben dasselbe 64×32-Layout wie Bedrock-Rüstung und werden unverändert übernommen.
Helm, Brust und Schuhe nutzen die Ebene `humanoid`, die Hose `humanoid_leggings`.

Ins Geyser-Mapping kommen der Slot (`equippable`), die Rüstungspunkte (`protection_value`, aus dem
Attribut `armor` des Items) und die Haltbarkeit. Ohne eigene Angaben gelten die Werte des Basis-Items.

Die Zuordnung Item → Textur braucht die ItemsAdder-Daten (Farbe bzw. Asset), läuft also nur auf dem Server
vollständig. Ohne diese Daten wird ein Leder-Set nur zugeordnet, wenn das Paket genau eines enthält.

**Nicht übernommen:** die Färbung färbbarer Ebenen (Bericht-Eintrag), weitere Textur-Ebenen eines
Equipment-Assets, Rüstungs-Zähigkeit und andere Attribute (die rechnet der Java-Server).

### Item-Eigenschaften

Geyser übernimmt die Eigenschaften des Basis-Items von selbst (ein Custom-Item auf `diamond_sword` bleibt ein
Schwert mit dessen Abbau- und Werkzeug-Typ). Zusätzlich gibt die Bridge weiter, was das ItemsAdder-Item
**abweichend** setzt:

| Java-Component | Bedrock |
|---|---|
| `max_stack_size` | Stapelgrösse (1–99) |
| `max_damage` | Haltbarkeit (setzt die Stapelgrösse auf 1) |
| `food`, `consumable` | essbar/trinkbar mit Sättigung und Dauer |
| `enchantment_glint_override` | Verzauberungs-Schimmer |
| `enchantable` | Verzauberbarkeit |
| `equippable.slot`, Attribut `armor` | Rüstungs-Slot und Rüstungspunkte |
| Modell mit `item/handheld` | `display_handheld` (wird wie ein Werkzeug gehalten) |

Anzeigenamen: Hex-Farben (`§x§R§R§G§G§B§B`, `&#RRGGBB`) werden zur nächsten der 16 Bedrock-Farben.

Der Bericht nennt pro Item, was übernommen wurde (`[2D; Stapel 16, Essen 6, trinkbar]`), und warnt, wenn ein
Item Dinge setzt, die Bedrock nicht erfährt (Angriffsschaden und andere Attribute, eigene Werkzeug-Regeln,
Abklingzeit). Diese Werte rechnet weiterhin der Java-Server; nur die Anzeige auf Bedrock fehlt.

### Testen

1. `/iazip` oder `/bedrockpack build` auf dem Hub, `/bedrockpack report` zeigt die Kategorie „Ruestungen“.
2. Proxy neu starten (neue Items brauchen den Neustart).
3. Der Bedrock-Spieler muss **den Server verlassen und neu joinen**, sonst behält Bedrock das alte Paket.

## Bauen

Benötigt JDK 21+ und Maven. Repos: Maven Central, `repo.papermc.io`, `repo.opencollab.dev`.

```bash
mvn package
```

Ein Paket ohne Server umwandeln (nur aus der ZIP, ohne ItemsAdder-Namen):

```bash
mvn -q -pl core dependency:build-classpath -Dmdep.outputFile=cp.txt
```

```bash
java -cp core/target/classes:$(cat cp.txt) net.snouli.bedrockbridge.core.Cli generated.zip ausgabe/
```

## Aufbau

- `core/` – Umwandlung, Versionierung, HTTP-Server (kein Bukkit, mit Tests)
- `hub-plugin/` – Paper-Plugin: ItemsAdder-API, Befehle, Datei-Überwachung
- `geyser-extension/` – Abgleich mit dem Hub, Paket an Bedrock-Spieler, Mappings für Geyser
