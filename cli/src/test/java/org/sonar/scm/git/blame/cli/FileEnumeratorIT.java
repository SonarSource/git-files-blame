package org.sonar.scm.git.blame.cli;

import java.util.List;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileEnumeratorIT extends AbstractCliIT {

  @Test
  void enumerate_countsWholeRepoButBlamesOnlyFilesUnderFolder() throws Exception {
    writeFile("README.md", "root\n");
    writeFile("src/main/Foo.java", "a\nb\n");
    writeFile("src/main/Bar.java", "c\n");
    writeFile("other/Baz.java", "d\n");
    commitAll("initial");

    LocatedRepository located = RepositoryLocator.locate(repoDir.resolve("src"));
    try (Repository repository = located.repository()) {
      ObjectId head = repository.resolve("HEAD");
      EnumerationResult result = new FileEnumerator(List.of()).enumerate(repository, head, located.pathPrefix());

      assertThat(result.totalFilesInRepo()).isEqualTo(4);
      assertThat(result.filesUnderFolder()).isEqualTo(2);
      assertThat(result.filesExcluded()).isZero();
      assertThat(result.filesToBlame()).containsExactlyInAnyOrder("src/main/Foo.java", "src/main/Bar.java");
    }
  }

  @Test
  void enumerate_appliesExclusionGlobs() throws Exception {
    writeFile("src/main/Foo.java", "a\n");
    writeFile("src/main/Bar.java", "b\n");
    writeFile("src/generated/Gen.java", "c\n");
    commitAll("initial");

    LocatedRepository located = RepositoryLocator.locate(repoDir.resolve("src"));
    try (Repository repository = located.repository()) {
      ObjectId head = repository.resolve("HEAD");
      EnumerationResult result = new FileEnumerator(List.of("**/generated/**", "**/Bar.java"))
        .enumerate(repository, head, located.pathPrefix());

      assertThat(result.filesUnderFolder()).isEqualTo(3);
      assertThat(result.filesExcluded()).isEqualTo(2);
      assertThat(result.filesToBlame()).containsExactly("src/main/Foo.java");
    }
  }

  @Test
  void enumerate_wholeRepoWhenFolderIsRoot() throws Exception {
    writeFile("A.java", "a\n");
    writeFile("sub/B.java", "b\n");
    commitAll("initial");

    LocatedRepository located = RepositoryLocator.locate(repoDir);
    try (Repository repository = located.repository()) {
      ObjectId head = repository.resolve("HEAD");
      EnumerationResult result = new FileEnumerator(List.of()).enumerate(repository, head, located.pathPrefix());

      assertThat(located.pathPrefix()).isEmpty();
      assertThat(result.totalFilesInRepo()).isEqualTo(2);
      assertThat(result.filesToBlame()).containsExactlyInAnyOrder("A.java", "sub/B.java");
    }
  }
}
