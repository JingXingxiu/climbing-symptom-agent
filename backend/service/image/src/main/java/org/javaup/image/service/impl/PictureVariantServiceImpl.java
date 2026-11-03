package org.javaup.image.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.image.entity.PictureVariant;
import org.javaup.image.mapper.PictureVariantMapper;
import org.javaup.image.service.PictureVariantService;
import org.springframework.stereotype.Service;

@Service
public class PictureVariantServiceImpl extends ServiceImpl<PictureVariantMapper, PictureVariant>
        implements PictureVariantService {
}
