package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// What a pack does with a plugin an instance installed from a file.
///
/// A profile records a local installation as the path it was given, and a path on one machine is not
/// something another machine can install from. A pack therefore carries the files — the tarball the
/// instance keeps, or the directory the profile installed from — and puts them back into the next
/// instance's own plugin directory, where they are a local installation like any other. These pin the
/// two halves of that: finding the files, and writing them out again.
class DshPluginBundleTest {

    @Test
    void theTarballAProfileNamesIsWhatTravels(@TempDir Path directory) throws Exception {
        Path instance = directory.resolve("instance");
        Path plugins = instance.resolve(DshLocalPlugins.DIRECTORY);
        Files.createDirectories(plugins);
        Path tarball = plugins.resolve("dsh-secret-1.0.0.tgz");
        Files.write(tarball, pack("dsh-secret", "1.0.0"));

        Path profile = instance.resolve("home/profiles/web");
        Files.createDirectories(profile);

        DshPluginBundle.Payload payload = DshPluginBundle.locate(profile, "dsh-secret",
                "file:" + tarball);

        assertNotNull(payload, "the file the profile installed from is still there");
        assertEquals("1.0.0", payload.version(), "the version comes from the package, not the path");
        assertEquals(tarball, payload.archive());
        assertNull(payload.directory());
    }

    @Test
    void aPluginInstalledFromADirectoryTravelsAsThatDirectory(@TempDir Path directory) throws Exception {
        Path profile = directory.resolve("profiles/web");
        Path installed = profile.resolve("node_modules/dsh-fork");
        Files.createDirectories(installed.resolve("lib"));
        Files.writeString(installed.resolve("package.json"), """
                {"name":"dsh-fork","version":"2.1.0"}
                """);
        Files.writeString(installed.resolve("lib/index.js"), "export const x = 1;\n");
        // A dependency tree is not part of the plugin: the other machine resolves it from the
        // registry, and carrying it would put a second copy of every dependency in the pack.
        Files.createDirectories(installed.resolve("node_modules/left-pad"));
        Files.writeString(installed.resolve("node_modules/left-pad/index.js"), "module.exports = 1;\n");

        DshPluginBundle.Payload payload = DshPluginBundle.locate(profile, "dsh-fork", "link:../dsh-fork");

        assertNotNull(payload, "a link is a directory, and the profile's own copy of it is what travels");
        assertEquals("2.1.0", payload.version());
        assertNull(payload.archive());
        assertEquals(installed, payload.directory());
    }

    @Test
    void aPackedPluginComesBackOutOfThePackWhereItWentIn(@TempDir Path directory) throws Exception {
        Path tarball = directory.resolve("dsh-secret-1.0.0.tgz");
        Files.write(tarball, pack("dsh-secret", "1.0.0"));

        Path pack = directory.resolve("pack.hdslp");
        DshPluginBundle.Payload payload =
                new DshPluginBundle.Payload("dsh-secret", "1.0.0", tarball, null);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            DshPluginBundle.writeInto(zip, payload, null);
        }

        assertEquals(List.of("plugins/dsh-secret-1.0.0.tgz"), DshPluginBundle.carried(pack));

        Path plugins = directory.resolve("next-instance/plugins");
        Path released = DshPluginBundle.release(pack, plugins, "dsh-secret", "1.0.0");

