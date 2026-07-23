# defined-example-ftc

A **realistic FTC robot** built with Defined — the template to copy into your own
TeamCode. It mirrors a real team's structure exactly and uses the **actual**
`NavigationAction`, the `Robot` + `RobotOpMode` lifecycle, the driver‑station
pre‑start menu, async telemetry, and the profiler / system‑monitor / hardware‑scheduler
utilities. It compiles against the FTC SDK + Pedro (so it's CI‑checked), but it drives
real hardware, so it isn't meant to run on a desktop.

> Want a version you can run on a laptop? See [`defined-examples`](../defined-examples)
> (a hardware‑free demo of the core engine and slot model). **This** module is what you
> clone for a real robot — it's the one that shows every feature working together.

## What it demonstrates

- **`ExampleRobot extends Robot`** — the lifecycle contract. `RobotOpMode` calls its
  hooks in order every loop, so there is no hand‑written `init()`/`loop()`/`stop()`
  plumbing. It wires bulk‑cache clearing, a `HardwareScheduler` for expensive reads, a
  `SectionProfiler`, and a `SystemMonitor`.
- **`ExampleTeleOp extends RobotOpMode<ExampleRobot>`** — controls in `onLoop`, monitors
  and the pre‑start menu in `onRobotInit`, and the two‑stage async telemetry pipeline
  (`fillSnapshot` captures on the loop thread, `formatTelemetry` lays out on a
  background thread).
- **`ExampleAuto extends RobotOpMode<ExampleRobot>`** — same base, no `onLoop` at all:
  the base ticks the robot and runner, and the composed routine does the rest.
- **`ExampleConfig`** — the field‑editable settings the menu drives.

## Structure (mirrors real TeamCode)

```mermaid
flowchart TD
    subgraph opmodes["opmodes/"]
        TELE["ExampleTeleOp<br/>extends RobotOpMode"]
        AUTO["ExampleAuto<br/>extends RobotOpMode"]
    end

    subgraph actions["actions/  (per-subsystem factories)"]
        DA["DriveActions → NavigationAction"]
        IA["IntakeActions"]
        SA["ShootingActions"]
        TA["TurretActions"]
        AA["AutonomyActions.autonomousRoutine"]
    end

    subgraph robot["robot"]
        RB["ExampleRobot<br/>extends Robot · lifecycle hooks"]
        SL["Subsystem (Slot enum)"]
        PC["ExampleConstants → Pedro Follower"]
        PO["Poses"]
    end

    subgraph subs["subsystems/"]
        I["Intake"]; F["Flywheel"]; X["Indexer"]; T["Turret"]
    end

    TELE -->|addMonitor / startGroup| actions
    AUTO -->|runner.startGroup| AA
    AA --> DA & IA & SA & TA
    actions -->|requires| SL
    actions -->|command| RB
    RB --> I & F & X & T
    RB --> PC
    DA --> PO
```

## The loop (RobotOpMode)

`RobotOpMode` owns the ordering. You never call `robot.update()` or `runner.update()`
yourself — you fill in the hooks (bold), and the base runs everything around them each
loop. This is the ordering that keeps a fresh pose in front of your logic and hardware
writes behind it.

```mermaid
sequenceDiagram
    participant SDK as FTC SDK
    participant OP as RobotOpMode
    participant ROB as ExampleRobot
    participant RUN as ActionRunner

    SDK->>OP: init()
    OP->>ROB: createRobot() + init()
    OP-->>OP: onRobotInit()  ← you: monitors + menu
    loop init_loop (pre-match)
        OP->>ROB: initUpdate()
        Note over OP: pre-start menu runs here
    end
    SDK->>OP: start()  →  ROB.start(isTeleOp)
    loop every loop()
        OP->>ROB: preUpdate(now)   (bulk cache, localization, scheduled reads)
        OP-->>OP: onLoop(now)      ← you: driver controls
        OP->>ROB: update(now)      (tick subsystems + Pedro)
        OP->>RUN: update(now)      (advance actions)
        OP-->>OP: fillSnapshot → async formatTelemetry
    end
    SDK->>OP: stop()  (cancel actions · ROB.stop · telemetry shutdown)
```

## How it maps to your project

| This module | Your TeamCode |
|---|---|
| `ExampleRobot` (extends `Robot`) | your `Robot.java` (extends `Robot`) |
| `Subsystem` (enum) | your `Slot` enum |
| `subsystems/*` | your hardware wrappers |
| `ExampleConstants` | your `pedroPathing/Constants.java` |
| `Poses` | your `Poses.java` |
| `actions/*Actions` | your `builders/specialized/*Actions` |
| `opmodes/ExampleTeleOp` / `ExampleAuto` (extend `RobotOpMode`) | your `@TeleOp` / `@Autonomous` |

## Configure / build

Hardware names used: `flywheel1`, `flywheel2`, `angle`, `intakeLeft`, `intakeRight`,
`leftGate`, `centerGate`, `rightGate`, `turret`, drive motors `leftFront`/`leftBack`/
`rightFront`/`rightBack`, and `pinpoint`. Rename in `ExampleRobot`/`ExampleConstants`
to match your config.

```bash
./gradlew :defined-example-ftc:assembleRelease   # compiles against FTC SDK + Pedro
```
