package org.javaup.history.api.dto;

import java.time.Instant;
import java.util.UUID;

public record FinalizedAgentResultResponse(UUID resultId, int revisionNo, Instant finalizedAt) {}
