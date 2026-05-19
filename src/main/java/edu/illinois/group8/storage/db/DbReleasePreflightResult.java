package edu.illinois.group8.storage.db;

import java.util.List;

public record DbReleasePreflightResult(
    int postgresVersionNum,
    String canonicalReplayIndexDefinition
) {
    public List<String> lines() {
        return List.of(
            "postgres_version_num=" + postgresVersionNum,
            "flyway_v008=success",
            "canonical_replay_index=ok"
        );
    }
}
