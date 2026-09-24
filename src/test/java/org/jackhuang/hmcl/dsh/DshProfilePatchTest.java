package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The repair a pack needs when it both lists a plugin as a bundle and inserts it.
///
/// Taken from a pack that would not start: `@michengai/dsh-im-connect` in `dsh.profile.bundles` **and**
/// in the patch's insert list, which applies it twice and ends the profile with a duplicate route.
class DshProfilePatchTest {
    @TempDir
    private Path profile;

    private Path patch(String body) throws Exception {
        Path file = profile.resolve("cordis.patch.yml");
        Files.writeString(file, body);
        return file;
    }

    @Test
    void anInsertABundleAlreadyAppliesIsTakenOut() throws Exception {
        Path file = patch("""
                # Your patch layer for this dsh profile
                - insert:
                    - id: dsh-im-connect
                      name: '@michengai/dsh-im-connect'
                """);

        List<String> left = DshProfilePatch.dropRedundantInserts(file,
                List.of("@deepseek-ai/dsh-base", "@michengai/dsh-im-connect"));

        assertEquals(List.of(), left, "nothing carried configuration");
        String after = Files.readString(file);
        assertTrue(after.contains("# Your patch layer"), "the comment stays: " + after);
        assertTrue(!after.contains("dsh-im-connect"), "the duplicate insert is gone: " + after);
    }

    @Test
    void anInsertNoBundleAppliesIsKept() throws Exception {
        String body = """
                - insert:
                    - id: something-else
                      name: 'dsh-something-else'
                """;
        Path file = patch(body);

        DshProfilePatch.dropRedundantInserts(file, List.of("@deepseek-ai/dsh-base"));

        assertEquals(body, Files.readString(file), "a plugin nothing else applies is left where it is");
    }

    @Test
    void anInsertCarryingConfigurationIsKeptAndReported() throws Exception {
        String body = """
                - insert:
                    - id: better-sidebar
                      name: 'dsh-better-sidebar'
                      config:
                        css: 'body { color: red }'
                """;
        Path file = patch(body);

        List<String> left = DshProfilePatch.dropRedundantInserts(file, List.of("dsh-better-sidebar"));

        assertEquals(List.of("better-sidebar"), left, "the entry is reported by its id");
        assertEquals(body, Files.readString(file), "its settings are not thrown away");
    }

    @Test
    void aPatchWithNoInsertsIsUntouched() throws Exception {
        String body = """
                - id: tool-web
                  config:
                    fetch: true
                """;
        Path file = patch(body);

        assertEquals(List.of(), DshProfilePatch.dropRedundantInserts(file, List.of("anything")));
        assertEquals(body, Files.readString(file));
    }
}
