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

import java.util.Collection;
import java.util.List;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.scm.git.blame.CommitGraphNode.TIME_COMPARATOR;

public class CommitGraphNodeTest {

  @Test
  public void comparator_whenFakeCommit_thenIsLess() {
    WorkDirGraphNode fakeCommit = new WorkDirGraphNode(null, List.of());
    CommitGraphNode commit = new CommitGraphNode(getRevCommit(2000), 1);

    int compare = TIME_COMPARATOR.compare(fakeCommit, commit);

    assertThat(compare).isNegative();
  }

  @Test
  public void comparator_whenTwoSimiliarCommits_thenOrderThemByTime() {
    CommitGraphNode earlyCommit = new CommitGraphNode(getRevCommit(1000), 1);
    CommitGraphNode laterCommit = new CommitGraphNode(getRevCommit(2000), 1);

    int compare = TIME_COMPARATOR.compare(earlyCommit, laterCommit);

    assertThat(compare).isPositive();
  }

  @Test
  public void comparator_whenCommitsAreTheSame_thenOrderIsAlsoEqual() {
    RevCommit revCommit = getRevCommit(1000);
    CommitGraphNode commitA = new CommitGraphNode(revCommit, 1);
    CommitGraphNode commitB = new CommitGraphNode(revCommit, 1);

    int compare = TIME_COMPARATOR.compare(commitA, commitB);

    assertThat(compare).isZero();
  }

  @Test
  public void comparator_whenCommitsAreFromTheSameTime_thenOrderIsDependentOnUnderylingRevCommit() {
    CommitGraphNode commitA = new CommitGraphNode(getRevCommit(1000), 1);

    RevCommit revCommit = getRevCommit(1000);
    when(revCommit.compareTo(any(AnyObjectId.class))).thenReturn(1);

    int compare = TIME_COMPARATOR.compare(commitA, new CommitGraphNode(revCommit, 1));

    assertThat(compare).isPositive();
  }

  @Test
  public void getFilesByPath_whenKeyDoesntExist_thenReturnsEmptyCollection() {
    CommitGraphNode underTest = new CommitGraphNode(getRevCommit(1000), 1);

    Collection<FileCandidate> emptyCollection = underTest.getFilesByPath("path");

    assertThat(emptyCollection).isEmpty();
  }

  @Test
  public void getFilesByPath_whenKeyDoesExist_thenReturnsNotEmptyCollection() {
    List<FileCandidate> fileCandidateList = List.of(fileCandidate("path"));
    CommitGraphNode underTest = new CommitGraphNode(getRevCommit(1000), fileCandidateList);

    Collection<FileCandidate> oneElementCollection = underTest.getFilesByPath("path");

    assertThat(oneElementCollection).hasSize(1);
  }

  @Test
  public void addFile_whenFileAdded_thenItUpdatesBothCollections() {
    CommitGraphNode underTest = new CommitGraphNode(getRevCommit(1000), 1);

    underTest.addFile(fileCandidate("path"));

    assertThat(underTest.getAllFiles()).hasSize(1);
    assertThat(underTest.getAllPaths()).hasSize(1);
  }

  @Test
  public void toString_whenCommitHasHash_thenPrintPartOfIt() {
    RevCommit revCommit = getRevCommit(1000);
    AbbreviatedObjectId shortId = mock(AbbreviatedObjectId.class);
    when(shortId.name()).thenReturn("abcdef");
    when(revCommit.abbreviate(anyInt())).thenReturn(shortId);

    CommitGraphNode underTest = new CommitGraphNode(revCommit, 1);

    assertThat(underTest.toString()).contains("abcdef");
  }

  private FileCandidate fileCandidate(String path) {
    return new FileCandidate(path, path, null);
  }

  private RevCommit getRevCommit(int time) {
    RevCommit mock = mock(RevCommit.class);
    when(mock.getCommitTime()).thenReturn(time);
    return mock;
  }
}
