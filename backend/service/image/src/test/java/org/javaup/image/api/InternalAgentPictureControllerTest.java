package org.javaup.image.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.validation.Validation;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.javaup.image.api.dto.ResolveAgentPicturesRequest;
import org.javaup.image.api.dto.ResolvedAgentPicture;
import org.javaup.image.api.problem.ApiProblemAdvice;
import org.javaup.image.service.AgentPictureService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import tools.jackson.databind.json.JsonMapper;

class InternalAgentPictureControllerTest {
    @Test
    void serializesCompleteSnapshotAndRequiresUserHeader() throws Exception {
        UUID user = UUID.randomUUID();
        UUID assessment = UUID.randomUUID();
        UUID picture = UUID.randomUUID();
        AgentPictureService service = mock(AgentPictureService.class);
        when(service.resolve(eq(user), any())).thenReturn(List.of(new ResolvedAgentPicture(picture,
                "USER_UPLOAD", "image/png", 800, 600, null, null, URI.create("https://example.com/image?signature=short"))));
        try (var validator = Validation.buildDefaultValidatorFactory()) {
            var mvc = MockMvcBuilders.standaloneSetup(new InternalAgentPictureController(service))
                    .setControllerAdvice(new ApiProblemAdvice())
                    .setValidator(new SpringValidatorAdapter(validator.getValidator())).build();
            String body = JsonMapper.builder().build().writeValueAsString(
                    new ResolveAgentPicturesRequest(assessment, List.of(picture)));
            mvc.perform(post("/internal/image/agent-pictures/resolve").header("X-User-Id", user)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].pictureId").value(picture.toString()))
                    .andExpect(jsonPath("$[0].width").value(800))
                    .andExpect(jsonPath("$[0].originalUrl").value("https://example.com/image?signature=short"));
            mvc.perform(post("/internal/image/agent-pictures/resolve").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/internal/image/agent-pictures/resolve").header("X-User-Id", user)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
            verify(service, times(1)).resolve(eq(user), any());
        }
    }
}
