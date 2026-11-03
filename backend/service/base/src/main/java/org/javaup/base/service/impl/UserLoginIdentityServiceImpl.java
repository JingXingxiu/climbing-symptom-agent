package org.javaup.base.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.javaup.base.entity.UserLoginIdentity;
import org.javaup.base.mapper.UserLoginIdentityMapper;
import org.javaup.base.service.UserLoginIdentityService;
import org.springframework.stereotype.Service;

@Service
public class UserLoginIdentityServiceImpl extends ServiceImpl<UserLoginIdentityMapper, UserLoginIdentity>
        implements UserLoginIdentityService {
}
