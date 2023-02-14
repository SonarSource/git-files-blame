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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.eclipse.jgit.revwalk.RevCommit;

public class StatefulCommit {
  public static final Comparator<StatefulCommit> TIME_COMPARATOR = Comparator
    .comparingInt(StatefulCommit::getTime)
    .thenComparing(StatefulCommit::getCommit);

  private final RevCommit sourceCommit;
  private final Map<String, List<FileCandidate>> filesByPath;

  StatefulCommit(RevCommit commit, List<FileCandidate> files) {
    this.sourceCommit = commit;
    this.filesByPath = files.stream().collect(Collectors.groupingBy(FileCandidate::getPath));
  }

  /**
   * Get files given their path in the commit that this object represents
   */
  public Collection<FileCandidate> getFilesByPath(String filePath) {
    return filesByPath.getOrDefault(filePath, List.of());
  }

  public Collection<FileCandidate> getAllFiles() {
    return filesByPath.values().stream().flatMap(Collection::stream).collect(Collectors.toList());
  }

  RevCommit getCommit() {
    return sourceCommit;
  }

  int getParentCount() {
    return sourceCommit.getParentCount();
  }

  RevCommit getParentCommit(int number) {
    return sourceCommit.getParent(number);
  }

  int getTime() {
    return sourceCommit.getCommitTime();
  }

  @Override
  public String toString() {
    StringBuilder r = new StringBuilder();
    r.append("Commit[");
    if (sourceCommit != null) {
      r.append(" @ ").append(sourceCommit.abbreviate(6).name());
    }
    r.append("]");
    return r.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    StatefulCommit that = (StatefulCommit) o;
    return Objects.equals(sourceCommit, that.sourceCommit);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourceCommit);
  }
}
