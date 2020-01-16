package com.wavefront.agent.listeners.tracing;

import com.google.common.annotations.VisibleForTesting;

import com.fasterxml.jackson.databind.JsonNode;
import com.wavefront.agent.auth.TokenAuthenticator;
import com.wavefront.agent.channel.HealthCheckManager;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.agent.preprocessor.ReportableEntityPreprocessor;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.ingester.ReportableEntityDecoder;
import com.wavefront.internal.reporter.WavefrontInternalReporter;
import com.wavefront.sdk.common.WavefrontSender;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import io.netty.channel.ChannelHandler;
import wavefront.report.Annotation;
import wavefront.report.Span;
import wavefront.report.SpanLogs;

import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.ERROR_SPAN_TAG_KEY;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.reportHeartbeats;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.reportWavefrontGeneratedData;
import static com.wavefront.sdk.common.Constants.APPLICATION_TAG_KEY;
import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.COMPONENT_TAG_KEY;
import static com.wavefront.sdk.common.Constants.NULL_TAG_VAL;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;

/**
 * Handler that process trace data sent from tier 1 SDK.
 *
 * @author djia@vmware.com
 */
@ChannelHandler.Sharable
public class CustomTracingPortUnificationHandler extends TracePortUnificationHandler {
  private static final Logger logger = Logger.getLogger(
      CustomTracingPortUnificationHandler.class.getCanonicalName());
  @Nullable
  private final WavefrontSender wfSender;
  private final WavefrontInternalReporter wfInternalReporter;
  private final ConcurrentMap<HeartbeatMetricKey, Boolean> discoveredHeartbeatMetrics;
  private final Set<String> traceDerivedCustomTagKeys;

  /**
   * @param handle                    handle/port number.
   * @param tokenAuthenticator        {@link TokenAuthenticator} for incoming requests.
   * @param healthCheckManager        shared health check endpoint handler.
   * @param traceDecoder              trace decoders.
   * @param spanLogsDecoder           span logs decoders.
   * @param preprocessor              preprocessor.
   * @param handlerFactory            factory for ReportableEntityHandler objects.
   * @param sampler                   sampler.
   * @param alwaysSampleErrors        always sample spans with error tag.
   * @param traceDisabled             supplier for backend-controlled feature flag for spans.
   * @param spanLogsDisabled          supplier for backend-controlled feature flag for span logs.
   * @param wfSender                  sender to send trace to Wavefront.
   * @param traceDerivedCustomTagKeys custom tags added to derived RED metrics.
   */
  public CustomTracingPortUnificationHandler(
      String handle, TokenAuthenticator tokenAuthenticator, HealthCheckManager healthCheckManager,
      ReportableEntityDecoder<String, Span> traceDecoder,
      ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder,
      @Nullable Supplier<ReportableEntityPreprocessor> preprocessor,
      ReportableEntityHandlerFactory handlerFactory, Sampler sampler, boolean alwaysSampleErrors,
      Supplier<Boolean> traceDisabled, Supplier<Boolean> spanLogsDisabled,
      @Nullable WavefrontSender wfSender, Set<String> traceDerivedCustomTagKeys) {
    this(handle, tokenAuthenticator, healthCheckManager, traceDecoder, spanLogsDecoder,
        preprocessor, handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE, handle)),
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE_SPAN_LOGS, handle)),
        sampler, alwaysSampleErrors, traceDisabled, spanLogsDisabled, wfSender,
        traceDerivedCustomTagKeys);
  }

  @VisibleForTesting
  public CustomTracingPortUnificationHandler(
      String handle, TokenAuthenticator tokenAuthenticator, HealthCheckManager healthCheckManager,
      ReportableEntityDecoder<String, Span> traceDecoder,
      ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder,
      @Nullable Supplier<ReportableEntityPreprocessor> preprocessor,
      final ReportableEntityHandler<Span, String> handler,
      final ReportableEntityHandler<SpanLogs, String> spanLogsHandler, Sampler sampler,
      boolean alwaysSampleErrors, Supplier<Boolean> traceDisabled,
      Supplier<Boolean> spanLogsDisabled, @Nullable WavefrontSender wfSender,
      Set<String> traceDerivedCustomTagKeys) {
    super(handle, tokenAuthenticator, healthCheckManager, traceDecoder, spanLogsDecoder,
        preprocessor, handler, spanLogsHandler, sampler, alwaysSampleErrors, traceDisabled, spanLogsDisabled);
    this.wfSender = wfSender;
    this.discoveredHeartbeatMetrics = new ConcurrentHashMap<>();
    this.traceDerivedCustomTagKeys = traceDerivedCustomTagKeys;

    if (wfSender != null) {
      wfInternalReporter = new WavefrontInternalReporter.Builder().
          prefixedWith("tracing.derived").withSource("custom_tracing").reportMinuteDistribution().
          build(wfSender);
      // Start the reporter
      wfInternalReporter.start(1, TimeUnit.MINUTES);
    } else {
      wfInternalReporter = null;
    }
  }

  @Override
  protected void report(Span object, boolean sampleError) {
    // report converted metrics/histograms from the span
    String applicationName = NULL_TAG_VAL;
    String serviceName = NULL_TAG_VAL;
    String cluster = NULL_TAG_VAL;
    String shard = NULL_TAG_VAL;
    String componentTagValue = NULL_TAG_VAL;
    String isError = "false";
    if (wfInternalReporter != null) {
      List<Annotation> annotations = object.getAnnotations();
      for (Annotation annotation : annotations) {
        switch (annotation.getKey()) {
          case APPLICATION_TAG_KEY:
            applicationName = annotation.getValue();
            continue;
          case SERVICE_TAG_KEY:
            serviceName = annotation.getValue();
          case CLUSTER_TAG_KEY:
            cluster = annotation.getValue();
            continue;
          case SHARD_TAG_KEY:
            shard = annotation.getValue();
            continue;
          case COMPONENT_TAG_KEY:
            componentTagValue = annotation.getValue();
            break;
          case ERROR_SPAN_TAG_KEY:
            isError = annotation.getValue();
            break;
        }
      }
      if (applicationName.equals(NULL_TAG_VAL) || serviceName.equals(NULL_TAG_VAL)) {
        logger.warning("Ingested spans discarded because span application/service name is " +
            "missing.");
        discardedSpans.inc();
        return;
      }

      if (sampleError || sample(object)) {
        handler.report(object);
      }

      discoveredHeartbeatMetrics.putIfAbsent(reportWavefrontGeneratedData(wfInternalReporter,
          object.getName(), applicationName, serviceName, cluster, shard, object.getSource(),
          componentTagValue, Boolean.parseBoolean(isError), object.getDuration(),
          traceDerivedCustomTagKeys, annotations), true);
      try {
        reportHeartbeats("customTracing", wfSender, discoveredHeartbeatMetrics);
      } catch (IOException e) {
        logger.log(Level.WARNING, "Cannot report heartbeat metric to wavefront");
      }
    }
  }
}
