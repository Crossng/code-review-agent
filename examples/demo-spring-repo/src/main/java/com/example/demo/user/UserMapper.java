package com.example.demo.user;

import java.util.List;

import org.springframework.stereotype.Repository;

@Repository
public class UserMapper {

    public List<UserEntity> findAll() {
        return List.of(new UserEntity(1L, "Ada Lovelace"));
    }

    public UserEntity findById(Long id) {
        return new UserEntity(id, "Grace Hopper");
    }
}

