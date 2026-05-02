package edu.illinois.group8.replay;

public class ReplayController {
    private volatile boolean paused;
    private volatile boolean stopped;

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public void stop() {
        stopped = true;
    }

    public void awaitIfPaused() {
        while (paused && !stopped) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop();
            }
        }
    }

    public boolean stopped() {
        return stopped;
    }
}
