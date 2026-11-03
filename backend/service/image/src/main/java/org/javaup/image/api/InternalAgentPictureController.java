package org.javaup.image.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.javaup.image.api.dto.ResolveAgentPicturesRequest;
import org.javaup.image.api.dto.ResolvedAgentPicture;
import org.javaup.image.service.AgentPictureService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部路径不代替鉴权；部署时应只允许可信服务访问。 */
@Validated
@RestController
@RequestMapping(value = "/internal/image/agent-pictures", produces = MediaType.APPLICATION_JSON_VALUE)
public class InternalAgentPictureController {
    private final AgentPictureService agentPictureService;

    public InternalAgentPictureController(AgentPictureService agentPictureService) {
        this.agentPictureService = agentPictureService;
    }

    @PostMapping(value = "/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<ResolvedAgentPicture> resolve(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @Valid @RequestBody ResolveAgentPicturesRequest request
    ) {
        return agentPictureService.resolve(currentUserId, request);
    }
}
