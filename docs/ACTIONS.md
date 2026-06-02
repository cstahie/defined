# Action catalog

Every action type in Defined, with its purpose, primary factory, and a short
example. Each one has a deterministic, runnable test in
[`defined-core/src/test`](../defined-core/src/test) — those tests double as worked
examples and are the source of truth for behavior.

Conventions:
- `now` is the injected timestamp (ms) passed to `update(now)`.
- A *monitor* never completes on its own; it runs until cancelled.
- "requires slots" only matters when run via an `ActionRunner`.

---

## The base: `Action`

The foundation. A `step` (run each tick) plus an optional `isComplete` predicate.

```java
Action.oneShot("open_gate", now -> gate.open());                 // runs once, completes
Action.until("aim", now -> turret.track(), turret::onTarget);    // runs until predicate true
Action.until("aim", now -> turret.track(), turret::onTarget, 2000); // ...with a 2s timeout
```

Fluent extras: `.withTimeout(ms)`, `.withOnStart/OnComplete/OnError/OnTimeout/OnCancel(...)`,
`.requires(slots...)`, `.reset()`.

---

## Composition

### `SequentialAction`
Run children in order; stop on the first failure.
```java
new SequentialAction("deploy",
    Action.oneShot("drop", now -> arm.down()),
    WaitAction.ms("settle", 250),
    Action.oneShot("grab", now -> claw.close()));
```

### `ParallelAction`
Run children together. Modes: `all`, `any` (race — cancels losers), `allNoFail`.
```java
ParallelAction.all("prep", spinUp, aim, presort);
ParallelAction.any("first_sensor", waitS1, waitS2);   // completes on first finisher
```

### `RaceGroupAction`
Like `ParallelAction.any`, with optional `failFast` semantics and `firstN` style use.
```java
RaceGroupAction.race("detect", waitForBeam, timeoutGuard);
```

### `RepeatAction`
Repeat a child a fixed number of times or until a condition.
```java
RepeatAction.times("triple_tap", shootOne, 3);
RepeatAction.until("drain", shootOne, () -> magazine.isEmpty()).withMaxIterations(10);
```

---

## Control flow

### `IfAction`
```java
IfAction.ifThen("maybe_intake", () -> count < 3, intake);
IfAction.ifThenElse("aim_side", () -> red, aimRed, aimBlue);
```

### `SwitchAction` / `SwitchAction.ValueSwitch<T>`
Multi‑way branch on conditions or on a value.
```java
new SwitchAction("strategy")
    .addCase("empty", () -> count == 0, collect)
    .addCase("full",  () -> count >= 3, shootAll)
    .withDefault(idle);

new SwitchAction.ValueSwitch<Integer>("by_count", () -> count)
    .addCase(1, shootOne).addCase(2, shootTwo).withDefault(idle);
```

### `GuardedAction`
Run inner only if a guard holds; otherwise a no‑op success (or fail, if configured).
```java
GuardedAction.ifTrue("if_balls", () -> count > 0, shootAll);
GuardedAction.ifTrue("must", () -> ready, fire).failIfGuardFalse("not ready");
```

### `NoOpAction`
A placeholder that completes immediately. Handy as an else‑branch.
```java
NoOpAction.dummy();
```

---

## Timing & waits

### `WaitAction`
Non‑blocking delay (driven by injected time, **not** wall‑clock).
```java
WaitAction.ms("settle", 250);
WaitAction.seconds("dwell", 1.5);
```

### `WaitUntilAction`
Wait for a condition, with optional timeout.
```java
WaitUntilAction.until("at_speed", flywheel::ready).withTimeout(1500);
```

### `TimeoutAction`
Wrap an action with a hard deadline.
```java
new TimeoutAction("bounded_transfer", transfer, 2500);
```

### `DeadlineAction`
Run side actions only as long as a "deadline" action runs; finish when it does.
```java
DeadlineAction.with("feed_window", WaitAction.ms("window", 3000), feedHold);
```

### `Continuous`
A low‑overhead "run a lambda every tick" action (lighter than wrapping in `RepeatAction`).
```java
Continuous.forever("drive", now -> drive.field(gp));
Continuous.until("track", limelight::lost, now -> turret.track());
```

### `HoldAction`
Run a hold function (PID/feedforward) forever, or until a stop condition.
```java
HoldAction.position("arm_hold", now -> arm.holdPosition());
HoldAction.hold("turret", now -> turret.hold()).until(turret::locked);
```

---

## Driver input

### `EdgeTriggerAction`
Fire an inner action once on a rising/falling edge (no auto‑repeat while held).
```java
EdgeTriggerAction.rising("shoot", () -> gp.cross, fireOne);
```

### `ToggleAction`
Press to toggle ON/OFF, with optional continuous hold while ON.
```java
ToggleAction.onPress("intake", () -> gp.cross, intakeOn, intakeOff);
ToggleAction.onHold("intake", () -> gp.cross, intakeOn, intakeOff, filterWhileOn);
```

### `DoubleTapAction`
Require a double‑tap within a window — for dangerous one‑offs.
```java
DoubleTapAction.ms("endgame_park", () -> gp.options, parkSequence, 300);
```

### `DebounceAction`
Suppress re‑triggers within a cooldown.
```java
DebounceAction.ms("nudge", bumpServo, 100);
```

