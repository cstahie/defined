package com.teamundefined.defined.pedro;

import com.pedropathing.follower.Follower;
import com.pedropathing.paths.PathChain;

import com.teamundefined.defined.Action;

/**
 * Runs a Pedro Pathing {@link PathChain} to completion as a Defined {@link Action},
 * so path following composes naturally inside sequences and parallels.
 *
 * <pre>{@code
 * Action auto = new SequentialAction("auto",
 *     FollowPathAction.follow("to_basket", follower, toBasket),
 *     robotActions.scoreThree(),
 *     FollowPathAction.follow("to_park", follower, toPark));
 * }</pre>
 *
 * <p>The action starts the path on its first tick and completes once
 * {@link Follower#isBusy()} reports the follower is idle. You must still call
 * {@link Follower#update()} every loop (e.g. from your OpMode) — this action only
 * commands and monitors the path, it does not drive the control loop.
 *
 * <p>Give it a timeout (via {@link Action#withTimeout(long)} or by wrapping in a
 * {@code TimeoutAction}) to guard against a path that never settles.
 */
public class FollowPathAction extends Action {

    private final Follower follower;
    private final PathChain path;
    private final boolean holdEnd;
    private boolean started = false;

    private FollowPathAction(String name, Follower follower, PathChain path, boolean holdEnd) {
        super(name, now -> {});
        this.follower = follower;
        this.path = path;
        this.holdEnd = holdEnd;
        this.step = this::runStep;
        this.isComplete = () -> started && !follower.isBusy();
    }

    /** Follow {@code path}, holding the end pose once reached. */
    public static FollowPathAction follow(String name, Follower follower, PathChain path) {
        return new FollowPathAction(name, follower, path, true);
    }

    /** Follow {@code path} without actively holding the final pose. */
    public static FollowPathAction followNoHold(String name, Follower follower, PathChain path) {
        return new FollowPathAction(name, follower, path, false);
    }

    private void runStep(long nowMillis) {
        if (follower == null) {
            endActionWithError("FollowPathAction follower is null Action=[" + name + "]");
            return;
        }
        if (path == null) {
            endActionWithError("FollowPathAction path is null Action=[" + name + "]");
            return;
        }
        if (!started) {
            follower.followPath(path, holdEnd);
            started = true;
        }
    }

    @Override
    public ActionState cancel(String reason) {
        if (follower != null && started && !inTerminalState()) {
            follower.breakFollowing();
        }
        return super.cancel(reason);
    }

    @Override
    public Action reset() {
        super.reset();
        started = false;
        return this;
    }
}
