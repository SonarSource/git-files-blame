/*
 * SonarQube
 * Copyright (C) 2009-2023 SonarSource SA
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

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;

import static java.util.Collections.emptyList;

/**
 * A ExecutorService that executes in the calling thread. It does not create any additional threads.
 */
public class SameThreadExecutorService extends AbstractExecutorService {
  static final SameThreadExecutorService INSTANCE = new SameThreadExecutorService();

  private SameThreadExecutorService() {
    // private
  }

  /**
   * Not supported. This method is a no-op.
   */
  @Override
  public void shutdown() {
  }

  /**
   * This executor can't be shutdown.
   *
   * @return false
   */
  @Override
  public boolean isShutdown() {
    return false;
  }

  /**
   * This executor can't be terminated.
   *
   * @return false
   */
  @Override
  public boolean isTerminated() {
    return false;
  }

  /**
   * This executor can't be terminator and this method will immediately return false
   *
   * @return false
   */
  @Override
  public boolean awaitTermination(long timeout, @NotNull TimeUnit timeUnit) {
    return false;
  }

  /**
   * This executor can't be shutdown. This method is a no op.
   *
   * @return always empty
   */
  @Override
  public List<Runnable> shutdownNow() {
    return emptyList();
  }

  /**
   * Executes the runnable in the calling thread.
   *
   * @param runnable Runnable to execute
   */
  @Override
  public void execute(Runnable runnable) {
    runnable.run();
  }
}