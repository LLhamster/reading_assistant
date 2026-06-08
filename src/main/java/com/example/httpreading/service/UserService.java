package com.example.httpreading.service;

import com.example.httpreading.api.ErrorCode;
import com.example.httpreading.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.example.httpreading.domain.user.User;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder){
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<User> findByUsername(String username){
        return userRepository.findByUsername(username);
    }

    public User regisiter(String username, String password){
        userRepository.findByUsername(username).ifPresent(u -> {
            ErrorCode.DUPLICATE_USERNAME.throwException();
        });
        User user = new User();
        user.setCreateTime(LocalDateTime.now());
        // BCrypt hash
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setUsername(username);
        return userRepository.save(user);
    }

    /**
     * 返回用户（若账号存在且密码匹配），否则 Optional.empty()
     */
    public Optional<User> authenticate(String username, String rawPassword){
        return userRepository.findByUsername(username)
                .filter(u -> passwordEncoder.matches(rawPassword, u.getPasswordHash()));
    }

    public Optional<User> login(String username, String password){
        // 保留老接口以减少改动，但请优先使用 authenticate
        return userRepository.findByUsername(username);
    }

}
