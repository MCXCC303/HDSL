package com.mcxcc303.hdsl.plugin;

import com.google.gson.JsonObject;
import com.mcxcc303.hdsl.pack.ModpackInstaller;
import com.mcxcc303.hdsl.pack.PackManifest;
import org.jackhuang.hmcl.util.platform.OperatingSystem;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies pack bundle handling for plugins installed from local files. */
class DshPluginBundleTest {

	private static PackManifest.Manifest manifest(PackManifest.Plugin plugin) {
		return new PackManifest.Manifest(PackManifest.FORMAT,
		                                 PackManifest.FORMAT_VERSION,
		                                 "",
		                                 "x",
		                                 "v0.1.7",
		                                 null,
		                                 "web",
		                                 List.of(plugin),
		                                 List.of(plugin.name()),
		                                 true,
		                                 "",
		                                 "1.0",
		                                 "",
		                                 "",
		                                 "",
		                                 "",
		                                 0,
		                                 List.of(),
		                                 List.of(),
		                                 List.of());
	}

	private static JsonObject json(Map<String, String> values) {
		JsonObject object = new JsonObject();
		values.forEach(object::addProperty);
		return object;
	}

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

	@Test
	void theTarballAProfileNamesIsWhatTravels(@TempDir Path directory) throws Exception {
		Path instance = directory.resolve("instance");
		Path plugins = instance.resolve(DshLocalPlugins.DIRECTORY);
		Files.createDirectories(plugins);
		Path tarball = plugins.resolve("dsh-secret-1.0.0.tgz");
		Files.write(tarball, pack("dsh-secret", "1.0.0"));

		Path profile = instance.resolve("home/profiles/web");
		Files.createDirectories(profile);

		DshPluginBundle.Payload payload = DshPluginBundle.locate(profile, "dsh-secret", "file:" + tarball);

		assertNotNull(payload, "the file the profile installed from is still there");
		assertEquals("1.0.0", payload.version(), "the version comes from the package, not the path");
		assertEquals(tarball, payload.archive());
		assertNull(payload.directory());
	}

	@Test
	void aPluginWhoseEntryLeavesTheProfileIsNotRead(@TempDir Path directory) throws Exception {
		// The specification names a packed plugin and that file is gone, so what is left is the
		// fallback: the entry in the profile's own `node_modules`. That entry resolves outside the
		// profile — a symbolic link, or the junction Windows makes instead of one — so what is
		// behind it belongs to whoever put it there. The fallback is for a copy the profile holds,
		// not for a link out of it, and reading through one would carry a package with no code in
		// it: a walk does not descend into the path it starts at when that path is a link.
		Path working = directory.resolve("dsh-fork");
		Files.createDirectories(working.resolve("lib"));
		Files.writeString(working.resolve("package.json"), """
				{"name":"dsh-fork","version":"2.1.0"}
				""");
		Files.writeString(working.resolve("lib/index.js"), "export const x = 1;\n");

		Path profile = directory.resolve("profiles/web");
		Path installed = profile.resolve("node_modules/dsh-fork");
		Files.createDirectories(installed.getParent());
		try {
			Files.createSymbolicLink(installed, working);
		} catch (UnsupportedOperationException | IOException e) {
			// A filesystem without links cannot test this part.
			return;
		}

		assertTrue(DshPluginBundle.isLinked(profile, "dsh-fork"), "the entry resolves outside the profile");
		assertNull(DshPluginBundle.locate(profile, "dsh-fork", "file:" + directory.resolve("dsh-fork-2.1.0.tgz")),
		           "so the fallback does not read it");
	}

	@Test
	void aDirectoryInstallIsAPluginUnderDevelopment(@TempDir Path directory) throws Exception {
		// `link:` and a directory `file:` are the same install: a plugin somebody is working on,
		// whose files stay in their directory. The profile points at it rather than holding a copy,
		// so there is none of the instance's own to carry — and a pack leaves it out, whichever way
		// the package manager arranged what is behind the entry: a real directory inside the
		// profile is not a copy either, because the answer is read off the specification.
		Path working = directory.resolve("dsh-fork");
		Files.createDirectories(working.resolve("lib"));
		Files.writeString(working.resolve("package.json"), """
				{"name":"dsh-fork","version":"2.1.0"}
				""");
		Files.writeString(working.resolve("lib/index.js"), "export const x = 1;\n");

		Path profile = directory.resolve("profiles/web");
		Path installed = profile.resolve("node_modules/dsh-fork");
		Files.createDirectories(installed.resolve("lib"));
		Files.writeString(installed.resolve("package.json"), """
				{"name":"dsh-fork","version":"2.1.0"}
				""");

		assertTrue(DshPluginBundle.namesADirectory("file:" + working, profile),
		           "a file: that names a directory is the same install as a link:");
		for (String spec : List.of("file:" + working, "link:" + working, "link:../dsh-fork")) {
			assertNull(DshPluginBundle.locate(profile, "dsh-fork", spec), "and the pack does not carry one: " + spec);
		}
	}

