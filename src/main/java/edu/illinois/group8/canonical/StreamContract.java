package edu.illinois.group8.canonical;

public record StreamContract(
    String streamName,
    int streamId,
    int schemaVersion,
    String owner,
    String serializationFormat,
    String orderingGuarantee,
    boolean replayAvailable,
    String retentionPolicy,
    boolean external
) {
}
