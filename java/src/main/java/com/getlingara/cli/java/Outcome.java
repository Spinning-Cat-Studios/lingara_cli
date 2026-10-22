package com.getlingara.cli.java;

import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;

/** One run's result: its exit, the buffered stdout, and the refusal, if any. */
public record Outcome(Exit exit, byte[] stdout, Fail fail) {}
