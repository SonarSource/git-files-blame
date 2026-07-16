package org.sonar.scm.git.blame.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Opens the git repository enclosing a given folder, even when that folder is nested deep inside a
 * mono-repo. Also computes the repository-root-relative prefix of the folder so that only files under
 * it are blamed.
 */
public final class RepositoryLocator {

  private RepositoryLocator() {
  }

  public static LocatedRepository locate(Path folder) throws IOException {
    Repository repository = new FileRepositoryBuilder()
      .findGitDir(folder.toAbsolutePath().toFile())
      .readEnvironment()
      .build();
    if (repository.getDirectory() == null) {
      repository.close();
      throw new IllegalArgumentException("No git repository found for folder: " + folder);
    }

    Path workTree = repository.getWorkTree().toPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
    Path absoluteFolder = folder.toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!absoluteFolder.startsWith(workTree)) {
      repository.close();
      throw new IllegalArgumentException("Folder " + absoluteFolder + " is not inside the repository work tree " + workTree);
    }

    String prefix = workTree.relativize(absoluteFolder).toString().replace(File.separatorChar, '/');
    if (!prefix.isEmpty() && !prefix.endsWith("/")) {
      prefix += "/";
    }
    return new LocatedRepository(repository, workTree, prefix);
  }
}
