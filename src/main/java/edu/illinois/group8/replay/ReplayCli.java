package edu.illinois.group8.replay;

import edu.illinois.group8.config.BackendConfig;
import edu.illinois.group8.parser.KalshiCanonicalParser;
import edu.illinois.group8.persistence.RawJournalReader;
import edu.illinois.group8.publication.CollectingEventPublisher;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class ReplayCli {
    public static void main(String[] args) {
        BackendConfig config = BackendConfig.fromEnvironment();
        ReplayOptions options = parseArgs(args, config);
        ReplayService replayService = new ReplayService(
            new KalshiCanonicalParser(),
            new RawJournalReader(options.journalRoot())
        );
        CollectingEventPublisher publisher = new CollectingEventPublisher();
        ReplaySummary summary = replayService.replay(options, publisher, new ReplayController());
        System.out.println(summary);
    }

    private static ReplayOptions parseArgs(String[] args, BackendConfig config) {
        Path journalRoot = config.journalRoot();
        List<String> markets = config.marketTickers();
        Long start = null;
        Long end = null;
        ReplayMode mode = ReplayMode.MULTIPLIER;
        double speed = 1.0;
        String replayId = null;

        for (String arg : args) {
            if (arg.startsWith("--journal-root=")) {
                journalRoot = Path.of(arg.substring("--journal-root=".length()));
            } else if (arg.startsWith("--market=")) {
                markets = Arrays.stream(arg.substring("--market=".length()).split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
            } else if (arg.startsWith("--start-ts-ms=")) {
                start = Long.parseLong(arg.substring("--start-ts-ms=".length()));
            } else if (arg.startsWith("--end-ts-ms=")) {
                end = Long.parseLong(arg.substring("--end-ts-ms=".length()));
            } else if (arg.startsWith("--speed=")) {
                speed = Double.parseDouble(arg.substring("--speed=".length()));
                mode = ReplayMode.MULTIPLIER;
            } else if (arg.equals("--wall-clock")) {
                mode = ReplayMode.WALL_CLOCK;
            } else if (arg.equals("--step")) {
                mode = ReplayMode.STEP;
            } else if (arg.startsWith("--replay-id=")) {
                replayId = arg.substring("--replay-id=".length());
            }
        }

        return new ReplayOptions(journalRoot, markets, start, end, mode, speed, replayId);
    }
}
