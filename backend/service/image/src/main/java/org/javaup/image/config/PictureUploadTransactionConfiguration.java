package org.javaup.image.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class PictureUploadTransactionConfiguration {

    /** Use once for reservation and once for completion; put MinIO IO between them. */
    @Bean
    public TransactionTemplate pictureUploadTransaction(
            PlatformTransactionManager transactionManager
    ) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
}
