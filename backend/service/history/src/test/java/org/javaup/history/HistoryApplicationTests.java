package org.javaup.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import org.javaup.history.client.ImageClient;
import org.javaup.history.mapper.AssessmentAnswerMapper;
import org.javaup.history.mapper.AssessmentMapper;
import org.javaup.history.mapper.AssessmentResultMapper;
import org.javaup.history.mapper.AssessmentResultRevisionMapper;
import org.javaup.history.mapper.AssessmentSymptomMapper;
import org.javaup.history.mapper.AssessmentVisualMapper;
import org.javaup.history.mapper.SymptomLocationMapper;
import org.javaup.history.service.AgentResultService;
import org.javaup.history.service.AssessmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "spring.config.location=optional:classpath:/history-offline-test.properties",
        "spring.config.import=",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.service-registry.auto-registration.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class HistoryApplicationTests {

    @MockitoBean
    private AssessmentMapper assessmentMapper;

    @MockitoBean
    private AssessmentAnswerMapper assessmentAnswerMapper;

    @MockitoBean
    private AssessmentResultMapper assessmentResultMapper;

    @MockitoBean
    private AssessmentResultRevisionMapper assessmentResultRevisionMapper;

    @MockitoBean
    private AssessmentSymptomMapper assessmentSymptomMapper;

    @MockitoBean
    private AssessmentVisualMapper assessmentVisualMapper;

    @MockitoBean
    private SymptomLocationMapper symptomLocationMapper;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ImageClient imageClient;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsOfflineWithoutDatabaseNacosOrImageService() {
        assertThat(context.getBean(AssessmentService.class)).isNotNull();
        assertThat(context.getBean(AgentResultService.class)).isNotNull();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        verifyNoInteractions(imageClient);
    }

}
