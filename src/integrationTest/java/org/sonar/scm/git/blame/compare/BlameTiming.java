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

/**
 * Clone and blame duration for one (scenario x clone strategy) run, for both tools. Correctness is the point of
 * this suite, but the numbers are still worth printing since the whole reason to test sparse/partial checkouts
 * is their effect on how expensive the first blame is.
 */
public record BlameTiming(String scenario, CloneStrategy strategy, long cloneMs, long nativeBlameMs, long libraryBlameMs) {
}
