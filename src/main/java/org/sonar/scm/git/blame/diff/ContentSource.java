/*
 * Copyright (C) 2010, 2021 Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.sonar.scm.git.blame.diff;

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
/*
 * Copied from JGit to apply the fix https://git.eclipse.org/r/c/jgit/jgit/+/200218/1
 * Do not modify it
 */
/**
 * Supplies the content of a file for
 * {@link DiffFormatter}.
 * <p>
 * A content source is not thread-safe. Sources may contain state, including
 * information about the last ObjectLoader they returned. Callers must be
 * careful to ensure there is no more than one ObjectLoader pending on any
 * source, at any time.
 */
public abstract class ContentSource {
	/**
	 * Construct a content source for an ObjectReader.
	 *
	 * @param reader
	 *            the reader to obtain blobs from.
	 * @return a source wrapping the reader.
	 */
	public static ContentSource create(ObjectReader reader) {
		return new ObjectReaderSource(reader);
	}

	/**
	 * Construct a content source for a working directory.
	 *
	 * If the iterator is a {@link org.eclipse.jgit.treewalk.FileTreeIterator}
	 * an optimized version is used that doesn't require seeking through a
	 * TreeWalk.
	 *
	 * @param iterator
	 *            the iterator to obtain source files through.
	 * @return a content source wrapping the iterator.
	 */
	public static ContentSource create(WorkingTreeIterator iterator) {
		return new WorkingTreeSource(iterator);
	}

	/**
	 * Determine the size of the object.
	 *
	 * @param path
	 *            the path of the file, relative to the root of the repository.
	 * @param id
	 *            blob id of the file, if known.
	 * @return the size in bytes.
	 * @throws IOException
	 *             the file cannot be accessed.
	 */
	public abstract long size(String path, ObjectId id) throws IOException;

	/**
	 * Open the object.
	 *
	 * @param path
	 *            the path of the file, relative to the root of the repository.
	 * @param id
	 *            blob id of the file, if known.
	 * @return a loader that can supply the content of the file. The loader must
	 *         be used before another loader can be obtained from this same
	 *         source.
	 * @throws IOException
	 *             the file cannot be accessed.
	 */
	public abstract ObjectLoader open(String path, ObjectId id)
			throws IOException;

	/**
	 * Closes the used resources like ObjectReader, TreeWalk etc. Default
	 * implementation does nothing.
	 *
	 * @since 6.2
	 */
	public void close() {
		// Do nothing
	}

	/**
	 * Checks if the source is from "working tree", so it can be accessed as a
	 * file directly.
	 *
	 * @since 6.2
	 *
	 * @return true if working tree source and false otherwise (loader must be
	 *         used)
	 */
	public boolean isWorkingTreeSource() {
		return false;
	}

	private static class ObjectReaderSource extends ContentSource {
		private final ObjectReader reader;

		ObjectReaderSource(ObjectReader reader) {
			this.reader = reader;
		}

		@Override
		public long size(String path, ObjectId id) throws IOException {
			try {
				return reader.getObjectSize(id, Constants.OBJ_BLOB);
			} catch (MissingObjectException ignore) {
				return 0;
			}
		}

		@Override
		public ObjectLoader open(String path, ObjectId id) throws IOException {
			return reader.open(id, Constants.OBJ_BLOB);
		}

		@Override
		public void close() {
			reader.close();
		}

		@Override
		public boolean isWorkingTreeSource() {
			return false;
		}
	}

	private static class WorkingTreeSource extends ContentSource {
		private final TreeWalk tw;

		private final WorkingTreeIterator iterator;

		private String current;

		WorkingTreeIterator ptr;

		WorkingTreeSource(WorkingTreeIterator iterator) {
			this.tw = new TreeWalk(iterator.getRepository(),
					(ObjectReader) null);
			this.tw.setRecursive(true);
			this.iterator = iterator;
		}

		@Override
		public long size(String path, ObjectId id) throws IOException {
			seek(path);
			return ptr.getEntryLength();
		}

