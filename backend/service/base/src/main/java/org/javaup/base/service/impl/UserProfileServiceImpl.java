package org.javaup.base.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.base.entity.UserProfile;
import org.javaup.base.mapper.UserProfileMapper;
import org.javaup.base.service.UserProfileService;
import org.springframework.stereotype.Service;

@Service
public class UserProfileServiceImpl extends ServiceImpl<UserProfileMapper, UserProfile>
        implements UserProfileService {
}
