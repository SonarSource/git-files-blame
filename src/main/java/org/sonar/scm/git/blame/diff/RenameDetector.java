/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.sonar.scm.git.blame.diff;

import static org.eclipse.jgit.storage.pack.PackConfig.DEFAULT_BIG_FILE_THRESHOLD;
import static org.sonar.scm.git.blame.diff.DiffEntry.Side.NEW;
import static org.sonar.scm.git.blame.diff.DiffEntry.Side.OLD;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.sonar.scm.git.blame.diff.DiffEntry.ChangeType;
import org.sonar.scm.git.blame.diff.SimilarityIndex.TableFullException;
/*
 * Copied from JGit to apply the fix https://git.eclipse.org/r/c/jgit/jgit/+/200218/1
 * Do not modify it
 */
/**
 * Detect and resolve object renames.
 */
public class RenameDetector {
	private static final int EXACT_RENAME_SCORE = 100;
	private static final Comparator<DiffEntry> DIFF_COMPARATOR = new Comparator<>() {
		@Override
		public int compare(DiffEntry a, DiffEntry b) {
			int cmp = nameOf(a).compareTo(nameOf(b));
			if (cmp == 0)
				cmp = sortOf(a.getChangeType()) - sortOf(b.getChangeType());
			return cmp;
		}
		private String nameOf(DiffEntry ent) {
			// Sort by the new name, unless the change is a delete. On
			// deletes the new name is /dev/null, so we sort instead by
			// the old name.
			//
			if (ent.changeType == ChangeType.DELETE)
				return ent.oldPath;
			return ent.newPath;
		}
		private int sortOf(ChangeType changeType) {
			// Sort deletes before adds so that a major type change for
			// a file path (such as symlink to regular file) will first
			// remove the path, then add it back with the new type.
			//
			switch (changeType) {
				case DELETE:
					return 1;
				case ADD:
					return 2;
				default:
					return 10;
			}
		}
	};
	private List<DiffEntry> entries;
	private List<DiffEntry> deleted;
	private List<DiffEntry> added;
	/**
	 * Old paths of deleted that have been matched in renames. If the corresponding
	 * deleted are matched again with other added, they'll be considered to be copies
	 * instead of renames.
	 */
	private Set<String> matchedDeletedPaths;
	private boolean done;
	private final ObjectReader objectReader;
	/** Similarity score required to pair an add/delete as a rename. */
	private int renameScore = 60;
	/**
	 * Similarity score required to keep modified file pairs together. Any
	 * modified file pairs with a similarity score below this will be broken
	 * apart.
	 */
	private int breakScore = -1;
	/** Limit in the number of files to consider for renames. */
	private int renameLimit;
	/**
	 * File size threshold (in bytes) for detecting renames. Files larger
	 * than this size will not be processed for renames.
	 */
	private int bigFileThreshold = DEFAULT_BIG_FILE_THRESHOLD;
	/**
	 * Skip detecting content renames for binary files. Content renames are
	 * those that are not exact, that is with a slight content modification
	 * between the two files.
	 */
	private boolean skipContentRenamesForBinaryFiles = false;
	/** Set if the number of adds or deletes was over the limit. */
	private boolean overRenameLimit;
	/**
	 * Create a new rename detector for the given repository
	 *
	 * @param repo
	 *            the repository to use for rename detection
	 */
	public RenameDetector(Repository repo) {
		this(repo.newObjectReader(), repo.getConfig().get(DiffConfig.KEY));
	}
	/**
	 * Create a new rename detector with a specified reader and diff config.
	 *
	 * @param reader
	 *            reader to obtain objects from the repository with.
	 * @param cfg
	 *            diff config specifying rename detection options.
	 * @since 3.0
	 */
	public RenameDetector(ObjectReader reader, DiffConfig cfg) {
		objectReader = reader.newReader();
		renameLimit = cfg.getRenameLimit();
		reset();
	}
	/**
	 * Get rename score
	 *
	 * @return minimum score required to pair an add/delete as a rename. The
	 *         score ranges are within the bounds of (0, 100).
	 */
	public int getRenameScore() {
		return renameScore;
	}
	/**
	 * Set the minimum score required to pair an add/delete as a rename.
	 * <p>
	 * When comparing two files together their score must be greater than or
	 * equal to the rename score for them to be considered a rename match. The
	 * score is computed based on content similarity, so a score of 60 implies
	 * that approximately 60% of the bytes in the files are identical.
	 *
	 * @param score
	 *            new rename score, must be within [0, 100].
	 * @throws java.lang.IllegalArgumentException
	 *             the score was not within [0, 100].
	 */
	public void setRenameScore(int score) {
		if (score < 0 || score > 100)
			throw new IllegalArgumentException(
				JGitText.get().similarityScoreMustBeWithinBounds);
		renameScore = score;
	}
	/**
	 * Get break score
	 *
	 * @return the similarity score required to keep modified file pairs
	 *         together. Any modify pairs that score below this will be broken
	 *         apart into separate add/deletes. Values less than or equal to
	 *         zero indicate that no modifies will be broken apart. Values over
	 *         100 cause all modify pairs to be broken.
	 */
	public int getBreakScore() {
		return breakScore;
	}
	/**
	 * Set break score
	 *
	 * @param breakScore
	 *            the similarity score required to keep modified file pairs
	 *            together. Any modify pairs that score below this will be
	 *            broken apart into separate add/deletes. Values less than or
	 *            equal to zero indicate that no modifies will be broken apart.
	 *            Values over 100 cause all modify pairs to be broken.
	 */
	public void setBreakScore(int breakScore) {
		this.breakScore = breakScore;
	}
	/**
	 * Get rename limit
	 *
	 * @return limit on number of paths to perform inexact rename detection
	 */
	public int getRenameLimit() {
		return renameLimit;
	}
	/**
	 * Set the limit on the number of files to perform inexact rename detection.
	 * <p>
	 * The rename detector has to build a square matrix of the rename limit on
	 * each side, then perform that many file compares to determine similarity.
	 * If 1000 files are added, and 1000 files are deleted, a 1000*1000 matrix
	 * must be allocated, and 1,000,000 file compares may need to be performed.
	 *
	 * @param limit
	 *            new file limit. 0 means no limit; a negative number means no
	 *            inexact rename detection will be performed, only exact rename
	 *            detection.
	 */
	public void setRenameLimit(int limit) {
		renameLimit = limit;
	}
	/**
	 * Get file size threshold for detecting renames. Files larger
	 * than this size will not be processed for rename detection.
	 *
	 * @return threshold in bytes of the file size.
	 * @since 5.12
	 */
	public int getBigFileThreshold() { return bigFileThreshold; }
	/**
	 * Set the file size threshold for detecting renames. Files larger than this
	 * threshold will be skipped during rename detection computation.
	 *
	 * @param threshold file size threshold in bytes.
	 * @since 5.12
	 */
	public void setBigFileThreshold(int threshold) {
		this.bigFileThreshold = threshold;
	}
	/**
	 * Get skipping detecting content renames for binary files.
	 *
	 * @return true if content renames should be skipped for binary files, false otherwise.
	 * @since 5.12
	 */
	public boolean getSkipContentRenamesForBinaryFiles() {
		return skipContentRenamesForBinaryFiles;
	}
	/**
	 * Sets skipping detecting content renames for binary files.
	 *
	 * @param value true if content renames should be skipped for binary files, false otherwise.
	 * @since 5.12
	 */
	public void setSkipContentRenamesForBinaryFiles(boolean value) {
		this.skipContentRenamesForBinaryFiles = value;
	}
	/**
	 * Check if the detector is over the rename limit.
	 * <p>
	 * This method can be invoked either before or after {@code getEntries} has
	 * been used to perform rename detection.
	 *
	 * @return true if the detector has more file additions or removals than the
	 *         rename limit is currently set to. In such configurations the
	 *         detector will skip expensive computation.
	 */
	public boolean isOverRenameLimit() {
		if (done)
			return overRenameLimit;
		int cnt = Math.max(added.size(), deleted.size());
		return getRenameLimit() != 0 && getRenameLimit() < cnt;
	}
	/**
	 * Add entries to be considered for rename detection.
	 *
	 * @param entriesToAdd
	 *            one or more entries to add.
	 * @throws java.lang.IllegalStateException
	 *             if {@code getEntries} was already invoked.
	 */
	public void addAll(Collection<DiffEntry> entriesToAdd) {
		if (done)
			throw new IllegalStateException(JGitText.get().renamesAlreadyFound);
		for (DiffEntry entry : entriesToAdd) {
			switch (entry.getChangeType()) {
				case ADD:
					added.add(entry);
					break;
				case DELETE:
					deleted.add(entry);
					break;
				case MODIFY:
					if (sameType(entry.getOldMode(), entry.getNewMode())) {
						entries.add(entry);
					} else {
						List<DiffEntry> tmp = DiffEntry.breakModify(entry);
						deleted.add(tmp.get(0));
						added.add(tmp.get(1));
					}
					break;
				case COPY:
				case RENAME:
				default:
					entries.add(entry);
			}
		}
	}
	/**
	 * Add an entry to be considered for rename detection.
	 *
	 * @param entry
	 *            to add.
	 * @throws java.lang.IllegalStateException
	 *             if {@code getEntries} was already invoked.
	 */
	public void add(DiffEntry entry) {
		addAll(Collections.singletonList(entry));
	}
	/**
	 * Detect renames in the current file set.
	 * <p>
	 * This convenience function runs without a progress monitor.
	 * </p>
	 *
	 * @return an unmodifiable list of {@link org.eclipse.jgit.diff.DiffEntry}s
	 *         representing all files that have been changed.
	 * @throws java.io.IOException
	 *             file contents cannot be read from the repository.
	 */
	public List<DiffEntry> compute() throws IOException {
		try {
			return compute(NullProgressMonitor.INSTANCE);
		} catch (CanceledException e) {
			// Won't happen with a NullProgressMonitor
			return Collections.emptyList();
		}
	}
	/**
	 * Detect renames in the current file set.
	 *
	 * @param pm
	 *            report progress during the detection phases.
	 * @return an unmodifiable list of {@link org.eclipse.jgit.diff.DiffEntry}s
	 *         representing all files that have been changed.
	 * @throws java.io.IOException
	 *             file contents cannot be read from the repository.
	 * @throws CanceledException
	 *             if rename detection was cancelled
	 */
	public List<DiffEntry> compute(ProgressMonitor pm)
		throws IOException, CanceledException {
		if (!done) {
			try {
				return compute(objectReader, pm);
			} finally {
				objectReader.close();
			}
		}
		return Collections.unmodifiableList(entries);
	}
	/**
	 * Detect renames in the current file set.
	 *
	 * @param reader
	 *            reader to obtain objects from the repository with.
	 * @param pm
	 *            report progress during the detection phases.
	 * @return an unmodifiable list of {@link org.eclipse.jgit.diff.DiffEntry}s
	 *         representing all files that have been changed.
	 * @throws java.io.IOException
	 *             file contents cannot be read from the repository.
	 * @throws CanceledException
	 *             if rename detection was cancelled
	 */
	public List<DiffEntry> compute(ObjectReader reader, ProgressMonitor pm)
		throws IOException, CanceledException {
		final ContentSource cs = ContentSource.create(reader);
		return compute(new ContentSource.Pair(cs, cs), pm);
	}
	/**
	 * Detect renames in the current file set.
	 *
	 * @param reader
	 *            reader to obtain objects from the repository with.
	 * @param pm
	 *            report progress during the detection phases.
	 * @return an unmodifiable list of {@link org.eclipse.jgit.diff.DiffEntry}s
	 *         representing all files that have been changed.
	 * @throws java.io.IOException
	 *             file contents cannot be read from the repository.
	 * @throws CanceledException
	 *             if rename detection was cancelled
	 */
	public List<DiffEntry> compute(ContentSource.Pair reader, ProgressMonitor pm)
		throws IOException, CanceledException {
		if (!done) {
			done = true;
			if (pm == null)
				pm = NullProgressMonitor.INSTANCE;
			if (0 < breakScore)
				breakModifies(reader, pm);
			if (!added.isEmpty() && !deleted.isEmpty())
				findExactRenames(pm);
			if (!added.isEmpty() && !deleted.isEmpty())
				findContentRenames(reader, pm);
			deleted.removeIf(d -> matchedDeletedPaths.contains(d.getOldPath()));
			matchedDeletedPaths = null;
			if (0 < breakScore && !added.isEmpty() && !deleted.isEmpty())
				rejoinModifies(pm);
			entries.addAll(added);
			added = null;
			entries.addAll(deleted);
			deleted = null;
			Collections.sort(entries, DIFF_COMPARATOR);
		}
		return Collections.unmodifiableList(entries);
	}
	/**
	 * Reset this rename detector for another rename detection pass.
	 */
	public void reset() {
		entries = new ArrayList<>();
		deleted = new ArrayList<>();
		added = new ArrayList<>();
		matchedDeletedPaths = new HashSet<>();
		done = false;
	}
	private void advanceOrCancel(ProgressMonitor pm) throws CanceledException {
		if (pm.isCancelled()) {
			throw new CanceledException(JGitText.get().renameCancelled);
		}
		pm.update(1);
	}
	private void breakModifies(ContentSource.Pair reader, ProgressMonitor pm)
		throws IOException, CanceledException {
		ArrayList<DiffEntry> newEntries = new ArrayList<>(entries.size());
		pm.beginTask(JGitText.get().renamesBreakingModifies, entries.size());
		for (int i = 0; i < entries.size(); i++) {
			DiffEntry e = entries.get(i);
			if (e.getChangeType() == ChangeType.MODIFY) {
				int score = calculateModifyScore(reader, e);
				if (score < breakScore) {
					List<DiffEntry> tmp = DiffEntry.breakModify(e);
					DiffEntry del = tmp.get(0);
					del.score = score;
					deleted.add(del);
					added.add(tmp.get(1));
				} else {
					newEntries.add(e);
				}
			} else {
				newEntries.add(e);
			}
			advanceOrCancel(pm);
		}
		entries = newEntries;
	}
	private void rejoinModifies(ProgressMonitor pm) throws CanceledException {
		HashMap<String, DiffEntry> nameMap = new HashMap<>();
		ArrayList<DiffEntry> newAdded = new ArrayList<>(added.size());
		pm.beginTask(JGitText.get().renamesRejoiningModifies, added.size()
			+ deleted.size());
		for (DiffEntry src : deleted) {
			nameMap.put(src.oldPath, src);
			advanceOrCancel(pm);
		}
		for (DiffEntry dst : added) {
			DiffEntry src = nameMap.remove(dst.newPath);
			if (src != null) {
				if (sameType(src.oldMode, dst.newMode)) {
					entries.add(DiffEntry.pair(ChangeType.MODIFY, src, dst,
						src.score));
				} else {
					nameMap.put(src.oldPath, src);
					newAdded.add(dst);
				}
			} else {
				newAdded.add(dst);
			}
			advanceOrCancel(pm);
		}
		added = newAdded;
		deleted = new ArrayList<>(nameMap.values());
	}
	private int calculateModifyScore(ContentSource.Pair reader, DiffEntry d)
		throws IOException {
		try {
			SimilarityIndex src = new SimilarityIndex();
			src.hash(reader.open(OLD, d));
			src.sort();
			SimilarityIndex dst = new SimilarityIndex();
			dst.hash(reader.open(NEW, d));
			dst.sort();
			return src.score(dst, 100);
		} catch (TableFullException tableFull) {
			// If either table overflowed while being constructed, don't allow
			// the pair to be broken. Returning 1 higher than breakScore will
			// ensure its not similar, but not quite dissimilar enough to break.
			//
			overRenameLimit = true;
			return breakScore + 1;
		}
	}
	private void findContentRenames(ContentSource.Pair reader,
		ProgressMonitor pm)
		throws IOException, CanceledException {
		int cnt = Math.max(added.size(), deleted.size());
		if (getRenameLimit() == 0 || cnt <= getRenameLimit()) {
			SimilarityRenameDetector d;
			d = new SimilarityRenameDetector(reader, deleted, added, matchedDeletedPaths);
			d.setRenameScore(getRenameScore());
			d.setBigFileThreshold(getBigFileThreshold());
			d.setSkipBinaryFiles(getSkipContentRenamesForBinaryFiles());
			d.compute(pm);
			overRenameLimit |= d.isTableOverflow();
			added = d.getLeftOverDestinations();
			entries.addAll(d.getMatches());
		} else {
			overRenameLimit = true;
		}
	}
	@SuppressWarnings("unchecked")
	private void findExactRenames(ProgressMonitor pm)
		throws CanceledException {
		pm.beginTask(JGitText.get().renamesFindingExact, //
			added.size() + added.size() + deleted.size()
				+ added.size() * deleted.size());
		HashMap<AbbreviatedObjectId, Object> deletedMap = populateMap(deleted, pm);
		HashMap<AbbreviatedObjectId, Object> addedMap = populateMap(added, pm);
		ArrayList<DiffEntry> uniqueAdds = new ArrayList<>(added.size());
		ArrayList<List<DiffEntry>> nonUniqueAdds = new ArrayList<>();
		for (Object o : addedMap.values()) {
			if (o instanceof DiffEntry)
				uniqueAdds.add((DiffEntry) o);
			else
				nonUniqueAdds.add((List<DiffEntry>) o);
		}
		ArrayList<DiffEntry> left = new ArrayList<>(added.size());
		for (DiffEntry a : uniqueAdds) {
			Object del = deletedMap.get(a.newId);
			if (del instanceof DiffEntry) {
				// We have one add to one delete: pair them if they are the same
				// type
				DiffEntry e = (DiffEntry) del;
				if (sameType(e.oldMode, a.newMode)) {
					matchedDeletedPaths.add(e.getOldPath());
					entries.add(exactRename(e, a));
				} else {
					left.add(a);
				}
			} else if (del != null) {
				// We have one add to many deletes: find the delete with the
				// same type and closest name to the add, then pair them
				List<DiffEntry> list = (List<DiffEntry>) del;
				DiffEntry best = bestPathMatch(a, list);
				if (best != null) {
					matchedDeletedPaths.add(best.getOldPath());
					entries.add(exactRename(best, a));
				} else {
					left.add(a);
				}
			} else {
				left.add(a);
			}
			advanceOrCancel(pm);
		}
		for (List<DiffEntry> adds : nonUniqueAdds) {
			Object o = deletedMap.get(adds.get(0).newId);
			if (o instanceof DiffEntry) {
				// We have many adds to one delete: find the add with the same
				// type and closest name to the delete, then pair them. Mark the
				// rest as copies of the delete.
				DiffEntry d = (DiffEntry) o;
				DiffEntry best = bestPathMatch(d, adds);
				if (best != null) {
					matchedDeletedPaths.add(d.getOldPath());
					entries.add(exactRename(d, best));
					for (DiffEntry a : adds) {
						if (a != best) {
							if (sameType(d.oldMode, a.newMode)) {
								entries.add(exactCopy(d, a));
							} else {
								left.add(a);
							}
						}
					}
				} else {
					left.addAll(adds);
				}
			} else if (o != null) {
				// We have many adds to many deletes: score all the adds against
				// all the deletes by path name, take the best matches, pair
				// them as renames, then call the rest copies
				List<DiffEntry> dels = (List<DiffEntry>) o;
				long[] matrix = new long[dels.size() * adds.size()];
				int mNext = 0;
				for (int delIdx = 0; delIdx < dels.size(); delIdx++) {
					String deletedName = dels.get(delIdx).oldPath;
					for (int addIdx = 0; addIdx < adds.size(); addIdx++) {
						String addedName = adds.get(addIdx).newPath;
						int score = SimilarityRenameDetector.nameScore(addedName, deletedName);
						matrix[mNext] = SimilarityRenameDetector.encode(score, delIdx, addIdx);
						mNext++;
						if (pm.isCancelled()) {
							throw new CanceledException(
								JGitText.get().renameCancelled);
						}
					}
				}
				Arrays.sort(matrix);
				for (--mNext; mNext >= 0; mNext--) {
					long ent = matrix[mNext];
					int delIdx = SimilarityRenameDetector.srcFile(ent);
					int addIdx = SimilarityRenameDetector.dstFile(ent);
					DiffEntry d = dels.get(delIdx);
					DiffEntry a = adds.get(addIdx);
					if (a == null) {
						advanceOrCancel(pm);
						continue; // was already matched earlier
					}
					ChangeType type;
					if (matchedDeletedPaths.add(d.getOldPath())) {
						type = ChangeType.RENAME;
					} else {
						type = ChangeType.COPY;
					}
					entries.add(DiffEntry.pair(type, d, a, 100));
					adds.set(addIdx, null); // Claim the destination was matched.
					advanceOrCancel(pm);
				}
			} else {
				left.addAll(adds);
			}
			advanceOrCancel(pm);
		}
		added = left;

		// TODO this is unnecessary with the patch, but until the patch is accepted by JGit, we need to read the entries from
		// the map as it was done before, to replicated the order. Unfortunately the order will depend on the hashes and will be unstable, but it will
		// ensure that it matches JGit's results. The next step of the algorithm (content renames) is sensitive to this order.
		deleted = new ArrayList<>(deletedMap.size());
		for (Object o : deletedMap.values()) {
			if (o instanceof DiffEntry) {
				DiffEntry e = (DiffEntry) o;
        deleted.add(e);
			} else {
				List<DiffEntry> list = (List<DiffEntry>) o;
				for (DiffEntry e : list) {
					deleted.add(e);
				}
			}
		}
		pm.endTask();
	}
	/**
	 * Find the best match by file path for a given DiffEntry from a list of
	 * DiffEntrys. The returned DiffEntry will be of the same type as <src>. If
	 * no DiffEntry can be found that has the same type, this method will return
	 * null.
	 *
	 * @param src
	 *            the DiffEntry to try to find a match for
	 * @param list
	 *            a list of DiffEntrys to search through
	 * @return the DiffEntry from <list> who's file path best matches <src>
	 */
	private static DiffEntry bestPathMatch(DiffEntry src, List<DiffEntry> list) {
		DiffEntry best = null;
		int score = -1;
		for (DiffEntry d : list) {
			if (sameType(mode(d), mode(src))) {
				int tmp = SimilarityRenameDetector
					.nameScore(path(d), path(src));
				if (tmp > score) {
					best = d;
					score = tmp;
				}
			}
		}
		return best;
	}
	@SuppressWarnings("unchecked")
	private HashMap<AbbreviatedObjectId, Object> populateMap(
		List<DiffEntry> diffEntries, ProgressMonitor pm)
		throws CanceledException {
		HashMap<AbbreviatedObjectId, Object> map = new HashMap<>();
		for (DiffEntry de : diffEntries) {
			Object old = map.put(id(de), de);
			if (old instanceof DiffEntry) {
				ArrayList<DiffEntry> list = new ArrayList<>(2);
				list.add((DiffEntry) old);
				list.add(de);
				map.put(id(de), list);
			} else if (old != null) {
				// Must be a list of DiffEntries
				((List<DiffEntry>) old).add(de);
				map.put(id(de), old);
			}
			advanceOrCancel(pm);
		}
		return map;
	}
	private static String path(DiffEntry de) {
		return de.changeType == ChangeType.DELETE ? de.oldPath : de.newPath;
	}
	private static FileMode mode(DiffEntry de) {
		return de.changeType == ChangeType.DELETE ? de.oldMode : de.newMode;
	}
	private static AbbreviatedObjectId id(DiffEntry de) {
		return de.changeType == ChangeType.DELETE ? de.oldId : de.newId;
	}
	static boolean sameType(FileMode a, FileMode b) {
		// Files have to be of the same type in order to rename them.
		// We would never want to rename a file to a gitlink, or a
		// symlink to a file.
		//
		int aType = a.getBits() & FileMode.TYPE_MASK;
		int bType = b.getBits() & FileMode.TYPE_MASK;
		return aType == bType;
	}
	private static DiffEntry exactRename(DiffEntry src, DiffEntry dst) {
		return DiffEntry.pair(ChangeType.RENAME, src, dst, EXACT_RENAME_SCORE);
	}
	private static DiffEntry exactCopy(DiffEntry src, DiffEntry dst) {
		return DiffEntry.pair(ChangeType.COPY, src, dst, EXACT_RENAME_SCORE);
	}
}