package com.wavefront.agent.handlers;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.AtomicDouble;
import com.wavefront.api.agent.ValidationConfiguration;
import com.wavefront.common.Clock;
import com.wavefront.common.HostMetricTagsPair;
import com.wavefront.ingester.ReportPointSerializer;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.DeltaCounter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.MetricName;
import wavefront.report.ReportPoint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.wavefront.data.Validation.validatePoint;
import static com.wavefront.sdk.common.Utils.metricToLineData;

/**
 * Handler that processes incoming DeltaCounter objects, aggregates them and hands it over to one
 * of the {@link SenderTask} threads according to deltaCountersAggregationIntervalSeconds or
 * before cache expires.
 *
 * @author djia@vmware.com
 */
public class DeltaCounterAccumulationHandlerImpl
    extends AbstractReportableEntityHandler<ReportPoint, String> {

  private final ValidationConfiguration validationConfig;
  private final Logger validItemsLogger;
  final Histogram receivedPointLag;
  private final Counter reportedCounter;
  private final Cache<HostMetricTagsPair, AtomicDouble> aggregatedDeltas;
  private final ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();

    /**
   * @param handlerKey                 metrics pipeline key.
   * @param blockedItemsPerBatch       controls sample rate of how many blocked
   *                                   points are written into the main log file.
   * @param senderTasks                sender tasks.
   * @param validationConfig           validation configuration.
   * @param aggregationIntervalSeconds aggregation interval for delta counters.
   * @param blockedItemLogger          logger for blocked items.
   * @param validItemsLogger           logger for valid items.
   */
  public DeltaCounterAccumulationHandlerImpl(
      final HandlerKey handlerKey, final int blockedItemsPerBatch,
      @Nullable final Collection<SenderTask<String>> senderTasks,
      @Nonnull final ValidationConfiguration validationConfig,
      long aggregationIntervalSeconds, @Nullable final Logger blockedItemLogger,
      @Nullable final Logger validItemsLogger) {
    super(handlerKey, blockedItemsPerBatch, new ReportPointSerializer(), senderTasks, true,
        blockedItemLogger);
    this.validationConfig = validationConfig;
    this.validItemsLogger = validItemsLogger;

    this.aggregatedDeltas = Caffeine.newBuilder().
        expireAfterAccess(5 * aggregationIntervalSeconds, TimeUnit.SECONDS).
        removalListener((RemovalListener<HostMetricTagsPair, AtomicDouble>)
            (metric, value, reason) -> this.reportAggregatedDeltaValue(metric, value)).build();

    this.receivedPointLag = Metrics.newHistogram(new MetricName("points." + handlerKey.getHandle() +
        ".received", "", "lag"), false);

    reporter.scheduleWithFixedDelay(this::flushDeltaCounters, aggregationIntervalSeconds,
        aggregationIntervalSeconds, TimeUnit.SECONDS);

    String metricPrefix = handlerKey.toString();
    this.reportedCounter = Metrics.newCounter(new MetricName(metricPrefix, "", "sent"));
    Metrics.newGauge(new MetricName(metricPrefix, "", "accumulator.size"), new Gauge<Long>() {
      @Override
      public Long value() {
        return aggregatedDeltas.estimatedSize();
      }
    });
  }

  @VisibleForTesting
  public void flushDeltaCounters() {
    this.aggregatedDeltas.asMap().forEach(this::reportAggregatedDeltaValue);
  }

  private void reportAggregatedDeltaValue(@Nullable HostMetricTagsPair hostMetricTagsPair,
                                          @Nullable AtomicDouble value) {
    if (value == null || hostMetricTagsPair == null) {
      return;
    }
    this.reportedCounter.inc();
    double reportedValue = value.getAndSet(0);
    if (reportedValue == 0) return;
    String strPoint = metricToLineData(hostMetricTagsPair.metric, reportedValue, Clock.now(),
        hostMetricTagsPair.getHost(), hostMetricTagsPair.getTags(), "wavefront-proxy");
    getTask().add(strPoint);
  }

  @Override
  void reportInternal(ReportPoint point) {
    if (DeltaCounter.isDelta(point.getMetric())) {
      validatePoint(point, validationConfig);
      getReceivedCounter().inc();
      double deltaValue = (double) point.getValue();
      receivedPointLag.update(Clock.now() - point.getTimestamp());
      HostMetricTagsPair hostMetricTagsPair = new HostMetricTagsPair(point.getHost(),
          point.getMetric(), point.getAnnotations());
      Objects.requireNonNull(aggregatedDeltas.get(hostMetricTagsPair, key -> new AtomicDouble(0))).
          getAndAdd(deltaValue);
      if (validItemsLogger != null && validItemsLogger.isLoggable(Level.FINEST)) {
        validItemsLogger.info(serializer.apply(point));
      }
    } else {
      reject(point, "Port is not configured to accept non-delta counter data!");
    }
  }

  @Override
  public void shutdown() {
    super.shutdown();
    reporter.shutdown();
  }
}
