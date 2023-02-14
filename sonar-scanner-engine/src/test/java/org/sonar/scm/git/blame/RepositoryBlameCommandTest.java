package org.sonar.scm.git.blame;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonar.scm.git.blame.BlameResult.FileBlame;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sonar.scm.git.GitUtils.copyFile;
import static org.sonar.scm.git.GitUtils.createFile;
import static org.sonar.scm.git.GitUtils.createRepository;
import static org.sonar.scm.git.GitUtils.moveFile;

public class RepositoryBlameCommandTest {
  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private Path baseDir;
  private Git git;
  private RepositoryBlameCommand blame;

  @Before
  public void prepare() throws IOException {
    baseDir = createNewTempFolder();
    git = createRepository(baseDir);
    blame = new RepositoryBlameCommand(git.getRepository());
  }

  /**
   * fileA gets copied to fileC, and also renamed to fileB. One will be a RENAME, the other will be a COPY.
   * We should blame everything to the first commit that originally created fileA.
   */
  @Test
  public void detect_rename_and_copy() throws IOException, GitAPIException {
    createFile(baseDir, "fileA", "line1", "line2", "line3", "line4", "line5", "line6", "line7");
    String c1 = commit("fileA");

    copyFile(baseDir, "fileA", "fileB");
    moveFile(baseDir, "fileA", "fileC");
    rm("fileA");
    commit("fileB", "fileC");

    BlameResult result = blame.call();

    assertThat(result.getFileBlames())
      .extracting(FileBlame::getPath).containsOnly("fileB", "fileC");
    Collection<String> allBlameCommits = result.getFileBlames().stream()
      .flatMap(f -> Arrays.stream(f.getCommits()))
      .map(AnyObjectId::getName)
      .collect(Collectors.toList());

    assertThat(allBlameCommits).containsOnly(c1);
  }

  /**
   * For whatever reason we can't add deleted files to the index with 'git add'. Needs to be done explicitly with 'git rm'.
   */
  private void rm(String... paths) throws GitAPIException {
    RmCommand add = git.rm();
    for (String p : paths) {
      add.addFilepattern(p);
    }
    add.call();
  }

  private String commit(String... paths) throws GitAPIException {
    AddCommand add = git.add();
    for (String p : paths) {
      add.addFilepattern(p);
    }
    add.call();
    RevCommit commit = git.commit().setCommitter("joe", "email@email.com").setMessage("msg").call();
    return commit.getName();
  }

  private Path createNewTempFolder() throws IOException {
    // This is needed for Windows, otherwise the created File point to invalid (shortened by Windows) temp folder path
    return temp.newFolder().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
  }
}
