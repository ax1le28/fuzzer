package io.promptfuzzer;

import io.promptfuzzer.cli.Cli;

/** Entry point: {@code serve} (default), {@code generate}, {@code run}, or {@code demo}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(new Cli(System.getenv()::get, System.out, System.err).execute(args));
    }
}
