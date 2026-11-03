package org.javaup.history.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.history.entity.AssessmentVisual;
import org.javaup.history.mapper.AssessmentVisualMapper;
import org.javaup.history.service.AssessmentVisualService;
import org.springframework.stereotype.Service;

@Service
public class AssessmentVisualServiceImpl extends ServiceImpl<AssessmentVisualMapper, AssessmentVisual>
        implements AssessmentVisualService {
}
