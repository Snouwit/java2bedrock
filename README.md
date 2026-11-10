# SnouliBedrockBridge

Macht ItemsAdder-Inhalte für Bedrock-Spieler (Geyser) sichtbar – automatisch nach jedem `/iazip`.

| Teil | Läuft auf | Datei |
|---|---|---|
| **SnouliBedrockBridge** (Paper-Plugin) | Hub (Paper, Java 21+) | `hub-plugin/target/SnouliBedrockBridge-1.0.0.jar` |
| **SnouliBedrockBridge-Geyser** (Geyser-Extension) | Proxy (Geyser-BungeeCord 2.12.x) | `geyser-extension/target/SnouliBedrockBridge-Geyser-1.0.0.jar` |

Stand: **Phase 1** – Font-Bilder (Rang-Icons), flache 2D-Items, einfache Würfel-Blöcke.
Möbel und 3D-Modelle (Phase 2) werden übersprungen und im Bericht aufgelistet.

## Ablauf

1. Admin macht `/iazip` auf dem Hub. ItemsAdder schreibt `plugins/ItemsAdder/output/generated.zip`.
2. Das Hub-Plugin bemerkt die neue Datei (es prüft alle 2 s Grösse und Änderungszeit und wartet, bis
   die Datei fertig geschrieben ist), liest das Paket und die ItemsAdder-API und baut asynchron:
   - `pack.mcpack` – Bedrock-Ressourcenpaket (gleiche UUID, neue Version bei jeder Änderung)
   - `mappings.json` – Geyser-Mappings (Items im Format v2, Blöcke im Format v1)
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
Icon. 3D-Modelle und Möbel werden übersprungen. Getragene Rüstungs-Texturen sind noch nicht dabei.

**Blöcke.** Zustände von `note_block`, `brown_mushroom_block`, `red_mushroom_block`, `mushroom_stem` und
`chorus_plant`, deren Modell ein voller Würfel mit 1–6 Texturen ist. Alles andere wird übersprungen.

Es werden nur Texturen aus dem ItemsAdder-Paket verwendet, nie Mojang-Texturen. Items/Blöcke, die auf
Vanilla-Texturen zeigen, werden übersprungen.

ItemsAdders Paket-Schutz (absichtlich falsche ZIP- und PNG-Prüfsummen) wird gelesen.

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
