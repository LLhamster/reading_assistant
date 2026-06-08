package com.example.httpreading.repository;
import java.util.Optional;
import com.example.httpreading.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;


public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}