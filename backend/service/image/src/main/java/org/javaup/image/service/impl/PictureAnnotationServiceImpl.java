package org.javaup.image.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.image.entity.PictureAnnotation;
import org.javaup.image.mapper.PictureAnnotationMapper;
import org.javaup.image.service.PictureAnnotationService;
import org.springframework.stereotype.Service;

@Service
public class PictureAnnotationServiceImpl extends ServiceImpl<PictureAnnotationMapper, PictureAnnotation>
        implements PictureAnnotationService {
}
