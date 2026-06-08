package com.example.httpreading.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.httpreading.domain.user.User;
import com.example.httpreading.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("findByUsername - 找到用户")
    void findByUsername_exists_returnsUser() {
        // given
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));

        // when
        Optional<User> result = userService.findByUsername("testuser");

        // then
        assertTrue(result.isPresent());
        assertEquals("testuser", result.get().getUsername());
    }

    @Test
    @DisplayName("findByUsername - 用户不存在")
    void findByUsername_notExists_returnsEmpty() {
        // given
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        // when
        Optional<User> result = userService.findByUsername("nonexistent");

        // then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("regisiter - 成功注册新用户")
    void register_newUser_success() {
        // given
        when(userRepository.findByUsername("newuser")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("hashedPassword");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        // when
        User result = userService.regisiter("newuser", "password123");

        // then
        assertNotNull(result);
        assertEquals("newuser", result.getUsername());
        assertEquals("hashedPassword", result.getPasswordHash());
        verify(passwordEncoder).encode("password123");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("regisiter - 用户名已存在抛出异常")
    void register_duplicateUsername_throwsException() {
        // given
        User existingUser = new User();
        existingUser.setUsername("existinguser");
        when(userRepository.findByUsername("existinguser")).thenReturn(Optional.of(existingUser));

        // when & then
        assertThrows(Exception.class, () -> userService.regisiter("existinguser", "password123"));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("authenticate - 密码正确返回用户")
    void authenticate_correctPassword_returnsUser() {
        // given
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashedPassword");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correctPassword", "hashedPassword")).thenReturn(true);

        // when
        Optional<User> result = userService.authenticate("testuser", "correctPassword");

        // then
        assertTrue(result.isPresent());
        assertEquals("testuser", result.get().getUsername());
    }

    @Test
    @DisplayName("authenticate - 密码错误返回empty")
    void authenticate_wrongPassword_returnsEmpty() {
        // given
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashedPassword");
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "hashedPassword")).thenReturn(false);

        // when
        Optional<User> result = userService.authenticate("testuser", "wrongPassword");

        // then
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("authenticate - 用户不存在返回empty")
    void authenticate_userNotExists_returnsEmpty() {
        // given
        when(userRepository.findByUsername("nonexistent")).thenReturn(Optional.empty());

        // when
        Optional<User> result = userService.authenticate("nonexistent", "anyPassword");

        // then
        assertFalse(result.isPresent());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }
}
