package org.sonar.scm.git.blame.cli;

/**
 * Outcome of writing blame results to disk.
 *
 * @param durationNanos wall-clock duration of the whole write phase in nanoseconds
 * @param filesWritten  number of {@code .blame} files successfully written
 * @param linesWritten  total number of blame lines written across all files
 * @param filesFailed   number of files that could not be written
 */
public record WriteResult(long durationNanos, int filesWritten, long linesWritten, int filesFailed) {

  public static WriteResult skipped() {
    return new WriteResult(0, 0, 0, 0);
  }
}
