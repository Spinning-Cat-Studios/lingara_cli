package com.getlingara.cli.java;

import com.getlingara.cli.java.core.Output;

/**
 * What one command sees: the app, its streams and the profile. {@code profileChosen} is true when
 * {@code --profile} or {@code LINGARA_PROFILE} chose it, so {@code platform.toml}'s {@code
 * [platform] profile} may not (16.10.26ae D4).
 */
public final class Ctx {
    public final App app;
    public final Output out;
    public String profile;
    public final boolean profileChosen;

    public Ctx(App app, Output out, String profile, boolean profileChosen) {
        this.app = app;
        this.out = out;
        this.profile = profile;
        this.profileChosen = profileChosen;
    }
}
