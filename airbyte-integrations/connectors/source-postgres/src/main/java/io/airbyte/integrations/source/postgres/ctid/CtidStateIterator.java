package io.airbyte.integrations.source.postgres.ctid;

import static io.airbyte.integrations.source.postgres.ctid.CtidStateManager.CTID_STATUS_VERSION;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.AbstractIterator;
import io.airbyte.integrations.source.postgres.internal.models.CtidStatus;
import io.airbyte.integrations.source.postgres.internal.models.InternalModels.StateType;
import io.airbyte.protocol.models.AirbyteStreamNameNamespacePair;
import io.airbyte.protocol.models.v0.AirbyteMessage;
import io.airbyte.protocol.models.v0.AirbyteMessage.Type;
import io.airbyte.protocol.models.v0.AirbyteStateMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.function.BiFunction;
import javax.annotation.CheckForNull;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CtidStateIterator extends AbstractIterator<AirbyteMessage> implements Iterator<AirbyteMessage> {
  private static final Logger LOGGER = LoggerFactory.getLogger(CtidStateIterator.class);
  public static final Duration SYNC_CHECKPOINT_DURATION = Duration.ofMinutes(15);
  public static final Integer SYNC_CHECKPOINT_RECORDS = 10_000;


  private final Iterator<AirbyteMessage> messageIterator;
  private final AirbyteStreamNameNamespacePair pair;
  private boolean hasEmittedFinalState;
  private boolean hasCaughtException = false;
  private String lastCtid;
  private final JsonNode streamStateForIncrementalRun;
  private final long relationFileNode;
  private final BiFunction<AirbyteStreamNameNamespacePair, JsonNode, AirbyteStateMessage> finalStateMessageSupplier;
  private long recordCount = 0L;
  private Instant lastCheckpoint = Instant.now();
  private final Duration syncCheckpointDuration;
  private final Long syncCheckpointRecords;

  public CtidStateIterator(final Iterator<AirbyteMessage> messageIterator,
      final AirbyteStreamNameNamespacePair pair,
      final long relationFileNode,
      final JsonNode streamStateForIncrementalRun,
      final BiFunction<AirbyteStreamNameNamespacePair, JsonNode, AirbyteStateMessage> finalStateMessageSupplier,
      final Duration checkpointDuration,
      final Long checkpointRecords) {
    this.messageIterator = messageIterator;
    this.pair = pair;
    this.relationFileNode = relationFileNode;
    this.streamStateForIncrementalRun = streamStateForIncrementalRun;
    this.finalStateMessageSupplier = finalStateMessageSupplier;
    this.syncCheckpointDuration = checkpointDuration;
    this.syncCheckpointRecords = checkpointRecords;
  }

  @CheckForNull
  @Override
  protected AirbyteMessage computeNext() {
    if (hasCaughtException) {
      // Mark iterator as done since the next call to messageIterator will result in an
      // IllegalArgumentException and resets exception caught state.
      // This occurs when the previous iteration emitted state so this iteration cycle will indicate
      // iteration is complete
      hasCaughtException = false;
      return endOfData();
    }

    if (messageIterator.hasNext()) {
      if ((recordCount >= syncCheckpointRecords || Duration.between(lastCheckpoint, OffsetDateTime.now()).compareTo(syncCheckpointDuration) > 0)
          && StringUtils.isNotBlank(lastCtid)) {
        final CtidStatus ctidStatus = new CtidStatus()
            .withVersion(CTID_STATUS_VERSION)
            .withStateType(StateType.CTID)
            .withCtid(lastCtid)
            .withIncrementalState(streamStateForIncrementalRun)
            .withRelationFilenode(relationFileNode);
        LOGGER.info("Emitting ctid state for stream {}, state is {}", pair, ctidStatus);
        recordCount = 0L;
        lastCheckpoint = Instant.now();
        return CtidStateManager.createPerStreamStateMessage(pair, ctidStatus);
      }
      // Use try-catch to catch Exception that could occur when connection to the database fails
      try {
        final AirbyteMessage message = messageIterator.next();
        if (message.getRecord().getData().hasNonNull("ctid")) {
          this.lastCtid = message.getRecord().getData().get("ctid").asText();
        }
        recordCount++;
        return message;
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    } else if (!hasEmittedFinalState) {
      hasEmittedFinalState = true;
      return new AirbyteMessage()
          .withType(Type.STATE)
          .withState(finalStateMessageSupplier.apply(pair, streamStateForIncrementalRun));
    } else {
      return endOfData();
    }
  }
}