		@Override
		public ObjectLoader open(String path, ObjectId id) throws IOException {
			seek(path);
			long entrySize = ptr.getEntryContentLength();
			return new ObjectLoader() {
				@Override
				public long getSize() {
					return entrySize;
				}

				@Override
				public int getType() {
					return ptr.getEntryFileMode().getObjectType();
				}

				@Override
				public ObjectStream openStream() throws MissingObjectException,
						IOException {
					long contentLength = entrySize;
					InputStream in = ptr.openEntryStream();
					in = new BufferedInputStream(in);
					return new ObjectStream.Filter(getType(), contentLength, in);
				}

				@Override
				public boolean isLarge() {
					return true;
				}

				@Override
				public byte[] getCachedBytes() throws LargeObjectException {
					throw new LargeObjectException();
				}
			};
		}

		private void seek(String path) throws IOException {
			if (!path.equals(current)) {
				iterator.reset();
				// Possibly this iterator had an associated DirCacheIterator,
				// but we have no access to it and thus don't know about it.
				// We have to reset this iterator here to work without
				// DirCacheIterator and to descend always into ignored
				// directories. Otherwise we might not find tracked files below
				// ignored folders. Since we're looking only for a single
				// specific path this is not a performance problem.
				iterator.setWalkIgnoredDirectories(true);
				iterator.setDirCacheIterator(null, -1);
				tw.reset();
				tw.addTree(iterator);
				tw.setFilter(PathFilter.create(path));
				current = path;
				if (!tw.next())
					throw new FileNotFoundException(path);
				ptr = tw.getTree(0, WorkingTreeIterator.class);
				if (ptr == null)
					throw new FileNotFoundException(path);
			}
		}

		@Override
		public void close() {
			tw.close();
		}

		@Override
		public boolean isWorkingTreeSource() {
			return true;
		}
	}

	/** A pair of sources to access the old and new sides of a DiffEntry. */
	public static final class Pair {
		private final ContentSource oldSource;

		private final ContentSource newSource;

		/**
		 * Construct a pair of sources.
		 *
		 * @param oldSource
		 *            source to read the old side of a DiffEntry.
		 * @param newSource
		 *            source to read the new side of a DiffEntry.
		 */
		public Pair(ContentSource oldSource, ContentSource newSource) {
			this.oldSource = oldSource;
			this.newSource = newSource;
		}

		/**
		 * Determine the size of the object.
		 *
		 * @param side
		 *            which side of the entry to read (OLD or NEW).
		 * @param ent
		 *            the entry to examine.
		 * @return the size in bytes.
		 * @throws IOException
		 *             the file cannot be accessed.
		 */
		public long size(DiffEntry.Side side, DiffEntry ent) throws IOException {
			switch (side) {
			case OLD:
				return oldSource.size(ent.oldPath, ent.oldId.toObjectId());
			case NEW:
				return newSource.size(ent.newPath, ent.newId.toObjectId());
			default:
				throw new IllegalArgumentException();
			}
		}

		/**
		 * Open the object.
		 *
		 * @param side
		 *            which side of the entry to read (OLD or NEW).
		 * @param ent
		 *            the entry to examine.
		 * @return a loader that can supply the content of the file. The loader
		 *         must be used before another loader can be obtained from this
		 *         same source.
		 * @throws IOException
		 *             the file cannot be accessed.
		 */
		public ObjectLoader open(DiffEntry.Side side, DiffEntry ent)
				throws IOException {
			switch (side) {
			case OLD:
				return oldSource.open(ent.oldPath, ent.oldId.toObjectId());
			case NEW:
				return newSource.open(ent.newPath, ent.newId.toObjectId());
			default:
				throw new IllegalArgumentException();
			}
		}

		/**
		 * Closes used resources.
		 *
		 * @since 6.2
		 */
		public void close() {
			oldSource.close();
			newSource.close();
		}

		/**
		 * Checks if source (side) is a "working tree".
		 *
		 * @since 6.2
		 *
		 * @param side
		 *            which side of the entry to read (OLD or NEW).
		 * @return is the source a "working tree"
		 *
		 */
		public boolean isWorkingTreeSource(DiffEntry.Side side) {
			switch (side) {
			case OLD:
				return oldSource.isWorkingTreeSource();
			case NEW:
				return newSource.isWorkingTreeSource();
			default:
				throw new IllegalArgumentException();
			}
		}

	}
}
