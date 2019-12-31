package com.wavefront.agent.handlers;

import com.wavefront.api.agent.ValidationConfiguration;
import com.wavefront.common.Clock;
import com.wavefront.ingester.ReportPointSerializer;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.MetricName;
import com.yammer.metrics.core.MetricsRegistry;
import wavefront.report.ReportPoint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.logging.Logger;

import static com.wavefront.data.Validation.validatePoint;

/**
 * Handler that processes incoming ReportPoint objects, validates them and hands them over to one of
 * the {@link SenderTask} threads.
 *
 * @author vasily@wavefront.com
 */
class ReportPointHandlerImpl extends AbstractReportableEntityHandler<ReportPoint, String> {

  final Logger validItemsLogger;
  final ValidationConfiguration validationConfig;
  final Histogram receivedPointLag;

  /**
   * Creates a new instance that handles either histograms or points.
   *
   * @param handlerKey           handler key for the metrics pipeline.
   * @param blockedItemsPerBatch controls sample rate of how many blocked points are written
   *                             into the main log file.
   * @param senderTasks          sender tasks.
   * @param validationConfig     validation configuration.
   * @param setupMetrics         Whether we should report counter metrics.
   * @param blockedItemLogger    logger for blocked items (optional).
   * @param validItemsLogger     sampling logger for valid items (optional).
   */
  ReportPointHandlerImpl(final HandlerKey handlerKey,
                         final int blockedItemsPerBatch,
                         @Nullable final Collection<SenderTask<String>> senderTasks,
                         @Nonnull final ValidationConfiguration validationConfig,
                         final boolean setupMetrics,
                         @Nullable final Logger blockedItemLogger,
                         @Nullable final Logger validItemsLogger) {
    super(handlerKey, blockedItemsPerBatch, new ReportPointSerializer(), senderTasks, setupMetrics,
        blockedItemLogger);
    this.validationConfig = validationConfig;
    this.validItemsLogger = validItemsLogger;
    MetricsRegistry registry = setupMetrics ? Metrics.defaultRegistry() : LOCAL_REGISTRY;
    this.receivedPointLag = registry.newHistogram(new MetricName(handlerKey.getEntityType() + "." +
        handlerKey.getHandle() + ".received", "", "lag"), false);
  }

  @Override
  void reportInternal(ReportPoint point) {
    validatePoint(point, validationConfig);
    receivedPointLag.update(Clock.now() - point.getTimestamp());
    final String strPoint = serializer.apply(point);
    getTask().add(strPoint);
    getReceivedCounter().inc();
    if (validItemsLogger != null) validItemsLogger.info(strPoint);
  }
}
