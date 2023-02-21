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
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.GitCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * A command, similar to Blame, which collects the blame for multiple files in the repository
 */
public class RepositoryBlameCommand extends GitCommand<BlameResult> {
  private DiffAlgorithm diffAlgorithm = new HistogramDiff();
  private RawTextComparator textComparator = RawTextComparator.DEFAULT;
  private ObjectId startCommit = null;
  private Set<String> filePaths = null;
  private boolean multithreading = false;

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
    this.startCommit = commit.toObjectId();
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

  @Override
  public BlameResult call() throws GitAPIException {
    BlameResult blameResult = new BlameResult();

    try {
      ObjectId commit = startCommit != null ? startCommit : getHead();
      BlobReader blobReader = new BlobReader();
      FilteredRenameDetector filteredRenameDetector = new FilteredRenameDetector(repo);
      FileTreeComparator fileTreeComparator = new FileTreeComparator(filteredRenameDetector);
      FileBlamer fileBlamer = new FileBlamer(fileTreeComparator, diffAlgorithm, textComparator, blobReader, blameResult, multithreading);
      StatefulCommitFactory statefulCommitFactory = new StatefulCommitFactory(filePaths);
      BlameGenerator blameGenerator = new BlameGenerator(repo, fileBlamer, statefulCommitFactory);
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
}
