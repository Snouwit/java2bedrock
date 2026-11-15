package net.snouli.bedrockbridge.geyser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class BridgeSyncTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String MAPPINGS_A = "{\"items\":{\"format_version\":2,\"items\":{}},\"blocks\":{\"format_version\":1,\"blocks\":{}}}";
    private static final String MAPPINGS_B = "{\"items\":{\"format_version\":2,\"items\":{\"minecraft:paper\":[]}},\"blocks\":{\"format_version\":1,\"blocks\":{}}}";

    @TempDir
    Path dir;
    HttpServer server;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = files.get(exchange.getRequestURI().getPath());
            boolean authorized = "token=abc%2B1".equals(exchange.getRequestURI().getRawQuery());
            int code = !authorized ? 403 : body == null ? 404 : status;
            byte[] response = code == 200 ? body : new byte[0];
            exchange.sendResponseHeaders(code, response.length == 0 ? -1 : response.length);
            if (response.length > 0) {
                exchange.getResponseBody().write(response);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String sha1(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

    private void publish(int version, String pack, String mappings) throws Exception {
        byte[] packBytes = pack.getBytes(StandardCharsets.UTF_8);
        byte[] mappingBytes = mappings.getBytes(StandardCharsets.UTF_8);
        files.put("/pack.mcpack", packBytes);
        files.put("/mappings.json", mappingBytes);
        files.put("/version.json", ("{\"version\":" + version + ",\"pack\":{\"sha1\":\"" + sha1(packBytes)
            + "\"},\"mappings\":{\"sha1\":\"" + sha1(mappingBytes) + "\"}}").getBytes(StandardCharsets.UTF_8));
    }

    private BridgeSync sync(String token) {
        return new BridgeSync(dir.resolve("data"), dir.resolve("custom_mappings"), dir.resolve("gde/Mappings"),
            "http://127.0.0.1:" + server.getAddress().getPort() + "/", token, TIMEOUT);
    }

    @Test
    void downloadsSplitsMappingsAndDetectsChanges() throws Exception {
        publish(1, "pack-one", MAPPINGS_A);
        BridgeSync sync = sync("abc+1");
        assertNull(sync.packFile());

        BridgeSync.Result first = sync.check(TIMEOUT);
        assertTrue(first.packChanged() && first.mappingsChanged());
        assertEquals("pack-one", Files.readString(sync.packFile()));
        JsonObject items = JsonParser.parseString(
            Files.readString(dir.resolve("custom_mappings").resolve(BridgeSync.ITEM_MAPPINGS))).getAsJsonObject();
        JsonObject blocks = JsonParser.parseString(
            Files.readString(dir.resolve("custom_mappings").resolve(BridgeSync.BLOCK_MAPPINGS))).getAsJsonObject();
        assertEquals(2, items.get("format_version").getAsInt());
        assertEquals(1, blocks.get("format_version").getAsInt());

        BridgeSync.Result unchanged = sync.check(TIMEOUT);
        assertFalse(unchanged.packChanged() || unchanged.mappingsChanged());

        // Texture-only update: new pack file, mappings (and therefore the need to restart) unchanged
        Path oldPack = sync.packFile();
        String loadedMappings = sync.mappingsSha1();
        publish(2, "pack-two", MAPPINGS_A);
        BridgeSync.Result textures = sync.check(TIMEOUT);
        assertTrue(textures.packChanged());
        assertFalse(textures.mappingsChanged());
        assertNotEquals(oldPack, sync.packFile());
        assertTrue(Files.exists(oldPack), "alte Datei bleibt fuer laufende Downloads");
        assertEquals(loadedMappings, sync.mappingsSha1());

        // New items: mappings change
        publish(3, "pack-two", MAPPINGS_B);
        BridgeSync.Result mappings = sync.check(TIMEOUT);
        assertFalse(mappings.packChanged());
        assertTrue(mappings.mappingsChanged());
        assertNotEquals(loadedMappings, sync.mappingsSha1());

        // A restart keeps the cache and cleans up old packs
        BridgeSync restarted = sync("abc+1");
        assertEquals(3, restarted.version());
        assertEquals(sync.packFile(), restarted.packFile());
        restarted.deleteOldPacks();
        assertFalse(Files.exists(oldPack));
        assertTrue(Files.exists(restarted.packFile()));
        BridgeSync.Result afterRestart = restarted.check(TIMEOUT);
        assertFalse(afterRestart.packChanged() || afterRestart.mappingsChanged());
    }

    @Test
    void failuresKeepThePreviousVersion() throws Exception {
        publish(1, "pack-one", MAPPINGS_A);
        BridgeSync sync = sync("abc+1");
        sync.check(TIMEOUT);
        Path pack = sync.packFile();
        byte[] itemMappings = Files.readAllBytes(dir.resolve("custom_mappings").resolve(BridgeSync.ITEM_MAPPINGS));

        // version.json announces a pack that does not match what is served (build in progress / corruption)
        publish(2, "pack-two", MAPPINGS_B);
        files.put("/pack.mcpack", "something else".getBytes(StandardCharsets.UTF_8));
        IOException mismatch = assertThrows(IOException.class, () -> sync.check(TIMEOUT));
        assertTrue(mismatch.getMessage().contains("SHA-1"));
        assertEquals(pack, sync.packFile());
        assertEquals("pack-one", Files.readString(sync.packFile()));
        assertArrayEquals(itemMappings, Files.readAllBytes(dir.resolve("custom_mappings").resolve(BridgeSync.ITEM_MAPPINGS)));
        assertEquals(1, sync.version());

        status = 500;
        assertThrows(IOException.class, () -> sync.check(TIMEOUT));
        status = 200;

        IOException forbidden = assertThrows(IOException.class, () -> sync("wrong").check(TIMEOUT));
        assertTrue(forbidden.getMessage().contains("Token"));

        server.stop(0);
        assertThrows(IOException.class, () -> sync.check(TIMEOUT));
        assertEquals("pack-one", Files.readString(sync.packFile()));
    }

    @Test
    void deletedMappingFilesAreRestored() throws Exception {
        publish(1, "pack-one", MAPPINGS_A);
        BridgeSync sync = sync("abc+1");
        sync.check(TIMEOUT);
        Files.delete(dir.resolve("custom_mappings").resolve(BridgeSync.BLOCK_MAPPINGS));
        assertTrue(sync.check(TIMEOUT).mappingsChanged());
        assertTrue(Files.exists(dir.resolve("custom_mappings").resolve(BridgeSync.BLOCK_MAPPINGS)));
    }

    @Test
    void displayEntityMappingsAreWrittenAndRemoved() throws Exception {
        Path file = dir.resolve("gde/Mappings").resolve(BridgeSync.DISPLAY_MAPPINGS);
        String withFurniture = MAPPINGS_A.substring(0, MAPPINGS_A.length() - 1)
            + ",\"display_entities\":\"mappings:\\n  bench:\\n    type: \\\"minecraft:paper\\\"\\n\"}";
        publish(1, "pack-one", withFurniture);
        BridgeSync sync = sync("abc+1");
        sync.check(TIMEOUT);
        assertTrue(sync.hasDisplayEntities());
        assertEquals("mappings:\n  bench:\n    type: \"minecraft:paper\"\n", Files.readString(file));
        assertTrue(sync("abc+1").hasDisplayEntities(), "bleibt nach Neustart bekannt");

        // deleted by hand: restored on the next check
        Files.delete(file);
        assertTrue(sync.check(TIMEOUT).mappingsChanged());
        assertTrue(Files.exists(file));

        publish(2, "pack-one", MAPPINGS_A);
        sync.check(TIMEOUT);
        assertFalse(sync.hasDisplayEntities());
        assertFalse(Files.exists(file));

        // without GeyserDisplayEntity nothing is written outside our own folders
        BridgeSync noExtension = new BridgeSync(dir.resolve("data2"), dir.resolve("custom_mappings2"), null,
            "http://127.0.0.1:" + server.getAddress().getPort(), "abc+1", TIMEOUT);
        publish(3, "pack-one", withFurniture);
        noExtension.check(TIMEOUT);
        assertTrue(noExtension.hasDisplayEntities());
        assertFalse(Files.exists(file));
    }

    @Test
    void configParserHandlesQuotesAndComments() {
        Map<String, String> config = BedrockBridgeExtension.parseConfig(List.of(
            "# Kommentar", "", "url: http://176.57.181.237:25887", "token: \"a:b#c\"",
            "check-interval-seconds: 60   # Sekunden", "notify-permission: 'snouli.bedrock.admin'"));
        assertEquals("http://176.57.181.237:25887", config.get("url"));
        assertEquals("a:b#c", config.get("token"));
        assertEquals("60", config.get("check-interval-seconds"));
        assertEquals("snouli.bedrock.admin", config.get("notify-permission"));
    }
}
