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
  private final Map<String, FileCandidate> filesByPath;

  StatefulCommit(RevCommit commit, List<FileCandidate> files) {
    this.sourceCommit = commit;
    this.filesByPath = files.stream().collect(Collectors.toMap(FileCandidate::getPath, f -> f));
  }

  public FileCandidate getFile(String filePath) {
    return filesByPath.get(filePath);
  }

  public Collection<FileCandidate> getFiles() {
    return filesByPath.values();
  }

  public void removeFilesWithoutRegions() {
    filesByPath.entrySet().removeIf(next -> next.getValue().getRegionList() == null);
  }

  public int linesToBlame() {
    return filesByPath.values().stream().mapToInt(f -> linesInRegionList(f.getRegionList())).sum();
  }

  private static int linesInRegionList(Region r) {
    int size = 0;
    while (r != null) {
      size += r.length;
      r = r.next;
    }
    return size;
  }

  RevCommit getCommit() {
    return sourceCommit;
  }

  int getParentCount() {
    return sourceCommit.getParentCount();
  }

  RevCommit getParentCommit() {
    return sourceCommit.getParent(0);
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
