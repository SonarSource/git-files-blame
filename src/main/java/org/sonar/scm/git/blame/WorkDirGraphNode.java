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
import java.util.Objects;
import javax.annotation.CheckForNull;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * This graph node represents the working directory. It's the first node we iterate.
 */
public class WorkDirGraphNode extends GraphNode {
  private final RevCommit parentCommit;

  public WorkDirGraphNode(RevCommit parentCommit, List<FileCandidate> filePaths) {
    super(filePaths);
    this.parentCommit = parentCommit;
  }

  @CheckForNull
  @Override
  public RevCommit getCommit() {
    return null;
  }

  @Override
  public int getParentCount() {
    return 1;
  }

  @Override
  public RevCommit getParentCommit(int i) {
    Objects.checkIndex(i, 1);
    return parentCommit;
  }

  @Override
  public int getTime() {
    // returning max value ensures that this node is processed before any other node.
    return Integer.MAX_VALUE;
  }
}
