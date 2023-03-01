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
