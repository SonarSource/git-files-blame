package org.sonar.scm.git.blame.cli.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import javax.annotation.Nullable;

/**
 * Samples the used heap memory on a background daemon thread, keeping track of the peak observed
 * value. Sampling runs within the JVM heap ceiling ({@code -Xmx}); set it explicitly for meaningful
 * comparisons between runs.
 */
public class MemorySampler {

  private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
  private final long intervalMs;
  private volatile boolean running;
  private volatile long peakHeapUsedBytes;
  @Nullable
  private Thread thread;

  public MemorySampler(long intervalMs) {
    this.intervalMs = intervalMs;
  }

  public void start() {
    peakHeapUsedBytes = 0;
    running = true;
    Thread sampler = new Thread(this::sampleLoop, "memory-sampler");
    sampler.setDaemon(true);
    sampler.start();
    this.thread = sampler;
  }

  private void sampleLoop() {
    while (running) {
      long used = memoryBean.getHeapMemoryUsage().getUsed();
      if (used > peakHeapUsedBytes) {
        peakHeapUsedBytes = used;
      }
      try {
        Thread.sleep(intervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  public void stop() {
    running = false;
    Thread sampler = this.thread;
    if (sampler != null) {
      sampler.interrupt();
      try {
        sampler.join(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public long peakHeapUsedBytes() {
    return peakHeapUsedBytes;
  }
}
