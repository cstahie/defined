package com.teamundefined.defined.examples.subsystems;

/**
 * A simulated intake. It just tracks whether it's running; the {@code DummyRobot}
 * coordinator feeds collected balls into the {@link Indexer} while it runs.
 */
public class Intake {
    private boolean running = false;

    public void on() { running = true; }
    public void off() { running = false; }
    public boolean isRunning() { return running; }
}
