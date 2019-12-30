package com.wavefront.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.wavefront.agent.api.APIContainer;
import com.wavefront.agent.config.ProxyConfig;
import com.wavefront.api.agent.AgentConfiguration;
import com.wavefront.common.Clock;
import com.wavefront.common.NamedThreadFactory;
import com.wavefront.metrics.JsonMetricsGenerator;
import com.yammer.metrics.Metrics;
import org.apache.commons.lang3.ObjectUtils;

import javax.annotation.Nullable;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ProcessingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.wavefront.agent.ProxyUtil.getProcessId;
import static com.wavefront.agent.Utils.getBuildVersion;

/**
 * Registers the proxy with the back-end, sets up regular "check-ins" (every minute),
 * transmits proxy metrics to the back-end.
 *
 * @author vasily@wavefront.com
 */
public class ProxyCheckinScheduler {
  private static final Logger logger = Logger.getLogger("proxy");

  /**
   * A unique process ID value (PID, when available, or a random hexadecimal string), assigned
   * at proxy start-up, to be reported with all ~proxy metrics as a "processId" point tag to
   * prevent potential ~proxy metrics collisions caused by users spinning up multiple proxies
   * with duplicate names.
   */
  protected static final String processId = getProcessId();

  private final UUID proxyId;
  private final ProxyConfig proxyConfig;
  private final APIContainer apiContainer;
  private final Runnable updateConfiguration;
  private final Runnable updateAgentMetrics;

  protected String serverEndpointUrl = null;
  private JsonNode agentMetrics;
  private long agentMetricsCaptureTs;
  private volatile boolean hadSuccessfulCheckin = false;
  private volatile boolean retryCheckin = false;

  /**
   * Executors for support tasks.
   */
  private final ScheduledExecutorService agentConfigurationExecutor = Executors.
      newScheduledThreadPool(2, new NamedThreadFactory("proxy-configuration"));

  /**
   * @param proxyId                    Proxy UUID.
   * @param proxyConfig                Proxy settings.
   * @param apiContainer               API container object.
   * @param agentConfigurationConsumer Configuration processor, invoked after each
   *                                   successful configuration fetch.
   */
  public ProxyCheckinScheduler(UUID proxyId,
                               ProxyConfig proxyConfig,
                               APIContainer apiContainer,
                               Consumer<AgentConfiguration> agentConfigurationConsumer) {
    this.proxyId = proxyId;
    this.proxyConfig = proxyConfig;
    this.apiContainer = apiContainer;
    this.updateConfiguration = () -> {
      boolean doShutDown = false;
      try {
        AgentConfiguration config = checkin();
        if (config != null) {
          agentConfigurationConsumer.accept(config);
          doShutDown = config.getShutOffAgents();
        }
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Exception occurred during configuration update", e);
      } finally {
        if (doShutDown) {
          logger.warning("Shutting down: Server side flag indicating proxy has to shut down.");
          System.exit(1);
        }
      }
    };
    this.updateAgentMetrics = () -> {
      try {
        Map<String, String> pointTags = new HashMap<>(proxyConfig.getAgentMetricsPointTags());
        pointTags.put("processId", processId);
        synchronized (agentConfigurationExecutor) {
          agentMetricsCaptureTs = System.currentTimeMillis();
          agentMetrics = JsonMetricsGenerator.generateJsonMetrics(Metrics.defaultRegistry(),
              true, true, true, pointTags, null);
        }
      } catch (Exception ex) {
        logger.log(Level.SEVERE, "Could not generate proxy metrics", ex);
      }
    };
    updateAgentMetrics.run();
    AgentConfiguration config = checkin();
    if (config == null && retryCheckin) {
      // immediately retry check-ins if we need to re-attempt
      // due to changing the server endpoint URL
      updateAgentMetrics.run();
      config = checkin();
    }
    if (config != null) {
      logger.info("initial configuration is available, setting up proxy");
      agentConfigurationConsumer.accept(config);
      hadSuccessfulCheckin = true;
    }

  }

  /**
   * Set up and schedule regular check-ins.
   */
  public void scheduleCheckins() {
    logger.info("scheduling regular check-ins");
    agentConfigurationExecutor.scheduleAtFixedRate(updateAgentMetrics, 10, 60, TimeUnit.SECONDS);
    agentConfigurationExecutor.scheduleWithFixedDelay(updateConfiguration, 0, 1, TimeUnit.SECONDS);
  }

  /**
   * Check whether this proxy had at least one successful check-in.
   *
   * @return true if this proxy had at least one successful check-in.
   */
  public boolean hadSuccessfulCheckin() {
    return hadSuccessfulCheckin;
  }

  /**
   * Stops regular check-ins.
   */
  public void shutdown() {
    agentConfigurationExecutor.shutdown();
  }

