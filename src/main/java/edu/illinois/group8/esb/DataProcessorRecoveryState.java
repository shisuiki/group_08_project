package edu.illinois.group8.esb;

import edu.illinois.group8.book.OrderBookRecoveryCheckpoint;
import edu.illinois.group8.book.OrderBookStateManager;
import edu.illinois.group8.book.SourceSequenceMonitor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record DataProcessorRecoveryState(
    Map<Long, Long> sourceWatermarks,
    List<OrderBookRecoveryCheckpoint> orderBookRecoveryCheckpoints
) {
    public DataProcessorRecoveryState {
        sourceWatermarks = Collections.unmodifiableMap(new HashMap<>(
            SourceSequenceMonitor.copyWatermarks(sourceWatermarks)
        ));
        orderBookRecoveryCheckpoints = OrderBookStateManager.copyRecoveryCheckpoints(
            orderBookRecoveryCheckpoints
        );
    }
}
