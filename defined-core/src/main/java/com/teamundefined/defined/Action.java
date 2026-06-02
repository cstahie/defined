package com.teamundefined.defined;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

public class Action {
    public static final long UNSET = -1;
    public enum ActionState {
        NONE,
        RUNNING,
        COMPLETE,
        TIMEOUT,
        ERROR,
        CANCELED
    }

    public String name;
    protected ActionState state = ActionState.NONE;
    protected long timeout = UNSET; //milliseconds
    protected long startTime = UNSET; //milliseconds
    protected long endTime = UNSET; //milliseconds

    protected LongConsumer step;
    protected BooleanSupplier isComplete = null;

    protected LongConsumer onStart = null;
    protected LongConsumer onComplete = null;
    protected LongConsumer onError = null;
    protected LongConsumer onCancel = null;
    protected LongConsumer onCancelCleanup = null;

    protected LongConsumer onTimeout = null;

    protected String errorMessage = null;

    protected Set<Slot> requiredSlots = new HashSet<>();

    public static Action oneShot(String name, LongConsumer step) {
        return new Action(name, step, null, UNSET);
    }

    public static Action until(String name, LongConsumer step, BooleanSupplier isComplete, long timeoutMs) {
        return new Action(name, step, isComplete, timeoutMs);
    }

    public static Action until(String name, LongConsumer step, BooleanSupplier isComplete) {
        return new Action(name, step, isComplete, UNSET);
    }

    public Action(String name, LongConsumer step) {
        this(name, step, null, UNSET);
    }

    public Action(String name, LongConsumer step, long timeout) {
       this(name, step, null, timeout);
    }
    public Action(String name, LongConsumer step, BooleanSupplier isComplete, long timeout) {
        this.name = (name != null && !name.isBlank()) ? name : "Action@" + Integer.toHexString(System.identityHashCode(this));
        this.timeout = timeout;
        this.step = step;
        this.isComplete = isComplete;
    }

    public boolean inTerminalState() {
        return state == ActionState.COMPLETE || state == ActionState.ERROR || state == ActionState.TIMEOUT || state == ActionState.CANCELED;
    }

    public ActionState update(long nowMillis) {
        // Skip if already ended.
        if (this.inTerminalState())
            return state;

        if (step == null)
            return endActionWithError("Action step is null");

        // Fresh start
        if (state == ActionState.NONE) {
            state = ActionState.RUNNING;
            startTime = nowMillis;
            Log.i("ACTION_STATE_MACHINE", 20, () -> "Action " + name + " started");

            // callback onStart
            if (onStart != null) {
                try {
                    onStart.accept(startTime);
                } catch (Exception e) {
                    return endActionWithError("ERR onStart: " + e.toString());
                }
            }
        }

        // Check timeout if set
        if (state == ActionState.RUNNING && timeout != UNSET && nowMillis - startTime >= timeout) {
            Log.i("ACTION_STATE_MACHINE", 20, () -> "Action " + name + " timedout");
            return endActionWithTimeout("Action timed out");
        }

        // Run step
        try {
            step.accept(nowMillis);
        } catch (Exception e) {
            return endActionWithError("ERR On Run: " + e.toString());
        }

        // If step ended us (error/timeout), stop here
        if (inTerminalState()) {
            Log.i("ACTION_STATE_MACHINE", 20, () -> "Action " + name + " state is " + state);
            return state;
        }

        // Check completion
        try {
            if (isComplete == null || isComplete.getAsBoolean()) {
                return endAction(ActionState.COMPLETE);
            }
        } catch (Exception e) {
            return endActionWithError("ERR ON isComplete: " + e.toString());
        }

        return state;
    }

    public ActionState cancel() {
        return endActionWithCancel("Canceled");
    }

    public ActionState cancel(String reason) {
        return endActionWithCancel(reason);
    }


    protected ActionState endActionWithError(String errorMessage) {
        Log.i("ACTION_STATE_MACHINE", 20, () -> "Action " + name + " error: " + errorMessage);

        if (this.inTerminalState()) {
            return this.state;
        }

        this.state = ActionState.ERROR;
        this.endTime = System.currentTimeMillis();
        this.errorMessage = errorMessage + " Action=[" + this.name + "]";

        if (onError != null) {
            try {
                onError.accept(this.endTime);
            } catch (Exception e) {
                this.errorMessage = "ERR onError: " + e + " caused first by " + this.errorMessage;
            }
        }

        return this.state;
    }

    protected ActionState endActionWithTimeout(String errorMessage){
        if (this.inTerminalState()) {
            return this.state;
        }
        this.state = ActionState.TIMEOUT;
        this.endTime = System.currentTimeMillis();
        this.errorMessage = errorMessage;

        if(onTimeout != null) {
            try {
                onTimeout.accept(this.endTime);
            } catch (Exception e) {
                this.errorMessage = "ERR onTimeout: " + e.toString();
                this.state = ActionState.ERROR;
            }
        }

        return this.state;
    }

