/*
 * Git Files Blame
 * Copyright (C) SonarSource Sàrl
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
package org.sonar.scm.git.blame.compare;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the native {@code git} binary, used as the ground truth to compare this library's blame
 * against. Runs with an isolated {@code HOME}/{@code GIT_CONFIG_GLOBAL} under the work directory and with
 * system config disabled, so a developer's own {@code ~/.gitconfig} (custom {@code diff.renames}, mailmap,
 * credential helpers, ...) can never perturb the comparison.
 */
public final class GitCli {

  private static final long COMMAND_TIMEOUT_SECONDS = 600;

  private final Path isolatedHome;

  public GitCli(Path isolatedHome) {
    this.isolatedHome = isolatedHome;
  }

  public void clone(String sourceUrl, Path dest, boolean noCheckout, boolean blobless) {
    List<String> args = new ArrayList<>(List.of("clone"));
    if (noCheckout) {
      args.add("--no-checkout");
    }
    if (blobless) {
      args.add("--filter=blob:none");
    }
    args.add(sourceUrl);
    args.add(dest.toString());
    run(args, dest.getParent());
  }

  public void sparseCheckoutInitCone(Path repo) {
    run(List.of("sparse-checkout", "init", "--cone"), repo);
  }

  public void sparseCheckoutSet(Path repo, List<String> paths) {
    List<String> args = new ArrayList<>(List.of("sparse-checkout", "set"));
    args.addAll(paths);
    run(args, repo);
  }

  public void checkout(Path repo, String ref) {
    run(List.of("checkout", ref), repo);
  }

  /**
   * Deletes a checked-out {@code .mailmap}, if any, so native git's blame doesn't rewrite historical author
   * identities to their canonical form. This library reads author identity straight off the commit object and
   * has no mailmap support (JGit has none - see
   * <a href="https://github.com/eclipse-jgit/jgit/issues/260">jgit#260</a>), so leaving a real-world repo's
   * {@code .mailmap} in place would make every mailmap'd author a spurious divergence. Native git only reads
   * this file from the working tree (not a committed blob) in a non-bare repo, so deleting it here is enough -
   * no config override is needed.
   */
  public void disableMailmap(Path repo) {
    try {
      Files.deleteIfExists(repo.resolve(".mailmap"));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete .mailmap in " + repo, e);
    }
  }

  /**
   * Runs native {@code git blame} configured to match this library's blame engine exactly, so the comparison
   * isolates genuine divergences rather than known algorithm differences (see {@code README.md}, "Blame
   * semantics vs native git"):
   * <ul>
   *   <li>{@code -w} / {@code WS_IGNORE_ALL}: ignore whitespace, mirroring the production {@code
   *       CompositeBlameCommand} caller in the scanner engine.</li>
   *   <li>{@code --diff-algorithm=histogram}: the library uses JGit's {@code HistogramDiff}, not native git's
   *       default Myers.</li>
   *   <li>{@code -c diff.indentHeuristic=false}: JGit's {@code HistogramDiff} does not apply git's indent
   *       heuristic, so hunk boundaries are placed without it on both sides.</li>
   *   <li>no {@code -M}: the library does not perform intra-file move/copy line detection, so native must not
   *       either (with {@code -M}, copied boilerplate lines get attributed to their original source commit).</li>
   * </ul>
   */
  public String blameLinePorcelain(Path repo, String ref, String path) {
    return run(List.of(
      "-c", "diff.indentHeuristic=false",
      "blame", "--line-porcelain", "-w", "--diff-algorithm=histogram", ref, "--", path), repo);
  }

  private String run(List<String> args, Path cwd) {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(args);
    try {
      Files.createDirectories(isolatedHome);
      ProcessBuilder builder = new ProcessBuilder(command)
        .directory(cwd.toFile())
        .redirectErrorStream(false);
      Map<String, String> env = builder.environment();
      env.put("GIT_TERMINAL_PROMPT", "0");
      env.put("GIT_CONFIG_NOSYSTEM", "1");
      env.put("HOME", isolatedHome.toString());
      env.put("GIT_CONFIG_GLOBAL", isolatedHome.resolve(".gitconfig-empty").toString());
      env.put("LC_ALL", "C");

      Process process = builder.start();
      ExecutorService readers = Executors.newFixedThreadPool(2);
      try {
        Future<String> stdout = readers.submit(() -> readFully(process.getInputStream()));
        Future<String> stderr = readers.submit(() -> readFully(process.getErrorStream()));

        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
          process.destroyForcibly();
          throw new IllegalStateException("Command timed out after " + COMMAND_TIMEOUT_SECONDS + "s: " + command);
        }
        String out = stdout.get();
        String err = stderr.get();
        if (process.exitValue() != 0) {
          throw new IllegalStateException("Command failed (exit " + process.exitValue() + "): " + command + "\n" + err);
        }
        return out;
      } finally {
        readers.shutdown();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to run " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running " + command, e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Failed to capture output of " + command, e.getCause());
    }
  }

  private static String readFully(InputStream in) throws IOException {
    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
  }
}
