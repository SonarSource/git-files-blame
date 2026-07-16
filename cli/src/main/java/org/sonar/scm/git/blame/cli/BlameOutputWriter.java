package org.sonar.scm.git.blame.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.scm.git.blame.BlameResult.FileBlame;

/**
 * Writes each file's blame result to {@code outputDir/<relative path>.blame} using a virtual thread
 * per file. This phase always runs after the blame computation, so it never affects the blame timing;
 * it is still measured on its own.
 */
public class BlameOutputWriter {

  private static final Logger LOG = LoggerFactory.getLogger(BlameOutputWriter.class);
  private static final String BLAME_SUFFIX = ".blame";

  public WriteResult write(Collection<FileBlame> blames, Path outputDir, int concurrency) {
    WriteJob job = new WriteJob(outputDir, new Semaphore(concurrency), new ConcurrentHashMap<>(),
      new LongAdder(), new LongAdder(), new LongAdder());

    long start = System.nanoTime();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (FileBlame fileBlame : blames) {
        executor.submit(() -> writeOne(fileBlame, job));
      }
    }
    long durationNanos = System.nanoTime() - start;

    return new WriteResult(durationNanos, job.written().intValue(), job.lines().sum(), job.failed().intValue());
  }

  private void writeOne(FileBlame fileBlame, WriteJob job) {
    try {
      job.semaphore().acquire();
      try {
        Path target = job.outputDir().resolve(fileBlame.getPath() + BLAME_SUFFIX);
        ensureParentDir(target, job);
        Files.write(target, render(fileBlame));
        job.written().increment();
        job.lines().add(fileBlame.lines());
      } finally {
        job.semaphore().release();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      job.failed().increment();
    } catch (IOException | RuntimeException e) {
      LOG.warn("Failed to write blame for {}", fileBlame.getPath(), e);
      job.failed().increment();
    }
  }

  private static void ensureParentDir(Path target, WriteJob job) {
    Path parent = target.getParent();
    if (parent == null) {
      return;
    }
    // computeIfAbsent runs the creation at most once per directory and blocks concurrent callers
    // for the same key until it completes, avoiding both redundant syscalls and write-before-mkdir races.
    job.createdDirs().computeIfAbsent(parent, dir -> {
      try {
        Files.createDirectories(dir);
        return Boolean.TRUE;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    });
  }

  private static byte[] render(FileBlame fileBlame) {
    String[] commitHashes = fileBlame.getCommitHashes();
    Instant[] commitDates = fileBlame.getCommitDates();
    String[] authorEmails = fileBlame.getAuthorEmails();

    StringBuilder builder = new StringBuilder(fileBlame.lines() * 64);
    for (int line = 0; line < fileBlame.lines(); line++) {
      builder
        .append(commitHashes[line] != null ? commitHashes[line] : "")
        .append('\t')
        .append(commitDates[line] != null ? commitDates[line].toString() : "")
        .append('\t')
        .append(authorEmails[line] != null ? authorEmails[line] : "")
        .append('\n');
    }
    return builder.toString().getBytes(StandardCharsets.UTF_8);
  }

  private record WriteJob(Path outputDir, Semaphore semaphore, ConcurrentHashMap<Path, Boolean> createdDirs,
    LongAdder written, LongAdder lines, LongAdder failed) {
  }
}
