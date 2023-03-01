package org.sonar.scm.git.blame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Represents a node we are traversing in the graph, and holds all the files amd their regions left to blame
 */
public abstract class GraphNode {
  public static final Comparator<GraphNode> TIME_COMPARATOR = Comparator
    .comparing(GraphNode::getTime)
    .thenComparing(GraphNode::getCommit, Comparator.nullsFirst(Comparator.naturalOrder())).reversed();

  // There can be multiple FileCandidate per path (in this commit) because there can be multiple original paths
  // being blamed that end up matching the same file in this commit.
  private final Map<String, List<FileCandidate>> filesByPath;
  // For performance, we keep the full list instead of collecting all files from filesByPath
  private final List<FileCandidate> allFiles;

  GraphNode(int expectedNumFiles) {
    this.filesByPath = new HashMap<>(expectedNumFiles);
    this.allFiles = new ArrayList<>(expectedNumFiles);
  }

  GraphNode(List<FileCandidate> files) {
    this.filesByPath = files.stream().collect(Collectors.groupingBy(FileCandidate::getPath));
    this.allFiles = files;
  }

  /**
   * Get files given their path in the commit that this object represents
   */
  public Collection<FileCandidate> getFilesByPath(String filePath) {
    return filesByPath.getOrDefault(filePath, List.of());
  }

  public Set<String> getAllPaths() {
    return filesByPath.keySet();
  }

  public Collection<FileCandidate> getAllFiles() {
    return allFiles;
  }

  public void addFile(FileCandidate fileCandidate) {
    filesByPath.computeIfAbsent(fileCandidate.getPath(), k -> new LinkedList<>()).add(fileCandidate);
    allFiles.add(fileCandidate);
  }

  /**
   * The commit is null when this object represents the working directory.
   */
  @CheckForNull
  public abstract RevCommit getCommit();

  public abstract int getParentCount();

  public abstract RevCommit getParentCommit(int i);

  public abstract int getTime();

  @Override
  public String toString() {
    StringBuilder r = new StringBuilder();
    r.append("Commit[");
    if (getCommit() != null) {
      r.append(" @ ").append(getCommit().abbreviate(6).name());
    }
    r.append("]");
    return r.toString();
  }

  /**
   * Two {@link CommitGraphNode} equal if they represent the same commit.
   * Should be consistent with {@link #hashCode} and with {@link #TIME_COMPARATOR}.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CommitGraphNode that = (CommitGraphNode) o;
    return Objects.equals(getCommit(), that.getCommit()) && Objects.equals(getTime(), that.getTime());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getCommit(), getTime());
  }
}
