package org.javaup.image.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.javaup.image.api.dto.AnnotationGeometry;
import org.javaup.image.api.dto.AnnotationShape;
import org.javaup.image.api.dto.AnnotationShapeType;
import org.javaup.image.api.dto.NormalizedPoint;
import org.javaup.image.api.dto.ReplaceAnnotationSetRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AnnotationValidationTest {

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
    void acceptsEmptyReplacementAndVersionZeroForFirstCreation() {
        var request = new ReplaceAnnotationSetRequest(UUID.randomUUID(), 1200, 800, List.of(), null, 0);

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMissingOrOutOfRangeRequiredRequestValues() {
        var request = new ReplaceAnnotationSetRequest(null, 0, -1, null, "x".repeat(1001), -1);

        var paths = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString()).toList();

        assertTrue(paths.containsAll(List.of("assessmentId", "sourceWidth", "sourceHeight",
                "shapes", "note", "version")));
        assertFalse(validator.validate(new ReplaceAnnotationSetRequest(
                UUID.randomUUID(), null, null, List.of(), null, null)).isEmpty());
    }

    @Test
    void rejectsNullShapeElementsAndOversizedLists() {
        var withNull = new ReplaceAnnotationSetRequest(UUID.randomUUID(), 1200, 800,
                Collections.singletonList(null), null, 0);
        assertTrue(validator.validate(withNull).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().contains("shapes[0]")));

        var tooMany = new ReplaceAnnotationSetRequest(UUID.randomUUID(), 1200, 800,
                Collections.nCopies(101, shape(new AnnotationGeometry.Point(new NormalizedPoint(0.5, 0.5)))),
                null, 0);
        assertTrue(validator.validate(tooMany).stream().anyMatch(violation ->
                violation.getPropertyPath().toString().equals("shapes")));
    }

    @Test
    void rejectsNullPointsInsideBothPolylineAndPolygon() {
        List<NormalizedPoint> withNull = Arrays.asList(new NormalizedPoint(0.2, 0.3), null);
        List<AnnotationShape> shapes = List.of(
                shape(new AnnotationGeometry.Polyline(withNull)),
                shape(new AnnotationGeometry.Polygon(withNull)));
        var request = new ReplaceAnnotationSetRequest(UUID.randomUUID(), 1200, 800, shapes, null, 0);

        var paths = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString()).toList();

        assertTrue(paths.stream().anyMatch(path -> path.contains("shapes[0]") && path.contains("points[1]")));
        assertTrue(paths.stream().anyMatch(path -> path.contains("shapes[1]") && path.contains("points[1]")));
    }

    @Test
    void preservesExistingTwoPointPolygonContractAndRejectsTooFewPoints() {
        var twoPoints = new AnnotationGeometry.Polygon(
                List.of(new NormalizedPoint(0.0, 0.0), new NormalizedPoint(1.0, 1.0)));
        assertTrue(validator.validate(twoPoints).isEmpty());
        assertFalse(validator.validate(new AnnotationGeometry.Polygon(
                List.of(new NormalizedPoint(0.5, 0.5)))).isEmpty());
        assertFalse(validator.validate(new AnnotationGeometry.Polyline(
                Collections.nCopies(2001, new NormalizedPoint(0.5, 0.5)))).isEmpty());
    }

    @Test
    void cascadesToNormalizedCoordinatesGeometryAndStyleConstraints() {
        var invalidShape = new AnnotationShape(
                UUID.randomUUID(), AnnotationShapeType.CIRCLE,
                new AnnotationGeometry.Circle(new NormalizedPoint(-0.01, 1.01), 0.0, 1.01),
                "red", 0.051, "x".repeat(121));
        var request = new ReplaceAnnotationSetRequest(UUID.randomUUID(), 1200, 800,
                List.of(invalidShape), null, 0);

        var paths = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString()).toList();

        for (String field : List.of("geometry.center.x", "geometry.center.y", "geometry.radiusX",
                "geometry.radiusY", "color", "lineWidthNormalized", "label")) {
            assertTrue(paths.stream().anyMatch(path -> path.endsWith(field)), () -> "Missing violation: " + field);
        }
    }

    private AnnotationShape shape(AnnotationGeometry geometry) {
        AnnotationShapeType type = switch (geometry) {
            case AnnotationGeometry.Point ignored -> AnnotationShapeType.POINT;
            case AnnotationGeometry.Circle ignored -> AnnotationShapeType.CIRCLE;
            case AnnotationGeometry.Polyline ignored -> AnnotationShapeType.POLYLINE;
            case AnnotationGeometry.Polygon ignored -> AnnotationShapeType.POLYGON;
        };
        return new AnnotationShape(UUID.randomUUID(), type, geometry, null, null, null);
    }
}
