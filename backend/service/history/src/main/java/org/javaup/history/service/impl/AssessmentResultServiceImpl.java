package org.javaup.history.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.history.entity.AssessmentResult;
import org.javaup.history.mapper.AssessmentResultMapper;
import org.javaup.history.service.AssessmentResultService;
import org.springframework.stereotype.Service;

@Service
public class AssessmentResultServiceImpl extends ServiceImpl<AssessmentResultMapper, AssessmentResult>
        implements AssessmentResultService {
}
