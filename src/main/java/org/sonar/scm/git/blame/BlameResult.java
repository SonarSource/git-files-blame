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
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class BlameResult {
  private final Map<String, FileBlame> fileBlameByPath = new HashMap<>();

  public Collection<FileBlame> getFileBlames() {
    return fileBlameByPath.values();
  }

  public Map<String, FileBlame> getFileBlameByPath() {
    return fileBlameByPath;
  }

  public void initialize(String path, int size) {
    fileBlameByPath.put(path, new FileBlame(path, size));
  }

  public void process(String commitHash, Date commitDate, String authorEmail, FileCandidate fileCandidate) {

    while (fileCandidate.getRegionList() != null) {
      int resLine = fileCandidate.getRegionList().resultStart;
      int resEnd = getResultEnd(fileCandidate.getRegionList());

      FileBlame fileBlame = fileBlameByPath.get(fileCandidate.getOriginalPath());
      for (; resLine < resEnd; resLine++) {
        fileBlame.commitHashes[resLine] = commitHash;
        fileBlame.commitDates[resLine] = commitDate;
        fileBlame.authorEmails[resLine] = authorEmail;
      }
      fileCandidate.setRegionList(fileCandidate.getRegionList().next);
    }
  }

  private static int getResultEnd(Region r) {
    return r.resultStart + r.length;
  }

  public static class FileBlame {
    private final String path;
    private final String[] commitHashes;
    private final Date[] commitDates;
    private final String[] authorEmails;

    public FileBlame(String path, int numberLines) {
      this.path = path;
      this.commitHashes = new String[numberLines];
      this.commitDates = new Date[numberLines];
      this.authorEmails = new String[numberLines];
    }

    public String getPath() {
      return path;
    }


    public String[] getCommitHashes() {
      return commitHashes;
    }

    public Date[] getCommitDates() {
      return commitDates;
    }

    public String[] getAuthorEmails() {
      return authorEmails;
    }

    public int lines() {
      return commitHashes.length;
    }
  }
}
