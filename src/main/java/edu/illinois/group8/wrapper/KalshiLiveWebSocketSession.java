package edu.illinois.group8.wrapper;

import java.util.concurrent.CompletionStage;

public interface KalshiLiveWebSocketSession extends AutoCloseable {
    boolean subscribe(String[] channels);

    boolean subscribe(String[] channels, String[] marketTickers);

    long subscribeAndAwaitSid(String[] channels, String[] marketTickers, int timeoutMs)
        throws InterruptedException;

    void updateAndAwaitOk(long subscriptionId, String action, String[] marketTickers, int timeoutMs)
        throws InterruptedException;

    void requestSnapshotAndAwaitOk(long subscriptionId, String[] marketTickers, int timeoutMs)
        throws InterruptedException;

    CompletionStage<Void> closed();

    boolean isClosed();

    @Override
    void close();
}
