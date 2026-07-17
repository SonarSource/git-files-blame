package org.sonar.scm.git.blame.cli;

import org.sonar.scm.git.blame.BlameResult;

/**
 * Result of a timed blame execution.
 *
 * @param blameResult   the blame produced by the library
 * @param durationNanos wall-clock duration of the {@code call()} in nanoseconds
 * @param iterations    number of commit iterations reported by the progress callback (a commit may be
 *                      visited more than once, so this is not a distinct-commit count)
 */
public record BlameRunResult(BlameResult blameResult, long durationNanos, int iterations) {
}
