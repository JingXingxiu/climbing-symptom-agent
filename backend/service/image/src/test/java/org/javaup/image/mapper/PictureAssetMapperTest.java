package org.javaup.image.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.javaup.image.entity.PictureAsset;
import org.javaup.image.persistence.typehandler.PgObjectTypeHandler;
import org.javaup.image.persistence.typehandler.UuidTypeHandler;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class PictureAssetMapperTest {

    private static final String INSERT_STATEMENT = PictureAssetMapper.class.getName() + ".insertIfAbsent";

    @Test
    void annotationParsesBeforeGlobalTypeHandlerScanningAndTargetsPartialUniqueIndex() {
        Configuration configuration = new Configuration();
        configuration.addMapper(PictureAssetMapper.class);

        BoundSql boundSql = configuration.getMappedStatement(INSERT_STATEMENT).getBoundSql(new PictureAsset());
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.startsWith("INSERT INTO picture_asset ( id, owner_user_id, source_type, "
                + "bucket_name, object_key, original_filename, content_type, size_bytes, sha256, "
                + "width_px, height_px, storage_status, processing_status, processing_generation, "
                + "safe_metadata, view_state, upload_idempotency_key, created_at, updated_at ) VALUES ("));
        assertTrue(sql.endsWith("ON CONFLICT (owner_user_id, upload_idempotency_key) "
                + "WHERE upload_idempotency_key IS NOT NULL DO NOTHING"));
        assertEquals(List.of("id", "ownerUserId", "sourceType", "bucketName", "objectKey",
                        "originalFilename", "contentType", "sizeBytes", "sha256", "widthPx", "heightPx",
                        "storageStatus", "processingStatus", "processingGeneration", "safeMetadata",
                        "viewState", "uploadIdempotencyKey", "createdAt", "updatedAt"),
                boundSql.getParameterMappings().stream().map(ParameterMapping::getProperty).toList());
        assertInstanceOf(UuidTypeHandler.class, boundSql.getParameterMappings().get(0).getTypeHandler());
        assertInstanceOf(PgObjectTypeHandler.class, boundSql.getParameterMappings().get(14).getTypeHandler());
    }

    @Test
    void bindsUuidAndJsonbAsNativePostgresValuesAndNullableViewStateAsOther() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(PictureAssetMapper.class);
        MappedStatement statement = configuration.getMappedStatement(INSERT_STATEMENT);
        PictureAsset picture = uploadPicture();
        BoundSql boundSql = statement.getBoundSql(picture);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);

        new DefaultParameterHandler(statement, picture, boundSql).setParameters(preparedStatement);

        verify(preparedStatement).setObject(1, picture.getId(), Types.OTHER);
        verify(preparedStatement).setObject(2, picture.getOwnerUserId(), Types.OTHER);
        verify(preparedStatement).setString(3, "USER_UPLOAD");
        verify(preparedStatement).setLong(8, 123L);
        verify(preparedStatement).setObject(15, picture.getSafeMetadata(), Types.OTHER);
        verify(preparedStatement).setNull(16, Types.OTHER);
        verify(preparedStatement).setString(17, picture.getUploadIdempotencyKey());
        verify(preparedStatement).setObject(18, picture.getCreatedAt());
        verify(preparedStatement).setObject(19, picture.getUpdatedAt());
    }

    @Test
    void packageScanningRegistersHandlersForGeneratedMybatisPlusStatementsAndResults() {
        Configuration configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register("org.javaup.image.persistence.typehandler");

        assertInstanceOf(UuidTypeHandler.class, configuration.getTypeHandlerRegistry().getTypeHandler(UUID.class));
        assertInstanceOf(UuidTypeHandler.class,
                configuration.getTypeHandlerRegistry().getTypeHandler(UUID.class, JdbcType.OTHER));
        assertInstanceOf(PgObjectTypeHandler.class,
                configuration.getTypeHandlerRegistry().getTypeHandler(PGobject.class));
        assertInstanceOf(PgObjectTypeHandler.class,
                configuration.getTypeHandlerRegistry().getTypeHandler(PGobject.class, JdbcType.OTHER));
    }

    private PictureAsset uploadPicture() throws Exception {
        PictureAsset picture = new PictureAsset();
        picture.setId(UUID.randomUUID());
        picture.setOwnerUserId(UUID.randomUUID());
        picture.setSourceType("USER_UPLOAD");
        picture.setBucketName("pictures");
        picture.setObjectKey("users/owner/pictures/original.jpg");
        picture.setOriginalFilename("original.jpg");
        picture.setContentType("image/jpeg");
        picture.setSizeBytes(123L);
        picture.setStorageStatus("INITIATED");
        picture.setProcessingStatus("PENDING");
        picture.setProcessingGeneration(1);
        PGobject safeMetadata = new PGobject();
        safeMetadata.setType("jsonb");
        safeMetadata.setValue("{}");
        picture.setSafeMetadata(safeMetadata);
        picture.setUploadIdempotencyKey(UUID.randomUUID().toString());
        picture.setCreatedAt(OffsetDateTime.parse("2026-10-20T12:00:00Z"));
        picture.setUpdatedAt(picture.getCreatedAt());
        return picture;
    }
}
