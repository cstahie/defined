package com.teamundefined.defined.examples.sim;

import com.teamundefined.defined.Slot;

/**
 * The example robot's subsystem slots.
 *
 * <p>This is exactly what a real team writes: a small enum implementing
 * {@link Slot}. The {@code ActionRunner} uses these to guarantee only one
 * action drives a given subsystem at a time.
 */
public enum Subsystem implements Slot {
    DRIVE,
    INTAKE,
    FLYWHEEL,
    INDEXER
}
