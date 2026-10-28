package io.github.imcinq.puremetal.backend;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/** Headless checks for the experimental DH preflight. */
public final class TestDistantHorizonsExperiment {
    private static final DistantHorizonsExperiment.EngineState OK = DistantHorizonsExperiment.EngineState.OK;
    private static final DistantHorizonsExperiment.EngineState WRONG_ENGINE = DistantHorizonsExperiment.EngineState.WRONG_ENGINE;
    private static final DistantHorizonsExperiment.EngineState UNREADABLE = DistantHorizonsExperiment.EngineState.UNREADABLE;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("puremetal-dh-test-");
        Path config = dir.resolve("DistantHorizons.toml");
        try {
            check(OK, null, config, null);
            check(OK, null, config, "[client.advanced.graphics.experimental]\nrenderingEngine = \"AUTO\"\n");
            check(OK, null, config, "renderingEngine = 'BLAZE_3D' # comment\n");
            check(WRONG_ENGINE, "OPEN_GL", config, "renderingEngine = \"OPEN_GL\"\n");
            check(WRONG_ENGINE, "OPEN_GL", config, "\"renderingEngine\" = 'OPEN_GL'\n");
            check(WRONG_ENGINE, "OPEN_GL", config, "client.advanced.graphics.experimental.renderingEngine = \"OPEN_GL\"\n");
            check(WRONG_ENGINE, "OPEN_GL", config, "renderingEngine = \"AUTO\"\nrenderingEngine = \"OPEN_GL\"\n");
            check(UNREADABLE, null, config, "renderingEngine = bad\n");
            check(UNREADABLE, null, config, "renderingEngine = \"AUTO\"\nrenderingEngine = \"BLAZE_3D\"\n");
            Files.write(config, new byte[] {'r', 'e', 'n', 'd', 'e', 'r', 'i', 'n', 'g', 'E', 'n', 'g', 'i', 'n', 'e', ' ', '=', ' ', '"', (byte) 0xFF, (byte) 0xFE, '"', '\n'});
            var unreadable = DistantHorizonsExperiment.checkEngine(config);
            if (unreadable.state() != UNREADABLE || unreadable.value() != null) throw new AssertionError("Invalid UTF-8 config must be UNREADABLE but got " + unreadable);
            for (var status :DistantHorizonsExperiment.Status.values()) {
                String note = status.note();
                if (note != null && (note.length() > 32 || note.indexOf('\u2014') >= 0)) throw new AssertionError("Bad F3 note for " + status);
            }
            if (DistantHorizonsExperiment.Status.OFF.note() != null || DistantHorizonsExperiment.Status.NOT_INSTALLED.note() != null)
                throw new AssertionError("F3 must stay silent when the flag is off or DH is absent");
            if (args.length > 0) {
                try (var archive = FileSystems.newFileSystem(Path.of(args[0]))) {
                    if (!DistantHorizonsExperiment.hasVerifiedClasses(name -> {
                        Path path = archive.getPath("/" + name);
                        return Files.exists(path) ? java.util.Optional.of(path) : java.util.Optional.empty();
                    })) throw new AssertionError("DH release classes do not match");
                }
            }
        } finally {
            Files.deleteIfExists(config);
            Files.deleteIfExists(dir);
        }
        System.out.println("PASS: DH preflight engine states, F3 notes and release classes");
    }

    private static void check(DistantHorizonsExperiment.EngineState expected, String value, Path config, String text) throws Exception {
        if (text == null) Files.deleteIfExists(config); else Files.writeString(config, text);
        var actual = DistantHorizonsExperiment.checkEngine(config);
        if (actual.state() != expected || !java.util.Objects.equals(actual.value(), value))
            throw new AssertionError("Expected " + expected + " " + value + " for " + text + " but got " + actual);
    }
}