  /**
   * Perform agent check-in and fetch configuration of the daemon from remote server.
   *
   * @return Fetched configuration. {@code null} if the configuration is invalid.
   */
  private AgentConfiguration checkin() {
    AgentConfiguration newConfig;
    JsonNode agentMetricsWorkingCopy;
    long agentMetricsCaptureTsWorkingCopy;
    synchronized(agentConfigurationExecutor) {
      if (agentMetrics == null) return null;
      agentMetricsWorkingCopy = agentMetrics;
      agentMetricsCaptureTsWorkingCopy = agentMetricsCaptureTs;
      agentMetrics = null;
    }
    logger.info("Checking in: " + ObjectUtils.firstNonNull(serverEndpointUrl,
        proxyConfig.getServer()));
    try {
      newConfig = apiContainer.getProxyV2API().proxyCheckin(proxyId,
          "Bearer " + proxyConfig.getToken(), proxyConfig.getHostname(), getBuildVersion(),
          agentMetricsCaptureTsWorkingCopy, agentMetricsWorkingCopy, proxyConfig.isEphemeral());
      agentMetricsWorkingCopy = null;
    } catch (ClientErrorException ex) {
      agentMetricsWorkingCopy = null;
      switch (ex.getResponse().getStatus()) {
        case 401:
          checkinError("HTTP 401 Unauthorized: Please verify that your server and token settings",
              "are correct and that the token has Proxy Management permission!");
          break;
        case 403:
          checkinError("HTTP 403 Forbidden: Please verify that your token has Proxy Management " +
              "permission!", null);
          break;
        case 404:
        case 405:
          String serverUrl = proxyConfig.getServer().replaceAll("/$", "");
          if (!hadSuccessfulCheckin && !retryCheckin && !serverUrl.endsWith("/api")) {
            this.serverEndpointUrl = serverUrl + "/api/";
            checkinError("Possible server endpoint misconfiguration detected, attempting to use " +
                serverEndpointUrl, null);
            apiContainer.updateServerEndpointURL(serverEndpointUrl);
            retryCheckin = true;
            return null;
          }
          String secondaryMessage = serverUrl.endsWith("/api") ?
              "Current setting: " + proxyConfig.getServer() :
              "Server endpoint URLs normally end with '/api/'. Current setting: " +
                  proxyConfig.getServer();
          checkinError("HTTP " + ex.getResponse().getStatus() + ": Misconfiguration detected, " +
              "please verify that your server setting is correct", secondaryMessage);
          if (!hadSuccessfulCheckin) {
            logger.warning("Aborting start-up");
            System.exit(-5);
          }
          break;
        case 407:
          checkinError("HTTP 407 Proxy Authentication Required: Please verify that " +
                  "proxyUser and proxyPassword",
              "settings are correct and make sure your HTTP proxy is not rate limiting!");
          break;
        default:
          checkinError("HTTP " + ex.getResponse().getStatus() +
                  " error: Unable to check in with Wavefront!",
              proxyConfig.getServer() + ": " + Throwables.getRootCause(ex).getMessage());
      }
      return new AgentConfiguration(); // return empty configuration to prevent checking in every 1s
    } catch (ProcessingException ex) {
      Throwable rootCause = Throwables.getRootCause(ex);
      if (rootCause instanceof UnknownHostException) {
        checkinError("Unknown host: " + proxyConfig.getServer() +
            ". Please verify your DNS and network settings!", null);
        return null;
      }
      if (rootCause instanceof ConnectException ||
          rootCause instanceof SocketTimeoutException) {
        checkinError("Unable to connect to " + proxyConfig.getServer() + ": " + rootCause.getMessage(),
            "Please verify your network/firewall settings!");
        return null;
      }
      checkinError("Request processing error: Unable to retrieve proxy configuration!",
          proxyConfig.getServer() + ": " + rootCause);
      return null;
    } catch (Exception ex) {
      checkinError("Unable to retrieve proxy configuration from remote server!",
          proxyConfig.getServer() + ": " + Throwables.getRootCause(ex));
      return null;
    } finally {
      synchronized(agentConfigurationExecutor) {
        // if check-in process failed (agentMetricsWorkingCopy is not null) and agent metrics have
        // not been updated yet, restore last known set of agent metrics to be retried
        if (agentMetricsWorkingCopy != null && agentMetrics == null) {
          agentMetrics = agentMetricsWorkingCopy;
        }
      }
    }
    try {
      if (newConfig.currentTime != null) {
        Clock.set(newConfig.currentTime);
      }
    } catch (Exception ex) {
      logger.log(Level.WARNING, "configuration retrieved from server is invalid", ex);
      try {
        apiContainer.getProxyV2API().proxyError(proxyId, "Configuration file is invalid: " +
            ex.toString());
      } catch (Exception e) {
        logger.log(Level.WARNING, "cannot report error to collector", e);
      }
      return null;
    }
    return newConfig;
  }

  private void checkinError(String errMsg, @Nullable String secondErrMsg) {
    if (hadSuccessfulCheckin) {
      logger.severe(errMsg + (secondErrMsg == null ? "" : " " + secondErrMsg));
    } else {
      logger.severe(Strings.repeat("*", errMsg.length()));
      logger.severe(errMsg);
      if (secondErrMsg != null) {
        logger.severe(secondErrMsg);
      }
      logger.severe(Strings.repeat("*", errMsg.length()));
    }
  }
}