    protected ActionState endActionWithCancel(String reason) {
        if (this.inTerminalState()) return this.state;

        this.state = ActionState.CANCELED;
        this.endTime = System.currentTimeMillis();

        // Reuse errorMessage as "status message" for terminal diagnostics
        if (reason != null && !reason.isBlank()) {
            this.errorMessage = "CANCELED because " + reason + " Action=[" + this.name + "]";
        }

        if (onCancel != null) {
            try {
                onCancel.accept(this.endTime);
            } catch (Exception e) {
                // Don't flip state; callback exceptions shouldn't change outcome
                if (this.errorMessage == null) this.errorMessage = "";
                this.errorMessage = "ERR onCancel: " + e + " caused first by " + this.errorMessage;
                this.state = ActionState.ERROR;
            }
        }

        if (onCancelCleanup != null) {
            try {
                onCancelCleanup.accept(this.endTime);
            } catch (Exception e) {
                // Don't flip state; callback exceptions shouldn't change outcome
                if (this.errorMessage == null) this.errorMessage = "";
                this.errorMessage = "ERR onCancelCleanup: " + e + " caused first by " + this.errorMessage;
                this.state = ActionState.ERROR;
            }
        }

        return this.state;
    }

    protected ActionState endAction(ActionState state) {
        if (this.inTerminalState()) {
            return this.state;
        }
        this.state = state;
        this.endTime = System.currentTimeMillis();

        if (this.state == ActionState.COMPLETE && onComplete != null) {
            try {
                onComplete.accept(this.endTime);
            } catch (Exception e) {
                this.errorMessage = "ERR onComplete: " + e.toString();
                this.state = ActionState.ERROR;
            }
        }

        return this.state;
    }

    public ActionState getState() {
        return state;
    }

    public long getTimeout() { return timeout; }
    public Action withTimeout(long timeout) { this.timeout = timeout; return this; }

    public long getStartTime() { return startTime; }
    public long getCompleteDurationMs() { return endTime > 0 && startTime > 0 ? endTime - startTime : -1; }
    public long getElapsed(long nowMillis) { return nowMillis > 0 && startTime > 0 ? nowMillis - startTime : -1; }



    public LongConsumer getStep() { return step; }
    public Action withStep(LongConsumer step) {
        if (!ensureMutable("withStep")) return this;
        this.step = step;
        return this;
    }

    public BooleanSupplier getIsComplete() { return isComplete; }

    public Action withIsComplete(BooleanSupplier isComplete) {
        if (!ensureMutable("withIsComplete")) return this;
        this.isComplete = isComplete;
        return this;
    }

    // Chain callbacks instead of replacing - run existing callback first, then new one
    public Action withOnStart(LongConsumer onStart) {
        LongConsumer existing = this.onStart;
        this.onStart = (existing != null) ? existing.andThen(onStart) : onStart;
        return this;
    }
    public Action withOnComplete(LongConsumer onComplete) {
        LongConsumer existing = this.onComplete;
        this.onComplete = (existing != null) ? existing.andThen(onComplete) : onComplete;
        return this;
    }
    public Action withOnError(LongConsumer onError) {
        LongConsumer existing = this.onError;
        this.onError = (existing != null) ? existing.andThen(onError) : onError;
        return this;
    }
    public Action withOnTimeout(LongConsumer onTimeout) {
        LongConsumer existing = this.onTimeout;
        this.onTimeout = (existing != null) ? existing.andThen(onTimeout) : onTimeout;
        return this;
    }
    public Action withOnCancel(LongConsumer onCancel) {
        LongConsumer existing = this.onCancel;
        this.onCancel = (existing != null) ? existing.andThen(onCancel) : onCancel;
        return this;
    }
    public Action withOnCancelCleanup(LongConsumer onCancelCleanup) {
        LongConsumer existing = this.onCancelCleanup;
        this.onCancelCleanup = (existing != null) ? existing.andThen(onCancelCleanup) : onCancelCleanup;
        return this;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Set<Slot> requiredSlots() { return requiredSlots; }

    public Action reset() {
        state = ActionState.NONE;
        startTime = UNSET;
        endTime = UNSET;
        errorMessage = null;
        return this;
    }

    public Action requires(Slot... slots) {
        for (Slot s : slots) {
            requiredSlots.add(s);
        }
        return this;
    }

    protected boolean ensureMutable(String op) {
        if (state == ActionState.RUNNING) {
            endActionWithError("Attempted to modify while running (" + op + ") Action=[" + name + "]");
            return false;
        }
        // If you want, also block modifications after terminal
        // endActionWithError("Attempted to modify after terminal (" + op + ") ...");
        return !inTerminalState();
    }

    @Override
    public String toString() {
        return this.name + "[" + this.state.toString() + "]" + (this.errorMessage!=null ? " E: " + this.errorMessage : "");
    }
}
