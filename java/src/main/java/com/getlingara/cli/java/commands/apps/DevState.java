package com.getlingara.cli.java.commands.apps;

import com.getlingara.cli.java.supervise.Group;
import com.getlingara.cli.java.supervise.Tunnel;
import java.util.ArrayList;
import java.util.List;

/** The children of one {@code apps dev} session and what it pointed at, for step 8 and the report. */
final class DevState {
    private static final long STOP_GRACE_MS = 5000;

    Group app;
    Tunnel tunnel;
    final List<String> urls = new ArrayList<>();
    /** The session ended under us (taken over, expired): no {@code DELETE}. */
    boolean lost;
    int respawns;

    /** cloudflared first, then the app's tree. */
    void stopChildren() {
        if (tunnel != null) {
            tunnel.stop();
        }
        if (app != null) {
            app.stop(STOP_GRACE_MS);
        }
    }
}
