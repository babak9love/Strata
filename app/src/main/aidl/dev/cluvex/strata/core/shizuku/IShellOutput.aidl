package dev.cluvex.strata.core.shizuku;

/** Receives the output of a command while it is still running. */
oneway interface IShellOutput {
    void onOutput(String chunk);
}
