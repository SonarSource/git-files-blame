package org.sonar.scm.git.blame.cli.metrics;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Aggregated garbage collection statistics across all collectors.
 *
 * @param count  total number of collections
 * @param timeMs total time spent collecting, in milliseconds
 */
public record GcStats(long count, long timeMs) {

  public static GcStats snapshot() {
    long count = 0;
    long timeMs = 0;
    for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      long beanCount = bean.getCollectionCount();
      long beanTime = bean.getCollectionTime();
      if (beanCount > 0) {
        count += beanCount;
      }
      if (beanTime > 0) {
        timeMs += beanTime;
      }
    }
    return new GcStats(count, timeMs);
  }

  public GcStats minus(GcStats other) {
    return new GcStats(count - other.count, timeMs - other.timeMs);
  }
}
