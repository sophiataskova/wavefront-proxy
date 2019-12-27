package com.wavefront.agent.handlers;

import com.wavefront.agent.data.QueueingReason;

import java.util.Collection;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Factory for {@link SenderTask} objects.
 *
 * @author vasily@wavefront.com
 */
public interface SenderTaskFactory {

  /**
   * Create a collection of {@link SenderTask objects} for a specified handler key.
   *
   * @param handlerKey unique identifier for the handler.
   * @param numThreads create a specified number of threads.
   * @return created tasks.
   */
  <T> Collection<SenderTask<T>> createSenderTasks(@Nonnull HandlerKey handlerKey,
                                                  final int numThreads);

  /**
   * Shut down all tasks.
   */
  void shutdown();

  /**
   * Drain memory buffers to queue for all tasks.
   *
   * @param reason reason for queueing
   */
  void drainBuffersToQueue(@Nullable QueueingReason reason);
}
