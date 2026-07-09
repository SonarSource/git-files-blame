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
package org.sonar.scm.git.blame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;

/**
 * A previously computed {@link BlameResult}, captured at a specific "boundary" commit and compacted into
 * per-path runs of identical attribution. Passing this to {@link RepositoryBlameCommand#setBoundaryBlame(BoundaryBlame)}
 * lets {@link BlameGenerator} stop walking history as soon as it reaches the boundary commit, resolving any
 * regions still unblamed at that point directly from this cache instead of continuing into the boundary
 * commit's own parents.
 * <p>
 * Only correct if the boundary commit is still an ancestor of the commit being blamed. Callers are responsible
 * for checking that (e.g. no rebase/force-push/squash since the boundary was captured) before calling
 * {@link RepositoryBlameCommand#setBoundaryBlame(BoundaryBlame)}.
 */
public final class BoundaryBlame {
  private static final int MAGIC = 0x424F5544; // "BOUD"
  private static final byte FORMAT_VERSION = 1;

  private final ObjectId boundaryCommit;
  private final Map<String, List<Run>> runsByPath;

  private BoundaryBlame(ObjectId boundaryCommit, Map<String, List<Run>> runsByPath) {
    this.boundaryCommit = boundaryCommit;
    this.runsByPath = runsByPath;
  }

  /**
   * @param boundaryCommit the commit at which {@code blameAtBoundary} was computed, i.e. the {@code startCommit}
   *                        that was passed to the {@link RepositoryBlameCommand} that produced it
   * @param blameAtBoundary a complete blame result computed with {@code setStartCommit(boundaryCommit)}
   */
  public static BoundaryBlame capture(AnyObjectId boundaryCommit, BlameResult blameAtBoundary) {
    Map<String, List<Run>> runsByPath = new HashMap<>();
    for (BlameResult.FileBlame fileBlame : blameAtBoundary.getFileBlames()) {
      runsByPath.put(fileBlame.getPath(), compact(fileBlame));
    }
    return new BoundaryBlame(boundaryCommit.toObjectId(), runsByPath);
  }

  private static List<Run> compact(BlameResult.FileBlame fileBlame) {
    String[] hashes = fileBlame.getCommitHashes();
    Instant[] dates = fileBlame.getCommitDates();
    String[] emails = fileBlame.getAuthorEmails();
    int numberLines = fileBlame.lines();

    List<Run> runs = new ArrayList<>();
    int line = 0;
    while (line < numberLines) {
      int runStart = line;
      String hash = hashes[line];
      Instant date = dates[line];
      String email = emails[line];
      do {
        line++;
      } while (line < numberLines && Objects.equals(hashes[line], hash) && Objects.equals(dates[line], date) && Objects.equals(emails[line], email));
      runs.add(new Run(runStart, line, hash, date, email));
    }
    return runs;
  }

  ObjectId getBoundaryCommit() {
    return boundaryCommit;
  }

  /**
   * Serializes this cache to {@code out}, e.g. to feed {@code WriteCache.write(key, InputStream)}. Does not close
   * {@code out}; the caller controls its lifecycle.
   */
  public void writeTo(OutputStream out) throws IOException {
    DataOutputStream data = new DataOutputStream(out);
    data.writeInt(MAGIC);
    data.writeByte(FORMAT_VERSION);
    boundaryCommit.copyRawTo(data);

    // commit metadata (hash/author/date) is repeated across many runs; store it once per distinct commit and have
    // each run reference it by index
    Map<String, Integer> commitIndexByHash = new LinkedHashMap<>();
    Map<String, Run> firstRunByHash = new HashMap<>();
    for (List<Run> runs : runsByPath.values()) {
      for (Run run : runs) {
        String hash = run.commitHash();
        if (hash != null && commitIndexByHash.putIfAbsent(hash, commitIndexByHash.size()) == null) {
          firstRunByHash.put(hash, run);
        }
      }
    }

    data.writeInt(commitIndexByHash.size());
    for (String hash : commitIndexByHash.keySet()) {
      Run representative = firstRunByHash.get(hash);
      ObjectId.fromString(hash).copyRawTo(data);
      writeNullableUTF(data, representative.authorEmail());
      writeNullableLong(data, representative.commitDate() != null ? representative.commitDate().getEpochSecond() : null);
    }

    data.writeInt(runsByPath.size());
    for (Map.Entry<String, List<Run>> entry : runsByPath.entrySet()) {
      data.writeUTF(entry.getKey());
      List<Run> runs = entry.getValue();
      data.writeInt(runs.size());
      for (Run run : runs) {
        data.writeInt(run.start());
        data.writeInt(run.endExclusive());
        data.writeInt(run.commitHash() != null ? commitIndexByHash.get(run.commitHash()) : -1);
      }
    }
    data.flush();
  }

  public byte[] toByteArray() {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try {
      writeTo(bos);
    } catch (IOException e) {
      // ByteArrayOutputStream never throws
      throw new UncheckedIOException(e);
    }
    return bos.toByteArray();
  }

