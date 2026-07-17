package org.sonar.scm.git.blame.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

/**
 * Base class for CLI integration tests. Creates a throwaway git repository in a temp folder and
 * exposes small helpers to add files and commits.
 */
abstract class AbstractCliIT {

  @TempDir
  protected Path repoDir;

  protected Git git;

  @BeforeEach
  void setUpRepository() throws IOException {
    Repository repository = FileRepositoryBuilder.create(repoDir.resolve(".git").toFile());
    repository.create();
    git = new Git(repository);
  }

  @AfterEach
  void tearDownRepository() {
    git.getRepository().close();
    git.close();
  }

  protected void writeFile(String relativePath, String content) throws IOException {
    Path file = repoDir.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  protected void commitAll(String message) throws Exception {
    git.add().addFilepattern(".").call();
    git.commit().setCommitter("tester", "tester@example.com").setMessage(message).call();
  }
}
