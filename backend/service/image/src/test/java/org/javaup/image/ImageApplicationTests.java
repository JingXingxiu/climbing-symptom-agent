package org.javaup.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import io.minio.MinioClient;
import javax.sql.DataSource;
import org.javaup.image.mapper.AnnotationSetMapper;
import org.javaup.image.mapper.PictureAnnotationMapper;
import org.javaup.image.mapper.PictureAssetMapper;
import org.javaup.image.mapper.PictureVariantMapper;
import org.javaup.image.service.AgentPictureService;
import org.javaup.image.service.PictureAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/image-offline-test.properties",
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "image.storage.minio.endpoint=http://127.0.0.1:19000",
        "image.storage.minio.access-key=offline-test-access",
        "image.storage.minio.secret-key=offline-test-secret",
        "image.storage.minio.region=us-east-1",
        "image.storage.minio.bucket=offline-test-pictures",
        "image.storage.minio.presigned-url-expiry-seconds=300"
})
class ImageApplicationTests {

    @MockitoBean
    private PictureAssetMapper pictureAssetMapper;

    @MockitoBean
    private PictureVariantMapper pictureVariantMapper;

    @MockitoBean
    private AnnotationSetMapper annotationSetMapper;

    @MockitoBean
    private PictureAnnotationMapper pictureAnnotationMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private MinioClient minioClient;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsOfflineWithoutDatabaseNacosOrObjectStorage() {
        assertThat(context.getBean(PictureAssetService.class)).isNotNull();
        assertThat(context.getBean(AgentPictureService.class)).isNotNull();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        verifyNoInteractions(minioClient);
    }

}
