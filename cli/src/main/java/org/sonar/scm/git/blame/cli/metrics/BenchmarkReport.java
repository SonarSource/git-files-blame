package org.sonar.scm.git.blame.cli.metrics;

/**
 * All the measures collected during a single benchmark run. Rendered both as a human-readable summary
 * and as a JSON document so results from CLIs built on different branches can be compared.
 */
public record BenchmarkReport(
  String label,
  String repoRoot,
  String targetFolder,
  String startCommit,
  int totalFilesInRepo,
  int filesUnderFolder,
  int filesExcluded,
  int filesBlamed,
  long totalLinesBlamed,
  int commitIterations,
  int distinctCommitsAttributed,
  long blameDurationMs,
  long writeDurationMs,
  long totalDurationMs,
  long peakHeapMb,
  long maxHeapMb,
  long gcCount,
  long gcTimeMs,
  int filesWriteFailed) {

  public String toJson() {
    StringBuilder builder = new StringBuilder();
    builder.append("{\n");
    appendString(builder, "label", label, true);
    appendString(builder, "repoRoot", repoRoot, true);
    appendString(builder, "targetFolder", targetFolder, true);
    appendString(builder, "startCommit", startCommit, true);
    appendNumber(builder, "totalFilesInRepo", totalFilesInRepo, true);
    appendNumber(builder, "filesUnderFolder", filesUnderFolder, true);
    appendNumber(builder, "filesExcluded", filesExcluded, true);
    appendNumber(builder, "filesBlamed", filesBlamed, true);
    appendNumber(builder, "totalLinesBlamed", totalLinesBlamed, true);
    appendNumber(builder, "commitIterations", commitIterations, true);
    appendNumber(builder, "distinctCommitsAttributed", distinctCommitsAttributed, true);
    appendNumber(builder, "blameDurationMs", blameDurationMs, true);
    appendNumber(builder, "writeDurationMs", writeDurationMs, true);
    appendNumber(builder, "totalDurationMs", totalDurationMs, true);
    appendNumber(builder, "peakHeapMb", peakHeapMb, true);
    appendNumber(builder, "maxHeapMb", maxHeapMb, true);
    appendNumber(builder, "gcCount", gcCount, true);
    appendNumber(builder, "gcTimeMs", gcTimeMs, true);
    appendNumber(builder, "filesWriteFailed", filesWriteFailed, false);
    builder.append("}\n");
    return builder.toString();
  }

  public String toSummary() {
    return """
      Benchmark report [%s]
        repository            : %s
        target folder         : %s
        start commit          : %s
        files in repo (total) : %d
        files under folder    : %d
        files excluded        : %d
        files blamed          : %d
        lines blamed          : %d
        commit iterations     : %d
        distinct commits      : %d
        blame time            : %d ms
        write time            : %d ms
        total time            : %d ms
        peak heap             : %d MB (max %d MB)
        gc                    : %d collections, %d ms
        write failures        : %d
      """.formatted(label, repoRoot, targetFolder, startCommit, totalFilesInRepo, filesUnderFolder,
      filesExcluded, filesBlamed, totalLinesBlamed, commitIterations, distinctCommitsAttributed,
      blameDurationMs, writeDurationMs, totalDurationMs, peakHeapMb, maxHeapMb, gcCount, gcTimeMs,
      filesWriteFailed);
  }

  private static void appendString(StringBuilder builder, String key, String value, boolean comma) {
    builder.append("  \"").append(key).append("\": \"").append(escape(value)).append('"');
    builder.append(comma ? ",\n" : "\n");
  }

  private static void appendNumber(StringBuilder builder, String key, long value, boolean comma) {
    builder.append("  \"").append(key).append("\": ").append(value);
    builder.append(comma ? ",\n" : "\n");
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