### `LatchAction`
Complete once a condition first becomes true (edge‑aware one‑shot).
```java
LatchAction.once("ball_seen", colorSensor::hasBall);
```

---

## Error handling

### `TryAction`
Swallow an inner failure (ERROR/TIMEOUT) so automation continues; optional fallback.
```java
TryAction.tryOnce("attempt", patternShot).withOnFail(anyColorShot);
```

### `FailsafeAction`
Retry and/or fall back when the primary fails.
```java
FailsafeAction.tryCatch("aim", limelightTrack, presetAim);
FailsafeAction.retryOrFallback("aim", track, 3, presetAim);
```

### `FailFastAction`
Trip to ERROR (or CANCELED) the moment a condition becomes true — use inside a
`ParallelAction` to abort the group.
```java
FailFastAction.ifTrue("lost_target", () -> !limelight.connected());
```

### `EnsureAction`
Run inner, then assert a postcondition; fail if it doesn't hold.
```java
EnsureAction.after("load", intake, () -> count == 3);
```

### `RequireAction`
Assert a precondition once; complete if true, error if false.
```java
RequireAction.that("has_ball", intake::hasBall);
```

### `FinallyAction`
Always run cleanup, success or failure; final state mirrors the primary.
```java
FinallyAction.wrap("shoot", shootSequence, Action.oneShot("close", now -> gates.close()));
```

---

## Rate control

### `RateLimitAction`
Limit how often an inner runs. Modes: `SKIP`, `WAIT`, `QUEUE_ONE`.
```java
RateLimitAction.ms("shoot", singleShot, 1500).withMode(RateLimitAction.Mode.WAIT);
RateLimitAction.hz("read", sensorRead, 5.0);
```

### `ThrottledAction`
Run an expensive inner's step at most once per interval.
```java
ThrottledAction.wrap(visionUpdate, 100);
ThrottledAction.throttledMonitor("telemetry", 500, () -> dash.update());
```

---

## Reliability & monitoring

### `WatchdogAction`
Run a protected action; if a trigger fires, run an emergency recovery.
```java
WatchdogAction.monitor("transfer_wd", transfer, () -> stuck(), unstick);
```

### `CancelOnAction`
Cancel an inner action when a condition becomes true.
```java
CancelOnAction.cancelIf("intake_limit", intakeHold, () -> count >= 3);
```

### `ManualOverrideAction`
Pause an automated inner whenever the driver takes manual control.
```java
ManualOverrideAction.when("aim", () -> Math.abs(gp.rightX) > 0.1, autoAim);
```

### `RetryUntilConfidentAction`
Retry an attempt until a confidence condition holds (or attempts run out).
```java
RetryUntilConfidentAction.of("lock", pulse, () -> tag.stable(), 10);
```

### `MetricAction`
Time an inner action and emit a summary.
```java
MetricAction.measure("shoot_metrics", shootSequence, msg -> telemetry.addLine(msg));
```

---

## Runner helpers (`com.teamundefined.defined.runner`)

### `ActionRunner`
Arbitrates slotted actions and ticks monitors. `startGroup`, `addMonitor`,
`cancelSlot`, `update(now)`, `emergencyCancelAllActions`.

### `ToggleStartGroupAction`
A slot‑free monitor: press a button to `startGroup` an action on the runner, press
again to swap to an "off" action. Auto‑resets to OFF if the started action cancels.

### `WhilePressedAction`
Run one action while a button is held and another when released.

---

## Pedro actions (`defined-pedro`)

### `NavigationAction`
The full‑featured "drive there" action: A→B (auto‑builds a `BezierLine`), pre‑built
or deferred `PathChain`, optimized minimal‑strafe paths, joystick override, waypoint
callbacks, and heading‑tolerant completion.
```java
// Simple A→B in autonomous:
NavigationAction.forAuto(follower, new Pose(48, 36, Math.toRadians(90)))
    .requiresDrive(Subsystem.DRIVE);

// TeleOp with driver override + minimal‑strafe path:
NavigationAction.optimizedWithOverride(follower, target, 0.3, 0.8,
        () -> Math.hypot(gamepad1.left_stick_x, gamepad1.left_stick_y) > 0.1)
    .requiresDrive(Subsystem.DRIVE);

// Fire callbacks along the way:
NavigationAction.forAuto(follower, target)
    .withTriggers(Arrays.asList(
        new NavigationAction.Waypoint(24, 36, t -> intake.setPower(1.0)),
        new NavigationAction.Waypoint(48, 48, t -> intake.setPower(0.0))));
```

### `PathUtils`
Helpers behind `NavigationAction`: `buildOptimizedPath(...)` (three‑segment,
minimal‑strafe) and `detectOptimalDriveDirection(...)`.

### `FollowPathAction`
A lighter alternative to `NavigationAction` when you just want to run a pre‑built
`PathChain` to completion as an action.
```java
FollowPathAction.follow("to_basket", follower, toBasket);
```

### `HeadingLockAction`
Field‑centric heading control that cooperates with Pedro's heading PID.
```java
new HeadingLockAction.Builder(follower)
    .rightStick(() -> -gp.right_stick_x, () -> -gp.right_stick_y)
    .snapTo45(true).build();
```

### `ZoneMonitor`
Fire callbacks when the robot enters/leaves a rectangular field zone.
```java
ZoneMonitor.rect("base", follower, 0, 0, 18, 18)
    .onEnter(() -> telemetry.addLine("Parked!"));
```
