package org.javaup.image.api;

import static org.hamcrest.Matchers.hasItems;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.javaup.image.api.dto.PictureProcessingStatus;
import org.javaup.image.api.dto.PictureResponse;
import org.javaup.image.api.dto.PictureUploadMetadata;
import org.javaup.image.api.dto.PictureUploadStatus;
import org.javaup.image.api.dto.VisualSourceType;
import org.javaup.image.api.problem.ApiProblemAdvice;
import org.javaup.image.service.PictureAssetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class ImageControllerUploadTest {

    private static final UUID USER_ID = UUID.fromString("0ead065a-3c98-48ea-81fe-cbd85995ccba");
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("44c5dfac-986f-4b3c-9372-1b095793d389");
    private static final UUID CLIENT_REQUEST_ID = UUID.fromString("913a6904-62c0-437c-9d38-b79318867da4");
    private static final UUID ASSESSMENT_ID = UUID.fromString("294a4f6c-d956-4372-9814-38bba5827d50");

    private PictureAssetService service;
    private ValidatorFactory validatorFactory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(PictureAssetService.class);
        validatorFactory = Validation.buildDefaultValidatorFactory();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ImageController(service, JsonMapper.builder().build(), validatorFactory.getValidator()))
                .setControllerAdvice(new ApiProblemAdvice())
                .build();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text/plain", "application/json"})
    void acceptsUniUploadStringAndJsonPartsAndPreservesServiceArguments(String contentType) throws Exception {
        var metadata = new PictureUploadMetadata(
                VisualSourceType.USER_UPLOAD, CLIENT_REQUEST_ID, ASSESSMENT_ID, null, "右手中指不适");
        var file = new MockMultipartFile("file", "hand.jpg", "image/jpeg", new byte[] {1, 2, 3});
        var metadataPart = metadataPart(JsonMapper.builder().build().writeValueAsString(metadata), contentType);
        UUID pictureId = UUID.fromString("234f7a13-b156-421e-8b11-e1ce73cb8fb9");
        Instant createdAt = Instant.parse("2026-10-20T00:00:00Z");
        var response = new PictureResponse(
                pictureId, USER_ID, VisualSourceType.USER_UPLOAD, null, null, null,
                "hand.jpg", "image/jpeg", 3, null, null,
                PictureUploadStatus.UPLOADED, PictureProcessingStatus.PENDING,
                null, null, null, createdAt, createdAt);
        when(service.uploadPicture(eq(USER_ID), eq(IDEMPOTENCY_KEY), same(file), eq(metadata)))
                .thenReturn(response);

        mockMvc.perform(uploadRequest().file(file).part(metadataPart))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pictureId").value(pictureId.toString()));

        verify(service).uploadPicture(eq(USER_ID), eq(IDEMPOTENCY_KEY), same(file), eq(metadata));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "\"not an object\"",
            "{\"sourceType\":\"UNKNOWN\",\"clientRequestId\":\"913a6904-62c0-437c-9d38-b79318867da4\"}"})
    void rejectsMalformedOrNonObjectMetadata(String metadataJson) throws Exception {
        mockMvc.perform(uploadRequest().file(file()).part(metadataPart(metadataJson, null)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    void validatesRequiredMetadataFields() throws Exception {
        mockMvc.perform(uploadRequest().file(file()).part(metadataPart("{}", null)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItems("sourceType", "clientRequestId")));

        verifyNoInteractions(service);
    }

    @Test
    void validatesNestedMetadataFields() throws Exception {
        String metadataJson = """
                {
                  "sourceType": "MODEL_SNAPSHOT",
                  "clientRequestId": "913a6904-62c0-437c-9d38-b79318867da4",
                  "viewState": {
                    "modelAssetVersion": "",
                    "target": {"x": 0, "y": 0, "z": 0},
                    "zoom": 0
                  }
                }
                """;

        mockMvc.perform(uploadRequest().file(file()).part(metadataPart(metadataJson, "application/json")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(
                        hasItems("viewState.modelAssetVersion", "viewState.camera", "viewState.zoom")));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMissingMetadataPart() throws Exception {
        mockMvc.perform(uploadRequest().file(file()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_VALUE"));

        verifyNoInteractions(service);
    }

    private MockMultipartHttpServletRequestBuilder uploadRequest() {
        var request = multipart("/api/image/v1/pictures");
        request.header("X-User-Id", USER_ID.toString());
        request.header("X-Idempotency-Key", IDEMPOTENCY_KEY.toString());
        return request;
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "hand.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private MockPart metadataPart(String metadataJson, String contentType) {
        var part = new MockPart("metadata", metadataJson.getBytes(StandardCharsets.UTF_8));
        if (contentType != null) {
            part.getHeaders().setContentType(MediaType.parseMediaType(contentType));
        }
        return part;
    }
}
