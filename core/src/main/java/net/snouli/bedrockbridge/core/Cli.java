package net.snouli.bedrockbridge.core;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts a pack without a server: {@code Cli <generated.zip> <output-dir> [pack name]}.
 * Uses only the zip (no ItemsAdder API data), like the plugin does when ItemsAdder is missing.
 */
public final class Cli {
    private Cli() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Aufruf: Cli <generated.zip> <ausgabe-ordner> [Paketname]");
            System.exit(2);
        }
        Path output = Path.of(args[1]);
        String name = args.length > 2 ? args[2] : "Snouli.net";
        BuildInput defaults = BuildInput.packOnly(Path.of(args[0]), name);
        BuildInput input = new BuildInput(defaults.javaPack(), name, defaults.skipNamespaces(), defaults.items(),
            defaults.fontImages(), defaults.maxGlyphSize(), System.out::println);

        BuildOutput built = PackBuilder.build(input);
        DistStore store = new DistStore(output);
        DistStore.Result result = store.publish(built, name, store.load());
        Files.writeString(output.resolve("report.txt"), built.report().render("SnouliBedrockBridge - Bericht"));
        built.report().summaryLines().forEach(System.out::println);
        System.out.println("Version " + result.published().version() + (result.changed() ? " (neu)" : " (unveraendert)")
            + " -> " + output.toAbsolutePath());
    }
}
