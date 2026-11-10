package net.snouli.bedrockbridge.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

class PublishTest {

    private static JsonObject manifest(byte[] mcpack) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(mcpack))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().equals("manifest.json")) {
                    return JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
            }
        }
        throw new IOException("manifest.json fehlt");
    }

    @Test
    void versionOnlyChangesWhenContentChanges(@TempDir Path dir) throws IOException {
        Path zip = TestPack.standard().write(dir.resolve("a.zip"));
        DistStore store = new DistStore(dir.resolve("dist"));
        assertNull(store.load());

        DistStore.Result first = store.publish(PackBuilder.build(BuildInput.packOnly(zip, "Test")), "Test", null);
        assertTrue(first.packChanged() && first.mappingsChanged());
        assertEquals(1, first.published().version());
        assertArrayEquals(new int[] {1, 0, 1}, first.published().packVersion());

        // Same input again: nothing is republished
        DistStore.Result same = store.publish(PackBuilder.build(BuildInput.packOnly(zip, "Test")), "Test", first.published());
        assertFalse(same.changed());
        assertSame(first.published(), same.published());

        // Only a texture changes: new pack version with the same UUID, mappings untouched
        Path changed = TestPack.standard()
            .png("assets/shop/textures/coin.png", TestPack.solid(16, 16, 0xFF123456)).write(dir.resolve("b.zip"));
        DistStore.Result second = store.publish(PackBuilder.build(BuildInput.packOnly(changed, "Test")), "Test", first.published());
        assertTrue(second.packChanged());
        assertFalse(second.mappingsChanged());
        assertEquals(2, second.published().version());
        assertNotEquals(first.published().packSha1(), second.published().packSha1());
        JsonObject firstHeader = manifest(first.published().pack()).getAsJsonObject("header");
        JsonObject secondHeader = manifest(second.published().pack()).getAsJsonObject("header");
        assertEquals(firstHeader.get("uuid"), secondHeader.get("uuid"));
        assertEquals("[1,0,2]", secondHeader.get("version").toString());

        // A restart reads the same state back from disk
        Published reloaded = new DistStore(dir.resolve("dist")).load();
        assertNotNull(reloaded);
        assertEquals(2, reloaded.version());
        assertEquals(second.published().packSha1(), reloaded.packSha1());
        assertEquals(second.published().items(), reloaded.items());

        JsonObject version = JsonParser.parseString(Files.readString(dir.resolve("dist/version.json"))).getAsJsonObject();
        assertEquals(Hashes.sha1(Files.readAllBytes(dir.resolve("dist/pack.mcpack"))),
            version.getAsJsonObject("pack").get("sha1").getAsString());
        assertEquals(Hashes.sha1(Files.readAllBytes(dir.resolve("dist/mappings.json"))),
            version.getAsJsonObject("mappings").get("sha1").getAsString());
        assertTrue(version.has("built_at"));
    }

    @Test
    void damagedDistIsNotLoaded(@TempDir Path dir) throws IOException {
        Path zip = TestPack.standard().write(dir.resolve("a.zip"));
        DistStore store = new DistStore(dir.resolve("dist"));
        store.publish(PackBuilder.build(BuildInput.packOnly(zip, "Test")), "Test", null);
        Files.writeString(dir.resolve("dist/pack.mcpack"), "kaputt");
        assertNull(store.load());
    }

    @Test
    void httpServerServesOnlyWithToken(@TempDir Path dir) throws Exception {
        Path zip = TestPack.standard().write(dir.resolve("a.zip"));
        AtomicReference<Published> published = new AtomicReference<>();
        BridgeHttpServer server = new BridgeHttpServer("127.0.0.1", 0, "geheim&1", published::get);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String token = "?token=geheim%261";

            assertEquals(503, get(client, base + "/version.json" + token).statusCode());

            Published current = new DistStore(dir.resolve("dist"))
                .publish(PackBuilder.build(BuildInput.packOnly(zip, "Test")), "Test", null).published();
            published.set(current);

            assertArrayEquals(current.versionJson(), get(client, base + "/version.json" + token).body());
            assertArrayEquals(current.pack(), get(client, base + "/pack.mcpack" + token).body());
            assertArrayEquals(current.mappings(), get(client, base + "/mappings.json" + token).body());

            assertEquals(403, get(client, base + "/version.json").statusCode());
            assertEquals(403, get(client, base + "/pack.mcpack?token=falsch").statusCode());
            assertEquals(403, get(client, base + "/pack.mcpack?token=geheim").statusCode());
            assertEquals(404, get(client, base + "/state.json" + token).statusCode());
            assertEquals(404, get(client, base + "/../config.yml" + token).statusCode());
            HttpResponse<byte[]> post = client.send(HttpRequest.newBuilder(URI.create(base + "/version.json" + token))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, post.statusCode());
        } finally {
            server.stop();
        }
    }

    private static HttpResponse<byte[]> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
