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
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

public class BlameResult {
  private final Map<String, FileBlame> fileBlameByPath = new HashMap<>();

  public Collection<FileBlame> getFileBlames() {
    return fileBlameByPath.values();
  }

  public void initialize(String path, int size) {
    fileBlameByPath.put(path, new FileBlame(path, size));
  }

  public void process(RevCommit commit, FileCandidate fileCandidate) {
    PersonIdent srcAuthor = commit.getAuthorIdent();

    while (fileCandidate.getRegionList() != null) {
      int resLine = fileCandidate.getRegionList().resultStart;
      int resEnd = getResultEnd(fileCandidate.getRegionList());

      FileBlame fileBlame = fileBlameByPath.get(fileCandidate.getPath());
      for (; resLine < resEnd; resLine++) {
        fileBlame.commits[resLine] = commit;
        fileBlame.authors[resLine] = srcAuthor;
      }
      fileCandidate.setRegionList(fileCandidate.getRegionList().next);
    }
  }

  private static int getResultEnd(Region r) {
    return r.resultStart + r.length;
  }

  public static class FileBlame {
    private final String path;
    private final RevCommit[] commits;
    private final PersonIdent[] authors;

    public FileBlame(String path, int numberLines) {
      this.path = path;
      this.commits = new RevCommit[numberLines];
      this.authors = new PersonIdent[numberLines];
    }

    public String getPath() {
      return path;
    }

    public RevCommit[] getCommits() {
      return commits;
    }

    public PersonIdent[] getAuthors() {
      return authors;
    }

    public int lines() {
      return commits.length;
    }
  }
}
