package com.wavefront.agent.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.google.common.collect.ImmutableList;
import com.wavefront.agent.queueing.TaskQueue;
import com.wavefront.api.SourceTagAPI;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.dto.SourceTag;

import javax.annotation.Nullable;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link DataSubmissionTask} that handles source tag payloads.
 *
 * @author vasily@wavefront.com
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "__CLASS")
public class SourceTagSubmissionTask extends AbstractDataSubmissionTask<SourceTagSubmissionTask> {
  private transient SourceTagAPI api;

  @JsonProperty
  private SourceTag sourceTag;

  @SuppressWarnings("unused")
  SourceTagSubmissionTask() {
  }

  /**
   * @param api          API endpoint.
   * @param properties   container for mutable proxy settings.
   * @param backlog      backing queue.
   * @param handle       Handle (usually port number) of the pipeline where the data came from.
   * @param sourceTag    source tag operation
   * @param timeProvider Time provider (in millis).
   */
  public SourceTagSubmissionTask(SourceTagAPI api, EntityProperties properties,
                                 TaskQueue<SourceTagSubmissionTask> backlog, String handle,
                                 SourceTag sourceTag,
                                 @Nullable Supplier<Long> timeProvider) {
    super(properties, backlog, handle, ReportableEntityType.SOURCE_TAG, timeProvider);
    this.api = api;
    this.sourceTag = sourceTag;
  }

  @Nullable
  Response doExecute() {
    switch (sourceTag.getOperation()) {
      case SOURCE_DESCRIPTION:
        switch (sourceTag.getAction()) {
          case DELETE:
            return api.removeDescription(sourceTag.getSource());
          case SAVE:
          case ADD:
            return api.setDescription(sourceTag.getSource(), sourceTag.getAnnotations().get(0));
          default:
            throw new IllegalArgumentException("Invalid acton: " + sourceTag.getAction());
        }
      case SOURCE_TAG:
        switch (sourceTag.getAction()) {
          case ADD:
            return api.appendTag(sourceTag.getSource(), sourceTag.getAnnotations().get(0));
          case DELETE:
            return api.removeTag(sourceTag.getSource(), sourceTag.getAnnotations().get(0));
          case SAVE:
            return api.setTags(sourceTag.getSource(), sourceTag.getAnnotations());
          default:
            throw new IllegalArgumentException("Invalid acton: " + sourceTag.getAction());
        }
      default:
        throw new IllegalArgumentException("Invalid source tag operation: " +
            sourceTag.getOperation());
    }
  }

  public SourceTag payload() {
    return sourceTag;
  }

  @Override
  public int weight() {
    return 1;
  }

  @Override
  public List<SourceTagSubmissionTask> splitTask(int minSplitSize, int maxSplitSize) {
    return ImmutableList.of(this);
  }

  public void injectMembers(SourceTagAPI api, EntityProperties properties,
                            TaskQueue<SourceTagSubmissionTask> backlog) {
    this.api = api;
    this.properties = properties;
    this.backlog = backlog;
    this.timeProvider = System::currentTimeMillis;
  }
}