        assertNotNull(released, "the pack holds the files, so they are put back");
        assertTrue(Files.isRegularFile(released), "the instance keeps the plugin file itself");
        assertEquals(-1, Files.mismatch(tarball, released), "and it is the file that travelled");
        assertTrue(released.isAbsolute(),
                "the profile records this path, so it has to be one that resolves from anywhere");
    }

    @Test
    void aDirectoryComesBackOutWithItsFilesAndWithoutItsDependencies(@TempDir Path directory)
            throws Exception {
        Path source = directory.resolve("dsh-fork");
        Files.createDirectories(source.resolve("lib"));
        Files.createDirectories(source.resolve("node_modules/left-pad"));
        Files.writeString(source.resolve("package.json"), """
                {"name":"dsh-fork","version":"2.1.0"}
                """);
        Files.writeString(source.resolve("lib/index.js"), "export const x = 1;\n");
        Files.writeString(source.resolve("node_modules/left-pad/index.js"), "module.exports = 1;\n");

        Path pack = directory.resolve("pack.hdslp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            DshPluginBundle.writeInto(zip, new DshPluginBundle.Payload("dsh-fork", "2.1.0", null, source),
                    null);
        }

        List<String> carried = DshPluginBundle.carried(pack);
        assertTrue(carried.contains("plugins/dsh-fork-2.1.0/lib/index.js"), carried.toString());
        assertTrue(carried.contains("plugins/dsh-fork-2.1.0/package.json"),
                "the manifest makes the directory a package, and travels with it");
        assertFalse(carried.stream().anyMatch(name -> name.contains("node_modules")),
                "a dependency tree is resolved on the other machine, not carried: " + carried);

        Path released = DshPluginBundle.release(pack, directory.resolve("next/plugins"), "dsh-fork", "2.1.0");

        assertNotNull(released);
        assertTrue(Files.isRegularFile(released.resolve("package.json")), "the package is whole");
        assertEquals("export const x = 1;\n", Files.readString(released.resolve("lib/index.js")));
        assertFalse(Files.exists(released.resolve("node_modules")), "and its dependencies are not here");
    }

    @Test
    void aPackThatCarriesNothingSaysSoRatherThanPretending(@TempDir Path directory) throws Exception {
        Path pack = directory.resolve("empty.hdslp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertTrue(DshPluginBundle.carried(pack).isEmpty());
        assertNull(DshPluginBundle.release(pack, directory.resolve("plugins"), "dsh-secret", "1.0.0"),
                "a pack written before packs carried plugins holds nothing to put back");
    }

    @Test
    void aCarriedPluginIsDeclaredByThePathItWasPutBackAt(@TempDir Path directory) {
        Path released = directory.resolve("instance/plugins/dsh-secret-1.0.0.tgz");
        DshModpacks.Manifest pack = manifest(new DshModpacks.Plugin("dsh-secret", "file:/made/elsewhere.tgz",
                true, true, true));

        Map<String, String> dependencies = DshModpacks.dependenciesOf(pack, Map.of("dsh-secret", released));

        assertEquals(released.toString(), dependencies.get("dsh-secret"),
                "the path from the machine the pack was made on is what this replaces");
        assertEquals(List.of("dsh-secret"), DshModpacks.listsBootable(pack, json(dependencies)),
                "the bundle boots, because the profile now declares the package");
    }

    @Test
    void aLocalPluginThePackDoesNotCarryIsLeftOutOfBothLists(@TempDir Path directory) {
        DshModpacks.Manifest pack = manifest(new DshModpacks.Plugin("dsh-secret", "file:/their/path.tgz",
                true, true, false));

        Map<String, String> dependencies = DshModpacks.dependenciesOf(pack, Map.of());

        assertTrue(dependencies.isEmpty(),
                "a path from another machine would make every later operation on the profile fail");
        assertTrue(DshModpacks.listsBootable(pack, json(dependencies)).isEmpty(),
                "and a bundle the profile does not have stops the instance from starting");
    }

    @Test
    void aBundleThePackDoesNotNameAsAPluginIsKept(@TempDir Path directory) {
        DshModpacks.Manifest pack = new DshModpacks.Manifest(DshModpacks.FORMAT, DshModpacks.FORMAT_VERSION,
                "", "x", "v0.1.7", null, "web", List.of(), List.of("@deepseek-ai/dsh-base"), false,
                "", "1.0", "", "", "", "", 0, List.of(), List.of(), List.of());

        assertEquals(List.of("@deepseek-ai/dsh-base"), DshModpacks.listsBootable(pack, json(Map.of())),
                "the harness's own bundles are not dependencies of the profile");
    }

    private static DshModpacks.Manifest manifest(DshModpacks.Plugin plugin) {
        return new DshModpacks.Manifest(DshModpacks.FORMAT, DshModpacks.FORMAT_VERSION, "", "x", "v0.1.7",
                null, "web", List.of(plugin), List.of(plugin.name()), true,
                "", "1.0", "", "", "", "", 0, List.of(), List.of(), List.of());
    }

    private static com.google.gson.JsonObject json(Map<String, String> values) {
        com.google.gson.JsonObject object = new com.google.gson.JsonObject();
        values.forEach(object::addProperty);
        return object;
    }

    /// Builds a gzipped tar holding a `package/package.json`, the way `pnpm pack` writes one.
    ///
    /// @param name    the package name
    /// @param version the package version
    /// @return the archive's bytes
    private static byte[] pack(String name, String version) throws Exception {
        String manifest = """
                {"name":"%s","version":"%s"}
                """.formatted(name, version);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(entry("package/package.json", manifest.getBytes(StandardCharsets.UTF_8)));
            gzip.write(new byte[1024]);
        }
        return out.toByteArray();
    }

    /// Builds one tar entry.
    ///
    /// @param name the entry's name
    /// @param body the entry's bytes
    /// @return the header and body, padded to a block
    private static byte[] entry(String name, byte[] body) {
        byte[] block = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, block, 0, Math.min(nameBytes.length, 100));
        byte[] size = Long.toOctalString(body.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, block, 124, size.length);
        block[156] = '0';
        for (int i = 148; i < 156; i++) {
            block[i] = ' ';
        }
        int padding = (512 - (body.length % 512)) % 512;
        byte[] result = new byte[512 + body.length + padding];
        System.arraycopy(block, 0, result, 0, 512);
        System.arraycopy(body, 0, result, 512, body.length);
        return result;
    }
}
