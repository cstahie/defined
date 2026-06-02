package com.teamundefined.defined;

/**
 * A resource lock identifier used by {@link com.teamundefined.defined.runner.ActionRunner}
 * to guarantee that at most one {@link Action} controls a given subsystem at a time.
 *
 * <p>The library intentionally does <b>not</b> hard-code subsystem names. Each team
 * declares its own slots, typically as an {@code enum}:
 *
 * <pre>{@code
 * public enum Subsystem implements Slot {
 *     DRIVE, INTAKE, TURRET, FLYWHEEL, INDEXER
 * }
 *
 * Action aim = new Action("aim", now -> turret.update())
 *         .requires(Subsystem.TURRET);
 * }</pre>
 *
 * <p>When the {@code ActionRunner} is asked to start an action whose required slots
 * are held by another non-terminal action, it cancels the conflicting action and
 * stages the new one as pending until the slots free up. See
 * {@link com.teamundefined.defined.runner.ActionRunner} for the full contract.
 *
 * <p>Any object may implement {@code Slot}; using an {@code enum} is recommended so
 * that slot identity is stable and {@code requires(...)} reads clearly. Implementations
 * must have sensible {@code equals}/{@code hashCode} (enums do by default) because
 * slots are used as map and set keys.
 */
public interface Slot {
}
