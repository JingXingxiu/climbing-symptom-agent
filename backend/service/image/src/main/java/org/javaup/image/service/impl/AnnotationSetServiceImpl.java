package org.javaup.image.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.image.entity.AnnotationSet;
import org.javaup.image.mapper.AnnotationSetMapper;
import org.javaup.image.service.AnnotationSetService;
import org.springframework.stereotype.Service;

@Service
public class AnnotationSetServiceImpl extends ServiceImpl<AnnotationSetMapper, AnnotationSet>
        implements AnnotationSetService {
}
