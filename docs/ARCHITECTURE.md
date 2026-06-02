# Architecture

Defined is intentionally small. There are exactly three core concepts —
**`Action`**, **`Slot`**, and **`ActionRunner`** — and everything else is an
`Action` subclass that composes or decorates other actions.

## The big picture

```mermaid
graph LR
    subgraph loop["Your OpMode loop()"]
        clock["nowMillis"]
    end

    subgraph engine["defined-core"]
        runner["ActionRunner"]
        monitors["Monitors<br/>(slot-free)"]
        running["Running actions<br/>(one per Slot)"]
        pending["Pending actions<br/>(coalesced per Slot)"]
        action["Action<br/>state machine"]
    end

    clock --> runner
    runner --> monitors
    runner --> running
    running --> pending
    monitors --> action
    running --> action
    action -->|controls| hw["Subsystems / hardware"]
```

Your loop feeds a timestamp to the runner. The runner ticks every monitor and
every running action exactly once. Each `Action` advances its own state machine
and pokes hardware through plain Java lambdas you supply.

## 1. `Action` — the state machine

An `Action` holds a `step` (what to do each tick) and an optional `isComplete`
predicate. `update(nowMillis)` runs the step, checks for timeout/completion, and
fires lifecycle callbacks.

```mermaid
stateDiagram-v2
    [*] --> NONE
    NONE --> RUNNING: first update() — fires onStart
    RUNNING --> RUNNING: step(now) each tick
    RUNNING --> COMPLETE: isComplete() true — fires onComplete
    RUNNING --> TIMEOUT: elapsed ≥ timeout — fires onTimeout
    RUNNING --> ERROR: step throws — fires onError
    RUNNING --> CANCELED: cancel() — fires onCancel + onCancelCleanup
    COMPLETE --> [*]
    TIMEOUT --> [*]
    ERROR --> [*]
    CANCELED --> [*]
```

Key properties:

- **Terminal is sticky.** Once `COMPLETE`/`TIMEOUT`/`ERROR`/`CANCELED`, further
  `update()` calls are no‑ops until `reset()`.
- **Time is injected.** Nothing reads the wall clock; you pass `nowMillis`. This is
  what makes the engine deterministic and desktop‑testable.
- **Callbacks chain.** `withOnComplete(...)` adds to existing callbacks rather than
  replacing them.
- **Reusable.** `reset()` returns an action to `NONE` so you can run it again.

### Composites

`SequentialAction`, `ParallelAction`, `RepeatAction`, etc. are just actions that
own child actions and call the children's `update(now)` from their own `step`.
Failure/timeout/cancel propagate according to each composite's contract.

```mermaid
graph TD
    seq["SequentialAction 'auto'"]
    seq --> a["FollowPathAction"]
    seq --> par["ParallelAction.all"]
    par --> load["loadBalls (INTAKE)"]
    par --> spin["spinUp (FLYWHEEL)"]
    seq --> fire["fireAll (INDEXER)"]
```

## 2. `Slot` — cooperative resource locking

`Slot` is an empty marker interface. Teams declare their own subsystems as an
`enum implements Slot`. An action declares the subsystems it controls with
`.requires(...)`, and the runner guarantees exclusivity.

```java
public enum Subsystem implements Slot { DRIVE, INTAKE, FLYWHEEL, INDEXER }
```

## 3. `ActionRunner` — arbitration

The runner manages three buckets:

- **monitors** — slot‑free actions that tick every loop (drive control, toggles).
- **running** — at most one action per slot.
- **pending** — at most one queued action per slot (newer replaces older).

```mermaid
flowchart TD
    start(["startGroup(action)"]) --> already{Already running<br/>this instance?}
    already -->|yes| done1([no‑op])
    already -->|no| slots{Required slots<br/>free?}
    slots -->|yes| install[Install as running] --> done2([started])
    slots -->|no| cancel[Cancel conflicting<br/>non‑terminal actions] --> stage[Stage as pending<br/>for each slot] --> done3([queued])

    upd(["update(now)"]) --> mon[Tick monitors] --> run[Tick running actions] --> clean[Drop terminal actions<br/>→ free their slots] --> flush[Start pending whose<br/>slots are now free]
```

This is why `FinallyAction` composes so well with the runner: a cancelled action
keeps holding its slots while its cleanup runs, so the next action can't barge in
until cleanup finishes.

## Module layout

```mermaid
graph TD
    subgraph pure["Pure Java (desktop‑testable)"]
        core["defined-core"]
        ex["defined-examples"]
    end
    subgraph android["Android libraries"]
        ftc["defined-ftc"]
        pedro["defined-pedro"]
    end
    ex --> core
    ftc --> core
    pedro --> core
```

- **core** stays pure Java so it builds and tests anywhere, and so the engine has
  zero overhead on the robot (the `Log` facade is a no‑op until a sink is installed).
- **ftc** and **pedro** are thin Android adapters; both declare their heavy
  dependencies as `compileOnly` because the robot app already bundles them.

## Performance notes

- No allocations in the hot path: the runner iterates with indexed loops and reuses
  collections.
- Logging is free unless enabled — `Log.sink` is `null` by default and message
  suppliers are never evaluated.
- One `update(now)` per action per loop; composites add only the cost of their
  active children.
