package edu.illinois.group8.book;

public record OrderBookRecoveryCheckpoint(String marketTicker, Long lastSequence) {
}
