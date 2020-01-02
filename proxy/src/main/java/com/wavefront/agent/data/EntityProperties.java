package com.wavefront.agent.data;

import com.google.common.util.concurrent.RecyclableRateLimiter;

import javax.annotation.Nullable;

/**
 * Unified interface for dynamic entity-specific dynamic properties, that may change at runtime
 *
 * @author vasily@wavefront.com
 */
public interface EntityProperties {
  // what we consider "unlimited"
  int NO_RATE_LIMIT = 10_000_000;

  // default values for dynamic properties
  boolean DEFAULT_SPLIT_PUSH_WHEN_RATE_LIMITED = false;
  double DEFAULT_RETRY_BACKOFF_BASE_SECONDS = 2.0d;
  int DEFAULT_FLUSH_INTERVAL = 1000;
  int DEFAULT_MAX_BURST_SECONDS = 10;
  int DEFAULT_BATCH_SIZE = 40000;
  int DEFAULT_BATCH_SIZE_HISTOGRAMS = 10000;
  int DEFAULT_BATCH_SIZE_SOURCE_TAGS = 50;
  int DEFAULT_BATCH_SIZE_SPANS = 5000;
  int DEFAULT_BATCH_SIZE_SPAN_LOGS = 1000;
  int DEFAULT_BATCH_SIZE_EVENTS = 50;
  int DEFAULT_MIN_SPLIT_BATCH_SIZE = 100;

  /**
   * Get initially configured batch size.
   *
   * @return batch size
   */
  int getItemsPerBatchOriginal();

  /**
   * Whether we should split batches into smaller ones after getting HTTP 406 response from server.
   *
   * @return true if we should split on pushback
   */
  boolean isSplitPushWhenRateLimited();

  /**
   * Get base in seconds for retry thread exponential backoff.
   *
   * @return exponential backoff base value
   */
  double getRetryBackoffBaseSeconds();

  /**
   * Sets base in seconds for retry thread exponential backoff.
   *
   * @param retryBackoffBaseSeconds new value for exponential backoff base value
   */
  void setRetryBackoffBaseSeconds(@Nullable Double retryBackoffBaseSeconds);

  /**
   * Get initially configured rate limit (per second).
   *
   * @return rate limit
   */
  double getRateLimit();

  /**
   * Get max number of burst seconds to allow when rate limiting to smooth out uneven traffic.
   *
   * @return number of seconds
   */
  int getRateLimitMaxBurstSeconds();

  /**
   * Get specific {@link RecyclableRateLimiter} instance.
   *
   * @return rate limiter
   */
  RecyclableRateLimiter getRateLimiter();

  /**
   * Get interval between batches (in milliseconds)
   *
   * @return interval between batches
   */
  int getPushFlushInterval();

  /**
   * Get the maximum allowed number of items per single flush.
   *
   * @return batch size
   */
  int getItemsPerBatch();

  /**
   * Sets the maximum allowed number of items per single flush.
   *
   * @param itemsPerBatch batch size.
   */
  void setItemsPerBatch(@Nullable Integer itemsPerBatch);

  /**
   * Do not split the batch if its size is less than this value. Only applicable when
   * {@link #isSplitPushWhenRateLimited()} is true.
   *
   * @return smallest allowed batch size
   */
  int getMinBatchSplitSize();

  /**
   * Max number of items that can stay in memory buffers before spooling to disk.
   * Defaults to 16 * {@link #getItemsPerBatch()}, minimum size: {@link #getItemsPerBatch()}.
   * Setting this value lower than default reduces memory usage, but will force the proxy to
   * spool to disk more frequently if you have points arriving at the proxy in short bursts,
   * and/or your network latency is on the higher side.
   *
   * @return memory buffer limit
   */
  int getMemoryBufferLimit();

  /**
   * Get current queueing behavior - defines conditions that trigger queueing.
   *
   * @return queueing behavior level
   */
  TaskQueueLevel getTaskQueueLevel();

  /**
   * Checks whether data flow for this entity type is disabled.
   *
   * @return true if data flow is disabled
   */
  boolean isFeatureDisabled();

  /**
   * Sets the flag value for "feature disabled" flag.
   *
   * @param featureDisabled if "true", data flow is disabled. if null or "false", enabled.
   */
  void setFeatureDisabled(@Nullable Boolean featureDisabled);
}