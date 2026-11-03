package org.javaup.history.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.history.entity.AssessmentSymptom;
import org.javaup.history.mapper.AssessmentSymptomMapper;
import org.javaup.history.service.AssessmentSymptomService;
import org.springframework.stereotype.Service;

@Service
public class AssessmentSymptomServiceImpl extends ServiceImpl<AssessmentSymptomMapper, AssessmentSymptom>
        implements AssessmentSymptomService {
}
