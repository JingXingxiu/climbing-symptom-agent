package org.javaup.history.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.history.entity.SymptomLocation;
import org.javaup.history.mapper.SymptomLocationMapper;
import org.javaup.history.service.SymptomLocationService;
import org.springframework.stereotype.Service;

@Service
public class SymptomLocationServiceImpl extends ServiceImpl<SymptomLocationMapper, SymptomLocation>
        implements SymptomLocationService {
}