	@Test
	void aPluginBehindThePackageManagersOwnLinkIsStillCarried(@TempDir Path directory) throws Exception {
		// A package manager's own node_modules is made of links: pnpm resolves a dependency into
		// `node_modules/.pnpm` inside the profile. That entry is a link, but the copy is the
		// profile's own — and a walk does not descend into the path it starts at when that path is
		// a link, so the copy has to be read where it actually is. Without that, this plugin would
		// travel as the one file that is read without walking: its `package.json`.
		Path profile = directory.resolve("profiles/web");
		Path store = profile.resolve("node_modules/.pnpm/dsh-fork@2.1.0/node_modules/dsh-fork");
		Files.createDirectories(store.resolve("lib"));
		Files.writeString(store.resolve("package.json"), """
				{"name":"dsh-fork","version":"2.1.0"}
				""");
		Files.writeString(store.resolve("lib/index.js"), "export const x = 1;\n");
		Path installed = profile.resolve("node_modules/dsh-fork");
		Files.createDirectories(installed.getParent());
		try {
			Files.createSymbolicLink(installed, store);
		} catch (UnsupportedOperationException | IOException e) {
			// A filesystem without links cannot test this part.
			return;
		}

		assertFalse(DshPluginBundle.isLinked(profile, "dsh-fork"),
		            "an entry resolving inside the profile is the profile's own copy");
		DshPluginBundle.Payload payload = DshPluginBundle.locate(profile,
		                                                         "dsh-fork",
		                                                         "file:" + directory.resolve("dsh-fork-2.1.0.tgz"));
		assertNotNull(payload, "the tarball the instance installed from is gone, so the profile's copy travels");
		assertEquals(store.toRealPath(),
		             payload.directory(),
		             "the directory handed out is the one the files are really in: on macOS the temporary" +
		             " directory is reached through /var, and only one of the two spellings is real");
	}

	@Test
	void aWindowsJunctionIsALinkToo(@TempDir Path directory) throws Exception {
		Assumptions.assumeTrue(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS,
		                       "junctions are a Windows feature");

		Path working = directory.resolve("dsh-fork");
		Files.createDirectories(working.resolve("lib"));
		Files.writeString(working.resolve("package.json"), """
				{"name":"dsh-fork","version":"2.1.0"}
				""");
		Files.writeString(working.resolve("lib/index.js"), "export const x = 1;\n");

		Path profile = directory.resolve("profiles/web");
		Path installed = profile.resolve("node_modules/dsh-fork");
		Files.createDirectories(installed.getParent());
		Process link = new ProcessBuilder("cmd.exe",
		                                  "/c",
		                                  "mklink",
		                                  "/J",
		                                  installed.toString(),
		                                  working.toString()).redirectErrorStream(true).start();
		try (var reader = link.inputReader()) {
			while (reader.readLine() != null) {
				// Drain, so the process is not left blocked on a full pipe.
			}
		}
		Assumptions.assumeTrue(link.waitFor() == 0, "this machine will not make a junction");

		assertTrue(DshPluginBundle.isLinked(profile, "dsh-fork"), "a junction is a link too");
		assertNull(DshPluginBundle.locate(profile, "dsh-fork", "link:" + working),
		           "and a pack does not read through one");
	}

