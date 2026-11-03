package org.javaup.history.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.history.entity.AssessmentAnswer;
import org.javaup.history.mapper.AssessmentAnswerMapper;
import org.javaup.history.service.AssessmentAnswerService;
import org.springframework.stereotype.Service;

@Service
public class AssessmentAnswerServiceImpl extends ServiceImpl<AssessmentAnswerMapper, AssessmentAnswer>
        implements AssessmentAnswerService {
}
