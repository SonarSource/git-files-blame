/*
 * SonarQube
 * Copyright (C) 2009-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scm.git.blame;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.GitCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.scm.git.blame.diff.RenameDetector;

import static java.util.Optional.ofNullable;

/**
 * A command, similar to Blame, which collects the blame for multiple files in the repository
 */
public class RepositoryBlameCommand extends GitCommand<BlameResult> {
  private static final Logger LOG = LoggerFactory.getLogger(RepositoryBlameCommand.class);
  private DiffAlgorithm diffAlgorithm = new HistogramDiff();
  private RawTextComparator textComparator = RawTextComparator.DEFAULT;
  private ObjectId startCommit = null;
  private Set<String> filePaths = null;
  private boolean multithreading = false;
  private BiConsumer<Integer, String> progressCallBack;

  public RepositoryBlameCommand(Repository repo) {
    super(repo);
  }

  /**
   * Sets the diff algorithm used to compute the differences between 2 files
   */
  public RepositoryBlameCommand setDiffAlgorithm(DiffAlgorithm diffAlgorithm) {
    this.diffAlgorithm = diffAlgorithm;
    return this;
  }

  /**
   * Whether multiple threads should be used to speed up the computation. Defaults to false.
   */
  public RepositoryBlameCommand setMultithreading(boolean multithreading) {
    this.multithreading = multithreading;
    return this;
  }

  /**
   * @param commit a commit Object ID or null to use HEAD
   */
  public RepositoryBlameCommand setStartCommit(@Nullable AnyObjectId commit) {
    this.startCommit = commit != null ? commit.toObjectId() : null;
    return this;
  }

  /**
   * Sets a text comparator that will be used by the diff algorithm to compute the difference between 2 files
   */
  public RepositoryBlameCommand setTextComparator(RawTextComparator textComparator) {
    this.textComparator = textComparator;
    return this;
  }

  /**
   * If set, only the provided file paths will be blamed.
   *
   * @param filePaths Files to blame. If null, all committed files will be blamed.
   */
  public RepositoryBlameCommand setFilePaths(@Nullable Set<String> filePaths) {
    this.filePaths = filePaths;
    return this;
  }

  /**
   * Add a callback to check the progress of the algorithm
   * @param progressCallBack Consumer to be called each time a commit is processed by the algorithm.
   * First parameter of the callback is the commit iteration number, second is the commit hash that is starting to be processed.
   * A commit can be processed multiple time in the algorithm. (Depending on branching and merging)
   */
  public RepositoryBlameCommand setProgressCallBack(BiConsumer<Integer, String> progressCallBack) {
    this.progressCallBack = progressCallBack;
    return this;
  }

  @Override
  public BlameResult call() throws GitAPIException {
    BlameResult blameResult = new BlameResult();

    try {
      ObjectId commit = startCommit != null ? startCommit : getHead();
      BlobReader blobReader = new BlobReader();
      FilteredRenameDetector filteredRenameDetector = new FilteredRenameDetector(new RenameDetector(repo));
      FileTreeComparator fileTreeComparator = new FileTreeComparator(filteredRenameDetector);
      FileBlamer fileBlamer = new FileBlamer(fileTreeComparator, diffAlgorithm, textComparator, blobReader, blameResult, multithreading);

      Set<String> filteredFilePaths = Optional.ofNullable(filePaths).map(e -> filterUncommittedFiles(e, repo))
        .orElse(null);

      if (filteredFilePaths != null && filteredFilePaths.isEmpty()) {
        return blameResult;
      }

      StatefulCommitFactory statefulCommitFactory = new StatefulCommitFactory(filteredFilePaths);
      BlameGenerator blameGenerator = new BlameGenerator(repo, fileBlamer, statefulCommitFactory, progressCallBack);
      blameGenerator.compute(commit);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to blame repository files", e);
    }
    return blameResult;
  }

  private ObjectId getHead() throws IOException, NoHeadException {
    ObjectId head = repo.resolve(Constants.HEAD);
    if (head == null) {
      throw new NoHeadException(MessageFormat.format(JGitText.get().noSuchRefKnown, Constants.HEAD));
    }
    return head;
  }

  private static Set<String> filterUncommittedFiles(Set<String> inputFilePaths, Repository repo) {

    try {
      Optional<ObjectId> headCommit = ofNullable(repo.resolve(Constants.HEAD));

      if (headCommit.isEmpty()) {
        LOG.warn("Could not find HEAD commit");
        return Collections.emptySet();
      }

      Set<String> uncommittedFiles;

      try (RevWalk revWalk = new RevWalk(repo)) {
        RevCommit head = revWalk.parseCommit(headCommit.get());
        uncommittedFiles = collectUncommittedFilesOnCommit(repo, head);
      }

      Set<String> filteredFiles = new HashSet<>(inputFilePaths);
      Set<String> removedFiles = new HashSet<>();
      uncommittedFiles.forEach(uncommittedFile -> {
        if (filteredFiles.remove(uncommittedFile)) {
          removedFiles.add(uncommittedFile);
        }
      });

      if (!removedFiles.isEmpty()) {
        LOG.debug("The following files will not be blamed because they have uncommitted changes: {}", removedFiles);
      }
      return filteredFiles;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to find all committed files", e);
    }
  }

  private static Set<String> collectUncommittedFilesOnCommit(Repository repo, RevCommit head) throws IOException {

    CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
    ObjectReader oldReader = repo.newObjectReader();
    oldTreeParser.reset(oldReader, head.getTree());

    TreeWalk walk = new TreeWalk(repo);
    walk.addTree(head.getTree());
    walk.addTree(new FileTreeIterator(repo));
    walk.setRecursive(true);
    List<DiffEntry> diffEntries = DiffEntry.scan(walk);
    return diffEntries.stream().map(d -> {
      if (Objects.requireNonNull(d.getChangeType()) == DiffEntry.ChangeType.DELETE) {
        return d.getOldPath();
      }
      // RENAME change type cannot happen since we didn't enable RenameDetector
      return d.getNewPath();
    }).collect(Collectors.toSet());
  }

}
