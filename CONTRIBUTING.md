# Contributing to Defined

Thanks for helping make FTC robot code better for everyone! 🤖

## Ground rules

1. **Every action ships with a deterministic test.** Drive actions with injected
   timestamps (`update(now)`) — no `Thread.sleep`, no wall‑clock. See the existing
   tests in `defined-core/src/test` for the pattern.
2. **Keep the core pure Java.** `defined-core` must not depend on Android, the FTC
   SDK, or Pedro. Anything platform‑specific belongs in `defined-ftc` / `defined-pedro`.
3. **No cost when idle.** The robot loop is sacred. Avoid allocations in the hot path
   and keep logging behind the `Log` facade (it's a no‑op until a sink is installed).
4. **Minimum code that solves the problem.** Nothing speculative.

## Development

```bash
./gradlew :defined-core:test     # fast — pure Java, no Android
./gradlew build                  # everything, incl. Android AARs (needs the Android SDK)
./gradlew :defined-examples:run  # run the simulated robot
```

The Android modules require a `local.properties` with `sdk.dir=...` pointing at your
Android SDK. The pure‑Java modules (`core`, `examples`) need only a JDK (8+).

## Adding a new action

1. Create the class in `com.teamundefined.defined.generic` (core) extending `Action`.
2. Wire `step` and `isComplete` in the constructor; expose clear static factories.
3. Add a test in the matching `*ActionsTest` that reads as a worked example.
4. Add an entry to [docs/ACTIONS.md](docs/ACTIONS.md).

## Commit style

Conventional commits (`feat:`, `fix:`, `docs:`, `test:`). Keep messages explaining
the *why*, not just the *what*.

## Code of conduct

Be kind. This is a student‑led project for a student community.
