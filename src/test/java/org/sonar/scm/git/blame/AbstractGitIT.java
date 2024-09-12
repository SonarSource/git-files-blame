/*
 * Git Files Blame
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import javax.annotation.CheckForNull;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import static org.sonar.scm.git.GitUtils.createRepository;

public abstract class AbstractGitIT {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  protected Path baseDir;
  protected Git git;
  protected RepositoryBlameCommand blame;

  @Before
  public void prepare() throws IOException {
    baseDir = createNewTempFolder();
    git = createRepository(baseDir);
    blame = new RepositoryBlameCommand(git.getRepository());
  }

  /**
   * For whatever reason we can't add deleted files to the index with 'git add'. It needs to be done explicitly with 'git rm'.
   */
  protected void rm(String... paths) throws GitAPIException {
    RmCommand rm = git.rm();
    for (String p : paths) {
      rm.addFilepattern(p);
    }
    rm.call();
  }

  /**
   * returns null if there's a conflict
   */
  @CheckForNull
  protected String merge(String commit) throws IOException, GitAPIException {
    ObjectId c = git.getRepository().resolve(commit);
    ObjectId newHead = git.merge().setFastForward(MergeCommand.FastForwardMode.NO_FF).include(c).call().getNewHead();
    return newHead != null ? newHead.getName() : null;
  }

  protected void resetHard(String commit) throws GitAPIException {
    git.reset()
      .setMode(ResetCommand.ResetType.HARD)
      .setRef(commit)
      .call();
  }

  protected String commitMsg(String msg, String... paths) throws GitAPIException {
    AddCommand add = git.add();
    for (String p : paths) {
      add.addFilepattern(p);
    }
    add.call();
    RevCommit commit = git.commit().setCommitter("joe", "email@email.com").setMessage(msg).call();
    return commit.getName();
  }

  protected String commit(long dateInMs, String... paths) throws GitAPIException {
    if (paths.length > 0) {
      AddCommand add = git.add();
      for (String p : paths) {
        add.addFilepattern(p);
      }
      add.call();
    }
    PersonIdent ident = new PersonIdent("joe", "email@email.com", dateInMs, 0);
    RevCommit commit = git.commit().setCommitter(ident).setAuthor(ident).setMessage("msg").call();
    return commit.getName();
  }

  protected String commit(String... paths) throws GitAPIException {
    if (paths.length > 0) {
      AddCommand add = git.add();
      for (String p : paths) {
        add.addFilepattern(p);
      }
      add.call();
    }
    RevCommit commit = git.commit().setCommitter("joe", "email@email.com").setMessage("msg").call();
    return commit.getName();
  }

  Path createNewTempFolder() throws IOException {
    // This is needed for Windows, otherwise the created File point to invalid (shortened by Windows) temp folder path
    return temp.newFolder().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
  }
}
