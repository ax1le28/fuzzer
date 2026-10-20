package io.promptfuzzer.cli;

import java.io.PrintStream;
import java.util.Map;

/** Test helper: runs the CLI with a fixed environment. */
public final class CliAccess {

    private CliAccess() {
    }

    public static int run(Map<String, String> env, PrintStream out, String[] args) {
        return new Cli(env::get, out, out).execute(args);
    }
}
