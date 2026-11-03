package org.javaup.history.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.javaup.history.api.dto.ReplaceVisualReferencesRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VisualReferenceValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void permitsEmptyListToClearReferencesAndUpToTwelvePictures() {
        assertTrue(validator.validate(new ReplaceVisualReferencesRequest(List.of(), 1)).isEmpty());
        assertTrue(validator.validate(new ReplaceVisualReferencesRequest(
                Collections.nCopies(12, UUID.randomUUID()), 2)).isEmpty());
    }

    @Test
    void rejectsMissingListVersionAndNonPositiveVersions() {
        var paths = validator.validate(new ReplaceVisualReferencesRequest(null, null)).stream()
                .map(violation -> violation.getPropertyPath().toString()).toList();

        assertTrue(paths.containsAll(List.of("pictureIds", "version")));
        assertFalse(validator.validate(new ReplaceVisualReferencesRequest(List.of(), 0)).isEmpty());
        assertFalse(validator.validate(new ReplaceVisualReferencesRequest(List.of(), -1)).isEmpty());
    }

    @Test
    void rejectsNullElementsAndMoreThanTwelvePictures() {
        assertTrue(validator.validate(new ReplaceVisualReferencesRequest(
                Collections.singletonList(null), 1)).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().contains("pictureIds[0]")));
        assertTrue(validator.validate(new ReplaceVisualReferencesRequest(
                Collections.nCopies(13, UUID.randomUUID()), 1)).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("pictureIds")));
    }
}
