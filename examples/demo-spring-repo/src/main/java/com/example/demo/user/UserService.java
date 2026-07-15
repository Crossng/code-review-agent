package com.example.demo.user;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public List<UserEntity> listUsers() {
        return userMapper.findAll();
    }

    public UserEntity getUser(Long id) {
        return userMapper.findById(id);
    }
}

