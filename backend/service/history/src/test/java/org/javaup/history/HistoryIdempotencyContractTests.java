package org.javaup.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import com.baomidou.mybatisplus.annotation.TableField;
import org.javaup.history.api.HistoryController;
import org.javaup.history.api.dto.AppendAssessmentAnswerRequest;
import org.javaup.history.api.dto.SubmitAssessmentRequest;
import org.javaup.history.entity.Assessment;
import org.javaup.history.service.AssessmentService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestHeader;

class HistoryIdempotencyContractTests {

    @Test
    void createIdempotencyKeyMapsToDedicatedAssessmentColumn() throws NoSuchFieldException {
        var field = Assessment.class.getDeclaredField("createIdempotencyKey");
        TableField mapping = field.getAnnotation(TableField.class);

        assertThat(field.getType()).isEqualTo(java.util.UUID.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).isEqualTo("create_idempotency_key");
    }

    @Test
    void onlyCreateAssessmentUsesTheIdempotencyHeaderAndAllEndpointsUseTrustedUser() {
        Method create = methodNamed("createAssessment");
        Method answer = methodNamed("appendAssessmentAnswer");
        Method submit = methodNamed("submitAssessment");

        assertThat(requestHeaders(create))
                .extracting(RequestHeader::value)
                .containsExactly("X-User-Id", "X-Idempotency-Key");
        assertThat(requestHeaders(answer))
                .extracting(RequestHeader::value)
                .containsExactly("X-User-Id");
        assertThat(requestHeaders(submit))
                .extracting(RequestHeader::value)
                .containsExactly("X-User-Id");

        assertThat(Arrays.stream(HistoryController.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())))
                .allSatisfy(method -> assertThat(requestHeaders(method))
                        .extracting(RequestHeader::value)
                        .contains("X-User-Id"));
    }

    @Test
    void assessmentServiceDeclaresTheElevenUseCasesWithCurrentUserFirst() {
        Method[] methods = AssessmentService.class.getDeclaredMethods();

        assertThat(methods)
                .extracting(Method::getName)
                .containsExactlyInAnyOrder(
                        "create",
                        "list",
                        "get",
                        "updateBasics",
                        "resume",
                        "replaceLocations",
                        "clearLocations",
                        "replaceVisuals",
                        "appendAnswer",
                        "submit",
                        "getResult"
                );
        assertThat(methods).allSatisfy(method ->
                assertThat(method.getParameterTypes()[0]).isEqualTo(java.util.UUID.class));
    }

    @Test
    void answerAndSubmitKeepTheirBodyLevelIdempotencyInputs() {
        assertThat(recordComponentType(AppendAssessmentAnswerRequest.class, "clientAnswerId"))
                .isEqualTo(java.util.UUID.class);
        assertThat(SubmitAssessmentRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("version");
    }

    private static Method methodNamed(String name) {
        return Arrays.stream(HistoryController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Class<?> recordComponentType(Class<?> recordType, String componentName) {
        return Arrays.stream(recordType.getRecordComponents())
                .filter(component -> component.getName().equals(componentName))
                .map(component -> component.getType())
                .findFirst()
                .orElseThrow();
    }

    private static RequestHeader[] requestHeaders(Method method) {
        return Arrays.stream(method.getParameters())
                .map(Parameter::getAnnotations)
                .flatMap(Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .toArray(RequestHeader[]::new);
    }
}
