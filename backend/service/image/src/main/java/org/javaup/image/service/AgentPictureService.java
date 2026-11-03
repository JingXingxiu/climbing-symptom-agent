package org.javaup.image.service;

import java.util.List;
import java.util.UUID;
import org.javaup.image.api.dto.ResolveAgentPicturesRequest;
import org.javaup.image.api.dto.ResolvedAgentPicture;

public interface AgentPictureService {
    List<ResolvedAgentPicture> resolve(UUID currentUserId, ResolveAgentPicturesRequest request);
}
