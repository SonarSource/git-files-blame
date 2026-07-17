package org.sonar.scm.git.blame.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class BlameCliCommandIT extends AbstractCliIT {

  @Test
  void run_writesBlameFilesAndReport() throws Exception {
    writeFile("README.md", "root\n");
    writeFile("src/main/Foo.java", "line1\nline2\nline3\n");
    writeFile("src/main/Bar.java", "only\n");
    commitAll("initial");

    Path outputDir = repoDir.resolve("out");
    int exitCode = new CommandLine(new BlameCliCommand()).execute(
      repoDir.resolve("src").toString(), "-o", outputDir.toString(), "--label", "unit-test");

    assertThat(exitCode).isZero();

    Path fooBlame = outputDir.resolve("src/main/Foo.java.blame");
    Path barBlame = outputDir.resolve("src/main/Bar.java.blame");
    assertThat(fooBlame).exists();
    assertThat(barBlame).exists();
    assertThat(Files.readAllLines(fooBlame)).hasSize(3);
    assertThat(Files.readAllLines(barBlame)).hasSize(1);

    Path report = outputDir.resolve("benchmark-report.json");
    assertThat(report).exists();
    String json = Files.readString(report);
    assertThat(json)
      .contains("\"label\": \"unit-test\"")
      .contains("\"totalFilesInRepo\": 3")
      .contains("\"filesBlamed\": 2")
      .contains("\"totalLinesBlamed\": 4")
      .contains("\"filesWriteFailed\": 0");
  }

  @Test
  void run_withNonPositiveWriteConcurrency_failsInsteadOfHanging() throws Exception {
    writeFile("src/Foo.java", "a\nb\n");
    commitAll("initial");

    Path outputDir = repoDir.resolve("out");
    int exitCode = new CommandLine(new BlameCliCommand()).execute(
      repoDir.resolve("src").toString(), "-o", outputDir.toString(), "--write-concurrency", "0");

    assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
    assertThat(outputDir.resolve("src/Foo.java.blame")).doesNotExist();
  }

  @Test
  void run_withNoWriteOutput_doesNotWriteBlameFiles() throws Exception {
    writeFile("src/Foo.java", "a\nb\n");
    commitAll("initial");

    Path outputDir = repoDir.resolve("out");
    int exitCode = new CommandLine(new BlameCliCommand()).execute(
      repoDir.resolve("src").toString(), "-o", outputDir.toString(), "--no-write-output");

    assertThat(exitCode).isZero();
    assertThat(outputDir.resolve("src/Foo.java.blame")).doesNotExist();
    // the report is still produced
    assertThat(outputDir.resolve("benchmark-report.json")).exists();
  }
}