  /**
   * Parses a cache previously produced by {@link #writeTo(OutputStream)}, e.g. read from {@code ReadCache.read(key)}.
   * Throws {@link IOException} on any format mismatch (including a version written by an incompatible release of
   * this library) - callers should treat that as "no usable cache" and fall back to a full blame, the same as if
   * the entry were simply absent.
   */
  public static BoundaryBlame readFrom(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    int magic = data.readInt();
    if (magic != MAGIC) {
      throw new IOException("Not a BoundaryBlame cache entry (unexpected header)");
    }
    byte version = data.readByte();
    if (version != FORMAT_VERSION) {
      throw new IOException("Unsupported BoundaryBlame cache format version: " + version);
    }

    byte[] rawId = new byte[Constants.OBJECT_ID_LENGTH];
    data.readFully(rawId);
    ObjectId boundaryCommit = ObjectId.fromRaw(rawId);

    int numCommits = data.readInt();
    String[] hashByIndex = new String[numCommits];
    String[] emailByIndex = new String[numCommits];
    Instant[] dateByIndex = new Instant[numCommits];
    for (int i = 0; i < numCommits; i++) {
      data.readFully(rawId);
      hashByIndex[i] = ObjectId.fromRaw(rawId).getName();
      emailByIndex[i] = readNullableUTF(data);
      Long epochSecond = readNullableLong(data);
      dateByIndex[i] = epochSecond != null ? Instant.ofEpochSecond(epochSecond) : null;
    }

    int numPaths = data.readInt();
    Map<String, List<Run>> runsByPath = new HashMap<>(numPaths);
    for (int p = 0; p < numPaths; p++) {
      String path = data.readUTF();
      int numRuns = data.readInt();
      List<Run> runs = new ArrayList<>(numRuns);
      for (int r = 0; r < numRuns; r++) {
        int start = data.readInt();
        int end = data.readInt();
        int commitIndex = data.readInt();
        runs.add(new Run(start, end, commitIndex >= 0 ? hashByIndex[commitIndex] : null, commitIndex >= 0 ? dateByIndex[commitIndex] : null,
          commitIndex >= 0 ? emailByIndex[commitIndex] : null));
      }
      runsByPath.put(path, runs);
    }

    return new BoundaryBlame(boundaryCommit, runsByPath);
  }

  public static BoundaryBlame fromByteArray(byte[] bytes) throws IOException {
    return readFrom(new ByteArrayInputStream(bytes));
  }

  private static void writeNullableUTF(DataOutputStream data, @Nullable String value) throws IOException {
    data.writeBoolean(value != null);
    if (value != null) {
      data.writeUTF(value);
    }
  }

  @Nullable
  private static String readNullableUTF(DataInputStream data) throws IOException {
    return data.readBoolean() ? data.readUTF() : null;
  }

  private static void writeNullableLong(DataOutputStream data, @Nullable Long value) throws IOException {
    data.writeBoolean(value != null);
    if (value != null) {
      data.writeLong(value);
    }
  }

  @Nullable
  private static Long readNullableLong(DataInputStream data) throws IOException {
    return data.readBoolean() ? data.readLong() : null;
  }

  /**
   * Resolves every region still unblamed on {@code candidate} against this cache, consuming its region list.
   * Does nothing, leaving the region list untouched, if this cache has no entry for the candidate's path - the
   * caller is expected to fall back to walking that candidate's history normally in that case.
   */
  void resolveRegions(FileCandidate candidate, BlameResult blameResult) {
    List<Run> runs = runsByPath.get(candidate.getPath());
    if (runs == null) {
      return;
    }

    Region region = candidate.getRegionList();
    while (region != null) {
      resolveRegion(candidate.getOriginalPath(), region, runs, blameResult);
      region = region.next;
    }
    candidate.setRegionList(null);
  }

  private static void resolveRegion(String originalPath, Region region, List<Run> runs, BlameResult blameResult) {
    int sourceStart = region.sourceStart;
    int sourceEnd = sourceStart + region.length;
    int resultOffset = region.resultStart - sourceStart;

    // runs are sorted and non-overlapping, so a single linear scan finds every run intersecting this region
    for (Run run : runs) {
      if (run.endExclusive() <= sourceStart) {
        continue;
      }
      if (run.start() >= sourceEnd) {
        break;
      }
      int overlapStart = Math.max(run.start(), sourceStart);
      int overlapEnd = Math.min(run.endExclusive(), sourceEnd);
      blameResult.saveBlameDataForRange(originalPath, overlapStart + resultOffset, overlapEnd + resultOffset, run.commitHash(), run.commitDate(), run.authorEmail());
    }
  }

  public int getPathCount() {
    return runsByPath.size();
  }

  public int getRunCount() {
    return runsByPath.values().stream().mapToInt(List::size).sum();
  }

  /**
   * The actual size of this cache once serialized via {@link #writeTo(OutputStream)}, alongside a breakdown useful
   * to reason about where the bytes go.
   */
  public SerializedSize computeSerializedSize() {
    Set<String> distinctCommits = new HashSet<>();
    int runCount = 0;
    for (List<Run> runs : runsByPath.values()) {
      runCount += runs.size();
      for (Run run : runs) {
        if (run.commitHash() != null) {
          distinctCommits.add(run.commitHash());
        }
      }
    }
    return new SerializedSize(runsByPath.size(), runCount, distinctCommits.size(), toByteArray().length);
  }

  public record SerializedSize(int pathCount, int runCount, int distinctCommitCount, long bytes) {
  }

  private record Run(int start, int endExclusive, @Nullable String commitHash, @Nullable Instant commitDate, @Nullable String authorEmail) {
  }
}
