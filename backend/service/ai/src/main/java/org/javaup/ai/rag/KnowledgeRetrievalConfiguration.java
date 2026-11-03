package org.javaup.ai.rag;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(KnowledgeRetrievalProperties.class)
@MapperScan(basePackageClasses = KnowledgeRetrievalMapper.class, annotationClass = Mapper.class)
public class KnowledgeRetrievalConfiguration {}
