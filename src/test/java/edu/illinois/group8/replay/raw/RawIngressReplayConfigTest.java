package edu.illinois.group8.replay.raw;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawIngressReplayConfigTest {
    @Test
    void constructorDefaultsUseRawWsSchemaNames() {
        RawIngressReplayConfig config = new RawIngressReplayConfig(
            "timescale",
            Path.of("/tmp/raw"),
            "",
            "",
            "",
            "",
            "raw_payload",
            "receive_ts_ns",
            "connection_id",
            "",
            "raw_event_id",
            "market_ticker",
            RawReplayMode.AS_FAST_AS_POSSIBLE,
            1.0,
            0L,
            0L,
            null,
            null,
            List.of(),
            List.of(),
            true,
            false,
            "replay-1"
        );

        assertEquals("raw_ws_events", config.rawTable());
        assertEquals("connection_sequence", config.sequenceColumn());
    }

    @Test
    void environmentDefaultsUseRawWsSchemaNames() {
        RawIngressReplayConfig config = RawIngressReplayConfig.from(Map.of("BASE_DIR", "/tmp/base"));

        assertEquals("raw_ws_events", config.rawTable());
        assertEquals("connection_sequence", config.sequenceColumn());
    }

    @Test
    void environmentOverridesStillWin() {
        RawIngressReplayConfig config = RawIngressReplayConfig.from(Map.of(
            "RAW_REPLAY_TABLE", "custom_raw",
            "RAW_REPLAY_SEQUENCE_COLUMN", "custom_sequence"
        ));

        assertEquals("custom_raw", config.rawTable());
        assertEquals("custom_sequence", config.sequenceColumn());
    }

    @Test
    void cliOverridesStillWin() {
        RawIngressReplayConfig config = RawIngressReplayConfig.from(Map.of())
            .withCliArgs(new String[] {
                "--table=custom_cli_raw",
                "--sequence-column=custom_cli_sequence"
            });

        assertEquals("custom_cli_raw", config.rawTable());
        assertEquals("custom_cli_sequence", config.sequenceColumn());
    }

    @Test
    void usageShowsRawWsSchemaDefaults() {
        String usage = RawIngressReplayConfig.usage();

        assertTrue(usage.contains("--table=raw_ws_events"));
        assertTrue(usage.contains("--sequence-column=connection_sequence"));
    }
}
