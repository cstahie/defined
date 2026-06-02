package com.teamundefined.defined.examples;

import com.teamundefined.defined.Slot;

/**
 * The example robot's subsystem slots — this is the one place a team enumerates
 * what can be "owned" by an action. The {@code ActionRunner} uses these to ensure
 * two actions never fight over the same mechanism.
 *
 * <p>Copy this idea into your own TeamCode: one small enum implementing {@link Slot}.
 */
public enum Subsystem implements Slot {
    DRIVE, INTAKE, FLYWHEEL, INDEXER, TURRET
}
