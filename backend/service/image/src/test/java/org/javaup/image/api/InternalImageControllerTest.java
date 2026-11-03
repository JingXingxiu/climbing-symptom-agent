package org.javaup.image.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.javaup.image.api.dto.ResolveVisualReferencesRequest;
import org.javaup.image.api.dto.ResolvedVisualReference;
import org.javaup.image.api.dto.VisualSourceType;
import org.javaup.image.api.problem.ApiException;
import org.javaup.image.api.problem.ApiProblemAdvice;
import org.javaup.image.service.PictureAssetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import tools.jackson.databind.json.JsonMapper;

class InternalImageControllerTest {

    private static final String PATH = "/internal/image/visual-references/resolve";
    private final UUID userId = UUID.randomUUID();
    private final UUID assessmentId = UUID.randomUUID();
    private final UUID pictureId = UUID.randomUUID();
    private PictureAssetService service;
    private MockMvc mvc;
    private ValidatorFactory validatorFactory;

    @BeforeEach
    void setUp() {
        service = mock(PictureAssetService.class);
        validatorFactory = Validation.buildDefaultValidatorFactory();
        mvc = MockMvcBuilders.standaloneSetup(new InternalImageController(service))
                .setValidator(new SpringValidatorAdapter(validatorFactory.getValidator()))
                .setControllerAdvice(new ApiProblemAdvice())
                .build();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    void returnsMinimalOrderedBatchResponse() throws Exception {
        UUID annotationSetId = UUID.randomUUID();
        var request = new ResolveVisualReferencesRequest(assessmentId, List.of(pictureId));
        when(service.resolveVisualReferences(userId, request)).thenReturn(List.of(
                new ResolvedVisualReference(pictureId, VisualSourceType.MODEL_SNAPSHOT, annotationSetId)));

        mvc.perform(post(PATH).header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pictureId").value(pictureId.toString()))
                .andExpect(jsonPath("$[0].sourceType").value("MODEL_SNAPSHOT"))
                .andExpect(jsonPath("$[0].annotationSetId").value(annotationSetId.toString()))
                .andExpect(jsonPath("$[0].originalUrl").doesNotExist())
                .andExpect(jsonPath("$[0].shapes").doesNotExist());
        verify(service).resolveVisualReferences(userId, request);
    }

    @Test
    void acceptsEmptyListForClearingReferences() throws Exception {
        var request = new ResolveVisualReferencesRequest(assessmentId, List.of());
        when(service.resolveVisualReferences(userId, request)).thenReturn(List.of());

        mvc.perform(post(PATH).header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"assessmentId\":\"invalid\",\"pictureIds\":[]}",
            "{\"assessmentId\":\"1d75b60b-9418-457c-a408-e22424183a80\",\"pictureIds\":null}",
            "{\"assessmentId\":\"1d75b60b-9418-457c-a408-e22424183a80\",\"pictureIds\":[null]}"})
    void rejectsMalformedOrInvalidBody(String body) throws Exception {
        mvc.perform(post(PATH).header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMoreThanTwelvePictures() throws Exception {
        var request = new ResolveVisualReferencesRequest(assessmentId, Collections.nCopies(13, pictureId));
        mvc.perform(post(PATH).header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @Test
    void requiresCurrentUserHeader() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(
                                new ResolveVisualReferencesRequest(assessmentId, List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_VALUE"));
        verifyNoInteractions(service);
    }

    @Test
    void preservesWholeBatchBusinessFailureAsProblemResponse() throws Exception {
        when(service.resolveVisualReferences(eq(userId), any())).thenThrow(new ApiException(
                HttpStatus.NOT_FOUND, "PICTURE_NOT_FOUND", "One picture is not accessible"));

        mvc.perform(post(PATH).header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonMapper.builder().build().writeValueAsString(
                                new ResolveVisualReferencesRequest(assessmentId, List.of(pictureId)))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PICTURE_NOT_FOUND"));
    }
}
