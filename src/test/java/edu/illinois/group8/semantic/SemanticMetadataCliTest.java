package edu.illinois.group8.semantic;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticMetadataCliTest {
    @Test
    void helpIsReadOnlyAndDoesNotRequireSecrets() {
        Output output = run(new String[] {"--help"}, Map.of());

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("SemanticMetadataCli"));
        assertTrue(output.stderr().isBlank());
    }

    @Test
    void errorsAreRedacted() {
        Output output = run(new String[] {"--unknown=sk-secret-token-12345678901234567890"}, Map.of(
            "OPENROUTER_API_KEY", "sk-secret-token-12345678901234567890",
            "DB_WRITER_DATABASE_URL", "jdbc:postgresql://db/kalshi"
        ));

        assertEquals(1, output.exitCode());
        assertTrue(output.stderr().contains("semantic_metadata_error="));
        assertTrue(!output.stderr().contains("sk-secret-token"));
        assertTrue(output.stderr().contains("[redacted]"));
    }

    private static Output run(String[] args, Map<String, String> env) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = SemanticMetadataCli.run(
            args,
            env,
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Output(
            exit,
            stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private record Output(int exitCode, String stdout, String stderr) {
    }
}
