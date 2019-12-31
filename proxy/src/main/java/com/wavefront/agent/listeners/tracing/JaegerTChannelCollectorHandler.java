package com.wavefront.agent.listeners.tracing;

import com.google.common.base.Throwables;
import com.uber.tchannel.api.handlers.ThriftRequestHandler;
import com.uber.tchannel.messages.ThriftRequest;
import com.uber.tchannel.messages.ThriftResponse;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.agent.preprocessor.ReportableEntityPreprocessor;
import com.wavefront.common.NamedThreadFactory;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;
import io.jaegertracing.thriftjava.Batch;
import io.jaegertracing.thriftjava.Collector;
import org.apache.commons.lang.StringUtils;
import wavefront.report.Span;
import wavefront.report.SpanLogs;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.wavefront.agent.listeners.tracing.JaegerThriftUtils.processBatch;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.TRACING_DERIVED_PREFIX;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.reportHeartbeats;

/**
 * Handler that processes trace data in Jaeger Thrift compact format and
 * converts them to Wavefront format
 *
 * @author vasily@wavefront.com
 */
public class JaegerTChannelCollectorHandler extends ThriftRequestHandler<Collector.submitBatches_args,
    Collector.submitBatches_result> implements Runnable, Closeable {
  protected static final Logger logger =
      Logger.getLogger(JaegerTChannelCollectorHandler.class.getCanonicalName());

  private final static String JAEGER_COMPONENT = "jaeger";
  private final static String DEFAULT_SOURCE = "jaeger";

  private final String handle;
  private final ReportableEntityHandler<Span, String> spanHandler;
  private final ReportableEntityHandler<SpanLogs, String> spanLogsHandler;
  @Nullable
  private final WavefrontSender wfSender;
  @Nullable
  private final WavefrontInternalReporter wfInternalReporter;
  private final Supplier<Boolean> traceDisabled;
  private final Supplier<Boolean> spanLogsDisabled;
  private final Supplier<ReportableEntityPreprocessor> preprocessorSupplier;
  private final Sampler sampler;
  private final boolean alwaysSampleErrors;
  private final String proxyLevelApplicationName;
  private final Set<String> traceDerivedCustomTagKeys;

  private final Counter discardedTraces;
  private final Counter discardedBatches;
  private final Counter processedBatches;
  private final Counter failedBatches;
  private final Counter discardedSpansBySampler;
  private final ConcurrentMap<HeartbeatMetricKey, Boolean> discoveredHeartbeatMetrics;
  private final ScheduledExecutorService scheduledExecutorService;

  public JaegerTChannelCollectorHandler(String handle,
                                        ReportableEntityHandlerFactory handlerFactory,
                                        @Nullable WavefrontSender wfSender,
                                        Supplier<Boolean> traceDisabled,
                                        Supplier<Boolean> spanLogsDisabled,
                                        @Nullable Supplier<ReportableEntityPreprocessor> preprocessor,
                                        Sampler sampler,
                                        boolean alwaysSampleErrors,
                                        @Nullable String traceJaegerApplicationName,
                                        Set<String> traceDerivedCustomTagKeys) {
    this(handle, handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE, handle)),
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE_SPAN_LOGS, handle)),
        wfSender, traceDisabled, spanLogsDisabled, preprocessor, sampler, alwaysSampleErrors,
        traceJaegerApplicationName, traceDerivedCustomTagKeys);
  }

  public JaegerTChannelCollectorHandler(String handle,
                                        ReportableEntityHandler<Span, String> spanHandler,
                                        ReportableEntityHandler<SpanLogs, String> spanLogsHandler,
                                        @Nullable WavefrontSender wfSender,
                                        Supplier<Boolean> traceDisabled,
                                        Supplier<Boolean> spanLogsDisabled,
                                        @Nullable Supplier<ReportableEntityPreprocessor> preprocessor,
                                        Sampler sampler,
                                        boolean alwaysSampleErrors,
                                        @Nullable String traceJaegerApplicationName,
                                        Set<String> traceDerivedCustomTagKeys) {
    this.handle = handle;
    this.spanHandler = spanHandler;
    this.spanLogsHandler = spanLogsHandler;
    this.wfSender = wfSender;
    this.traceDisabled = traceDisabled;
    this.spanLogsDisabled = spanLogsDisabled;
    this.preprocessorSupplier = preprocessor;
    this.sampler = sampler;
    this.alwaysSampleErrors = alwaysSampleErrors;
    this.proxyLevelApplicationName = StringUtils.isBlank(traceJaegerApplicationName) ?
        "Jaeger" : traceJaegerApplicationName.trim();
    this.traceDerivedCustomTagKeys =  traceDerivedCustomTagKeys;
    this.discardedTraces = Metrics.newCounter(
        new MetricName("spans." + handle, "", "discarded"));
    this.discardedBatches = Metrics.newCounter(
        new MetricName("spans." + handle + ".batches", "", "discarded"));
    this.processedBatches = Metrics.newCounter(
        new MetricName("spans." + handle + ".batches", "", "processed"));
    this.failedBatches = Metrics.newCounter(
        new MetricName("spans." + handle + ".batches", "", "failed"));
    this.discardedSpansBySampler = Metrics.newCounter(
        new MetricName("spans." + handle, "", "sampler.discarded"));
    this.discoveredHeartbeatMetrics =  new ConcurrentHashMap<>();
    this.scheduledExecutorService = Executors.newScheduledThreadPool(1,
        new NamedThreadFactory("jaeger-heart-beater"));
    scheduledExecutorService.scheduleAtFixedRate(this, 1, 1, TimeUnit.MINUTES);

    if (wfSender != null) {
      wfInternalReporter = new WavefrontInternalReporter.Builder().
          prefixedWith(TRACING_DERIVED_PREFIX).withSource(DEFAULT_SOURCE).reportMinuteDistribution().
          build(wfSender);
      // Start the reporter
      wfInternalReporter.start(1, TimeUnit.MINUTES);
    } else {
      wfInternalReporter = null;
    }
  }

  @Override
  public ThriftResponse<Collector.submitBatches_result> handleImpl(
      ThriftRequest<Collector.submitBatches_args> request) {
    for (Batch batch : request.getBody(Collector.submitBatches_args.class).getBatches()) {
      try {
        processBatch(batch, null, DEFAULT_SOURCE, proxyLevelApplicationName, spanHandler,
            spanLogsHandler, wfInternalReporter, traceDisabled, spanLogsDisabled,
            preprocessorSupplier, sampler, alwaysSampleErrors, traceDerivedCustomTagKeys,
            discardedTraces, discardedBatches, discardedSpansBySampler, discoveredHeartbeatMetrics);
        processedBatches.inc();
      } catch (Exception e) {
        failedBatches.inc();
        logger.log(Level.WARNING, "Jaeger Thrift batch processing failed",
            Throwables.getRootCause(e));
      }
    }
    return new ThriftResponse.Builder<Collector.submitBatches_result>(request)
        .setBody(new Collector.submitBatches_result())
        .build();
  }

  @Override
  public void run() {
    try {
      reportHeartbeats(JAEGER_COMPONENT, wfSender, discoveredHeartbeatMetrics);
    } catch (IOException e) {
      logger.log(Level.WARNING, "Cannot report heartbeat metric to wavefront");
    }
  }

  @Override
  public void close() throws IOException {
    scheduledExecutorService.shutdownNow();
  }
}
