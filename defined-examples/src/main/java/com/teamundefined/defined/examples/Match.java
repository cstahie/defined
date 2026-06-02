package com.teamundefined.defined.examples;

import com.teamundefined.defined.examples.opmodes.DummyAuto;
import com.teamundefined.defined.examples.opmodes.DummyTeleOp;

/**
 * Runs a full "match" against the simulated robot — autonomous, then teleop — so you
 * can watch the Defined engine drive a structured robot end to end:
 *
 * <pre>{@code ./gradlew :defined-examples:run}</pre>
 */
public class Match {
    public static void main(String[] args) {
        System.out.println("=== Defined example robot — a full simulated match ===\n");

        System.out.println("--- AUTONOMOUS ---");
        DummyRobot auto = DummyAuto.run(true);

        System.out.println("\n--- TELEOP ---");
        DummyRobot teleop = DummyTeleOp.run(true);

        System.out.println("\nDone. AUTO scored " + auto.indexer.scored()
                + ", TELEOP scored " + teleop.indexer.scored() + ".");
    }
}
