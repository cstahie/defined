# defined-examples (desktop engine demo)

> **Looking for the realistic robot to copy?** See **[`defined-example-ftc`](../defined-example-ftc)** —
> it mirrors real TeamCode (real subsystems, the actual `NavigationAction`, the
> `ActionOpMode` lifecycle, TeleOp + Auto) and compiles against the FTC SDK + Pedro.
>
> **This** module is the hardware‑free version: a **simulated** robot so you can *run
> and unit‑test the engine on a laptop*. Its `driveTo` is a desktop stand‑in for
> `NavigationAction`. Use it to understand the engine + slot model; use
> `defined-example-ftc` as the template for your robot.

A simulated robot showing the same structure — subsystems, per‑subsystem action
factories, and an Auto + a TeleOp — that **runs and is tested on a laptop** (no hardware).

```bash
./gradlew :defined-examples:run     # play a full simulated match (auto, then teleop)
./gradlew :defined-examples:test    # prove auto + teleop actually score
```

## How it's organized (copy this shape into your TeamCode)

```mermaid
flowchart TD
    subgraph opmodes["opmodes/  (your OpModes)"]
        AUTO["DummyAuto<br/><i>one composed Action</i>"]
        TELE["DummyTeleOp<br/><i>ActionRunner + monitors</i>"]
    end

    subgraph actions["actions/  (per-subsystem factories)"]
        DA["DriveActions"]
        IA["IntakeActions"]
        SA["ShootingActions"]
        TA["TurretActions"]
    end

    subgraph robot["robot"]
        DR["DummyRobot<br/><i>owns + ticks subsystems</i>"]
        SL["Subsystem (Slot enum)"]
    end

    subgraph subs["subsystems/"]
        D["Drive"]; I["Intake"]; F["Flywheel"]; X["Indexer"]; T["Turret"]
    end

    AUTO --> actions
    TELE --> actions
    actions -->|requires| SL
    actions -->|command| DR
    DR --> D & I & F & X & T
```

| Layer | Files | Maps to your TeamCode as… |
|---|---|---|
| **subsystems/** | `Drive`, `Intake`, `Flywheel`, `Indexer`, `Turret` | your hardware wrappers |
| **robot** | `DummyRobot`, `Subsystem` | `Robot.java` + your `Slot` enum |
| **actions/** | `DriveActions`, `IntakeActions`, `ShootingActions`, `TurretActions` | your `*Actions` builders |
| **opmodes/** | `DummyAuto`, `DummyTeleOp` | your `@Autonomous` / `@TeleOp` |

## Autonomous = one composed action

```mermaid
flowchart LR
    a["driveTo(cluster)"] --> b["intakeUntil(3)"]
    b --> c["ParallelAll: driveTo(goal) ∥ spinUp"]
    c --> d["aim (turret)"] --> e["fireAll"] --> f["driveTo(park)"]
```

## TeleOp = runner + monitors + a button‑started group

```mermaid
flowchart TD
    R["ActionRunner.update(now)"]
    R --> m1["manualDrive (monitor)"]
    R --> m2["intakeToggle — X (monitor)"]
    R --> m3["turretToggle — Square (monitor)"]
    R --> m4["ToggleStartGroupAction — Triangle"]
    m4 -->|startGroup| g["shootLoaded<br/>requires FLYWHEEL + INDEXER"]
```

## Going from this to a real robot

1. Replace each `subsystems/*` class with your real hardware (`DcMotor`, `Servo`, …).
2. Keep the `actions/*` factories almost as‑is — they're already Defined actions.
3. Make `DummyTeleOp`/`DummyAuto` extend the FTC SDK (`OpMode`, or
   `ActionOpMode` from [`defined-ftc`](../defined-ftc)) and feed real `gamepad1.*`.
4. Swap `DriveActions.driveTo` for the Pedro `NavigationAction` from
   [`defined-pedro`](../defined-pedro).

The action structure — and the slot safety — stays identical.
