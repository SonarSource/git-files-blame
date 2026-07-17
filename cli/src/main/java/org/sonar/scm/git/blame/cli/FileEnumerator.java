package org.sonar.scm.git.blame.cli;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Enumerates the set of files to blame under a folder by walking the tree of the start commit.
 * <p>
 * Only committed (tracked) files are considered. This honors {@code .gitignore} by construction:
 * ignored files (build outputs, dependencies, ...) are normally not committed, so they are never
 * blamed. It also means untracked files are skipped, which is expected since they have no history
 * to blame.
 */
public class FileEnumerator {

  private final List<PathMatcher> exclusions;

  public FileEnumerator(List<String> exclusionGlobs) {
    this.exclusions = new ArrayList<>(exclusionGlobs.size());
    for (String glob : exclusionGlobs) {
      exclusions.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
    }
  }

  public EnumerationResult enumerate(Repository repository, ObjectId startCommit, String pathPrefix) throws IOException {
    Set<String> filesToBlame = new HashSet<>();
    int totalFilesInRepo = 0;
    int filesUnderFolder = 0;
    int filesExcluded = 0;

    try (RevWalk revWalk = new RevWalk(repository);
      TreeWalk treeWalk = new TreeWalk(repository)) {
      RevCommit commit = revWalk.parseCommit(startCommit);
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);

      while (treeWalk.next()) {
        if (!isFile(treeWalk.getRawMode(0))) {
          continue;
        }
        totalFilesInRepo++;
        String path = treeWalk.getPathString();
        if (isUnderFolder(path, pathPrefix)) {
          filesUnderFolder++;
          if (isExcluded(path)) {
            filesExcluded++;
          } else {
            filesToBlame.add(path);
          }
        }
      }
    }

    return new EnumerationResult(filesToBlame, totalFilesInRepo, filesUnderFolder, filesExcluded);
  }

  private static boolean isUnderFolder(String path, String pathPrefix) {
    return pathPrefix.isEmpty() || path.startsWith(pathPrefix);
  }

  private boolean isExcluded(String path) {
    Path candidate = Path.of(path);
    return exclusions.stream().anyMatch(matcher -> matcher.matches(candidate));
  }

  private static boolean isFile(int rawMode) {
    return (rawMode & FileMode.TYPE_MASK) == FileMode.TYPE_FILE;
  }
}