	@Test
	void aPackedPluginComesBackOutOfThePackWhereItWentIn(@TempDir Path directory) throws Exception {
		Path tarball = directory.resolve("dsh-secret-1.0.0.tgz");
		Files.write(tarball, pack("dsh-secret", "1.0.0"));

		Path pack = directory.resolve("pack.hdslp");
		DshPluginBundle.Payload payload = new DshPluginBundle.Payload("dsh-secret", "1.0.0", tarball, null);
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
	void aDirectoryComesBackOutWithItsFilesAndWithoutItsDependencies(@TempDir Path directory) throws Exception {
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
			DshPluginBundle.writeInto(zip, new DshPluginBundle.Payload("dsh-fork", "2.1.0", null, source), null);
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
		PackManifest.Manifest pack = manifest(new PackManifest.Plugin("dsh-secret",
		                                                              "file:/made/elsewhere.tgz",
		                                                              true,
		                                                              true,
		                                                              true));

		Map<String, String> dependencies = ModpackInstaller.dependenciesOf(pack, Map.of("dsh-secret", released));

		assertEquals(released.toString(),
		             dependencies.get("dsh-secret"),
		             "the path from the machine the pack was made on is what this replaces");
		assertEquals(List.of("dsh-secret"),
		             ModpackInstaller.listsBootable(pack, json(dependencies)),
		             "the bundle boots, because the profile now declares the package");
	}

	@Test
	void aLocalPluginThePackDoesNotCarryIsLeftOutOfBothLists(@TempDir Path directory) {
		PackManifest.Manifest pack = manifest(new PackManifest.Plugin("dsh-secret",
		                                                              "file:/their/path.tgz",
		                                                              true,
		                                                              true,
		                                                              false));

		Map<String, String> dependencies = ModpackInstaller.dependenciesOf(pack, Map.of());

		assertTrue(dependencies.isEmpty(),
		           "a path from another machine would make every later operation on the profile fail");
		assertTrue(ModpackInstaller.listsBootable(pack, json(dependencies)).isEmpty(),
		           "and a bundle the profile does not have stops the instance from starting");
	}

	@Test
	void aMemberThatWouldLandOutsideThePluginDirectoryIsNotUnpacked(@TempDir Path directory) throws Exception {
		// A pack is an archive somebody else made, and a payload's name is a path. What the name is
		// checked against is the package it belongs to — but a name that starts with the right
		// package and then steps out of the plugin directory, written the two ways Windows and POSIX
		// spell it, used to be written wherever it pointed: over an instance, over its profile, over
		// anything the user owns. What it is now is a member that is not unpacked at all, which is
		// what the first assertion below is about: the member that carries the pack's payload still
		// arrives, so a pack is refused the step out rather than refused for carrying a path.
		Path plugins = directory.resolve("instance/plugins");
		for (String escape : List.of("plugins/dsh-secret-1.0.0/../../PWNED.txt",
		                             "plugins/dsh-secret-1.0.0/..\\..\\PWNED.txt")) {
			Path pack = directory.resolve("pack-" + escape.hashCode() + ".hdslp");
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
				zip.putNextEntry(new ZipEntry("plugins/dsh-secret-1.0.0.tgz"));
				zip.write(pack("dsh-secret", "1.0.0"));
				zip.closeEntry();
				zip.putNextEntry(new ZipEntry(escape));
				zip.write("planted".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}

			Path released = DshPluginBundle.release(pack, plugins, "dsh-secret", "1.0.0");

			assertNotNull(released, escape + " is the member that has to be dropped, not the payload");
			assertTrue(Files.isRegularFile(released), "the payload is unpacked where it belongs");
			assertFalse(Files.exists(directory.resolve("instance/PWNED.txt")),
			            escape + " was written outside the plugin directory");
			assertFalse(Files.exists(directory.resolve("PWNED.txt")), escape + " was written outside the instance");
		}
	}

	@Test
	void aMemberInsideThePluginDirectoryIsStillUnpacked(@TempDir Path directory) throws Exception {
		Path pack = directory.resolve("pack.hdslp");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
			zip.putNextEntry(new ZipEntry("plugins/dsh-secret-1.0.0/lib/index.js"));
			zip.write("export const x = 1;\n".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		Path plugins = directory.resolve("instance/plugins");
		Path released = DshPluginBundle.release(pack, plugins, "dsh-secret", "1.0.0");

		assertNotNull(released);
		assertEquals(plugins.toAbsolutePath().normalize().resolve("dsh-secret-1.0.0"),
		             released,
		             "a payload carried as a directory comes back as that directory");
		assertEquals("export const x = 1;\n",
		             Files.readString(plugins.resolve("dsh-secret-1.0.0/lib/index.js")),
		             "and its members are unpacked inside it");
	}

	@Test
	void aBundleThePackDoesNotNameAsAPluginIsKept(@TempDir Path directory) {
		PackManifest.Manifest pack = new PackManifest.Manifest(PackManifest.FORMAT,
		                                                       PackManifest.FORMAT_VERSION,
		                                                       "",
		                                                       "x",
		                                                       "v0.1.7",
		                                                       null,
		                                                       "web",
		                                                       List.of(),
		                                                       List.of("@deepseek-ai/dsh-base"),
		                                                       false,
		                                                       "",
		                                                       "1.0",
		                                                       "",
		                                                       "",
		                                                       "",
		                                                       "",
		                                                       0,
		                                                       List.of(),
		                                                       List.of(),
		                                                       List.of());

		assertEquals(List.of("@deepseek-ai/dsh-base"),
		             ModpackInstaller.listsBootable(pack, json(Map.of())),
		             "the harness's own bundles are not dependencies of the profile");
	}
}
