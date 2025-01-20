/*
 * Git Files Blame
 * Copyright (C) 2009-2025 SonarSource SA
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

import java.util.List;
import org.eclipse.jgit.revwalk.RevCommit;

public class CommitGraphNode extends GraphNode {
  private final RevCommit commit;

  public CommitGraphNode(RevCommit commit, int numberExpectedFiles) {
    super(numberExpectedFiles);
    this.commit = commit;
  }

  public CommitGraphNode(RevCommit commit, List<FileCandidate> files) {
    super(files);
    this.commit = commit;
  }

  @Override
  public int getParentCount() {
    return commit.getParentCount();
  }

  @Override
  public RevCommit getParentCommit(int number) {
    return commit.getParent(number);
  }

  @Override
  public RevCommit getCommit() {
    return commit;
  }

  @Override
  public int getTime() {
    return commit.getCommitTime();
  }

}
