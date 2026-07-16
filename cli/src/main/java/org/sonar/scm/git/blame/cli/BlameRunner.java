package org.sonar.scm.git.blame.cli;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.sonar.scm.git.blame.BlameResult;
import org.sonar.scm.git.blame.RepositoryBlameCommand;

/**
 * Runs {@link RepositoryBlameCommand} and measures the time it takes. The progress callback only
 * increments a counter to keep the measured hot path as light as possible.
 */
public class BlameRunner {

  public BlameRunResult run(Repository repository, ObjectId startCommit, Set<String> filePaths, boolean multithreading) throws GitAPIException {
    AtomicInteger iterations = new AtomicInteger();
    RepositoryBlameCommand command = new RepositoryBlameCommand(repository)
      .setStartCommit(startCommit)
      .setFilePaths(filePaths)
      .setMultithreading(multithreading)
      .setProgressCallBack((iterationNb, commitHash) -> iterations.incrementAndGet());

    long start = System.nanoTime();
    BlameResult blameResult = command.call();
    long durationNanos = System.nanoTime() - start;

    return new BlameRunResult(blameResult, durationNanos, iterations.get());
  }
}
