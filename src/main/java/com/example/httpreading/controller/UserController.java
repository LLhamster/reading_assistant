package com.example.httpreading.controller;


import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.httpreading.domain.user.User;
import com.example.httpreading.dto.AuthRequest;
import com.example.httpreading.security.JwtService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.httpreading.api.*;
import com.example.httpreading.service.UserService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final JwtService jwtService;

    public UserController(UserService userService, JwtService jwtService){
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public CommonResponse<Map<String, Object>> regisiter(@Valid @RequestBody AuthRequest data){
        log.info("用户注册尝试 - username:{}", data.getUsername());
        User user = userService.regisiter(data.getUsername(), data.getPassword());
        log.info("用户注册成功 - userId:{}, username:{}", user.getId(), user.getUsername());
        return CommonResponse.success(Map.of("id", user.getId(), "username", user.getUsername()));
    }

    @PostMapping("/login")
    public CommonResponse<Map<String, Object>> login(@Valid @RequestBody AuthRequest data){
        log.info("用户登录尝试 - username:{}", data.getUsername());
        User user = userService.authenticate(data.getUsername(), data.getPassword())
                .orElseThrow(() -> {
                    log.warn("用户登录失败 - username:{}, reason:用户名或密码错误", data.getUsername());
                    return new IllegalArgumentException("用户名或密码错误");
                });

        String token = jwtService.generateToken(user.getId(), user.getUsername());
        log.info("用户登录成功 - userId:{}, username:{}", user.getId(), user.getUsername());
        return CommonResponse.success(Map.of(
                "token", token,
                "tokenType", "Bearer",
                "id", user.getId(),
                "username", user.getUsername()));
    }

}
