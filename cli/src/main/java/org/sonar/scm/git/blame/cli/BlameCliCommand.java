package org.sonar.scm.git.blame.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.sonar.scm.git.blame.BlameResult;
import org.sonar.scm.git.blame.BlameResult.FileBlame;
import org.sonar.scm.git.blame.cli.metrics.BenchmarkReport;
import org.sonar.scm.git.blame.cli.metrics.GcStats;
import org.sonar.scm.git.blame.cli.metrics.MemorySampler;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Entry point of the git-files-blame benchmark CLI. Blames every tracked file under a folder and
 * collects timing, memory and volume measures into a report.
 */
@Command(
  name = "git-files-blame",
  mixinStandardHelpOptions = true,
  versionProvider = ManifestVersionProvider.class,
  description = "Blame all tracked files under a folder and collect benchmark measures.")
public class BlameCliCommand implements Callable<Integer> {

  private static final long BYTES_PER_MB = 1024L * 1024L;
  private static final long NANOS_PER_MS = 1_000_000L;

  @Parameters(index = "0", arity = "0..1", defaultValue = ".",
    description = "Folder to blame. May be a sub-folder of a mono-repo. Default: current directory.")
  private Path folder;

  @Option(names = {"-o", "--output-dir"}, defaultValue = "blame-output",
    description = "Directory where .blame files are written. Default: ${DEFAULT-VALUE}.")
  private Path outputDir;

  @Option(names = {"-e", "--exclude"}, paramLabel = "GLOB",
    description = "Glob pattern of repo-relative paths to exclude. Repeatable, e.g. -e '**/*.min.js' -e 'gen/**'.")
  private List<String> exclusions = new ArrayList<>();

  @Option(names = "--single-threaded",
    description = "Disable the library's multithreaded blame (multithreading is enabled by default).")
  private boolean singleThreaded;

  @Option(names = "--no-write-output",
    description = "Skip writing .blame files, to measure blame time in isolation (files are written by default).")
  private boolean skipWriteOutput;

  @Nullable
  @Option(names = "--start-commit", description = "Commit to start the blame from. Default: HEAD.")
  private String startCommit;

  @Nullable
  @Option(names = "--label", description = "Label identifying this build in the report. Default: the CLI version.")
  private String label;

  @Nullable
  @Option(names = "--report", description = "Path of the JSON report file. Default: <output-dir>/benchmark-report.json.")
  private Path reportFile;

  private int writeConcurrency = Runtime.getRuntime().availableProcessors();

  @Spec
  private CommandSpec spec;

  @Option(names = "--write-concurrency", description = "Max number of files written concurrently. Default: number of CPUs.")
  private void setWriteConcurrency(int value) {
    if (value < 1) {
      throw new ParameterException(spec.commandLine(), "--write-concurrency must be >= 1, but was " + value + ".");
    }
    this.writeConcurrency = value;
  }

  @Option(names = "--memory-sample-interval-ms", defaultValue = "50",
    description = "Heap sampling interval in milliseconds. Default: ${DEFAULT-VALUE}.")
  private long memorySampleIntervalMs;

  @Override
  public Integer call() throws Exception {
    long totalStart = System.nanoTime();
    LocatedRepository located = RepositoryLocator.locate(folder);
    try (Repository repository = located.repository()) {
      ObjectId startCommitId = resolveStartCommit(repository);
      EnumerationResult enumeration = new FileEnumerator(exclusions)
        .enumerate(repository, startCommitId, located.pathPrefix());

      GcStats gcBefore = GcStats.snapshot();
      MemorySampler sampler = new MemorySampler(memorySampleIntervalMs);
      sampler.start();

      BlameRunResult blameRun = new BlameRunner()
        .run(repository, startCommitId, enumeration.filesToBlame(), !singleThreaded);
      WriteResult writeResult = skipWriteOutput
        ? WriteResult.skipped()
        : new BlameOutputWriter().write(blameRun.blameResult().getFileBlames(), outputDir, writeConcurrency);

      sampler.stop();
      GcStats gc = GcStats.snapshot().minus(gcBefore);
      long totalDurationNanos = System.nanoTime() - totalStart;

      BenchmarkReport report = buildReport(located, startCommitId, enumeration, blameRun, writeResult,
        sampler.peakHeapUsedBytes(), gc, totalDurationNanos);
      emitReport(report);
      return report.filesWriteFailed() == 0 ? 0 : 1;
    }
  }

  private ObjectId resolveStartCommit(Repository repository) throws IOException {
    String revision = startCommit != null ? startCommit : Constants.HEAD;
    ObjectId resolved = repository.resolve(revision);
    if (resolved == null) {
      throw new IllegalStateException("Could not resolve start commit: " + revision);
    }
    return resolved;
  }

  private BenchmarkReport buildReport(LocatedRepository located, ObjectId startCommitId, EnumerationResult enumeration,
    BlameRunResult blameRun, WriteResult writeResult, long peakHeapBytes, GcStats gc, long totalDurationNanos) {
    BlameResult blameResult = blameRun.blameResult();
    Path targetFolder = located.pathPrefix().isEmpty() ? located.workTree() : located.workTree().resolve(located.pathPrefix());
    return new BenchmarkReport(
      label != null ? label : ManifestVersionProvider.version(),
      located.workTree().toString(),
      targetFolder.toString(),
      startCommitId.getName(),
      enumeration.totalFilesInRepo(),
      enumeration.filesUnderFolder(),
      enumeration.filesExcluded(),
      blameResult.getFileBlames().size(),
      totalLines(blameResult),
      blameRun.iterations(),
      distinctCommits(blameResult),
      blameRun.durationNanos() / NANOS_PER_MS,
      writeResult.durationNanos() / NANOS_PER_MS,
      totalDurationNanos / NANOS_PER_MS,
      peakHeapBytes / BYTES_PER_MB,
      Runtime.getRuntime().maxMemory() / BYTES_PER_MB,
      gc.count(),
      gc.timeMs(),
      writeResult.filesFailed());
  }

  private void emitReport(BenchmarkReport report) throws IOException {
    Path target = reportFile != null ? reportFile : outputDir.resolve("benchmark-report.json");
    Path parent = target.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(target, report.toJson(), StandardCharsets.UTF_8);
    System.out.print(report.toSummary());
    System.out.println("Report written to " + target);
  }

  private static long totalLines(BlameResult blameResult) {
    long total = 0;
    for (FileBlame fileBlame : blameResult.getFileBlames()) {
      total += fileBlame.lines();
    }
    return total;
  }

  private static int distinctCommits(BlameResult blameResult) {
    Set<String> distinct = new HashSet<>();
    for (FileBlame fileBlame : blameResult.getFileBlames()) {
      for (String hash : fileBlame.getCommitHashes()) {
        if (hash != null) {
          distinct.add(hash);
        }
      }
    }
    return distinct.size();
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new BlameCliCommand()).execute(args);
    System.exit(exitCode);
  }
}
