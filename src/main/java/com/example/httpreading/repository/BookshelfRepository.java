package com.example.httpreading.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.httpreading.domain.user.Bookshelf;

public interface BookshelfRepository extends JpaRepository<Bookshelf, Long>{

    public List<Bookshelf>findByUserId(Long userId);

    public Optional<Bookshelf>findByUserIdAndBookId(Long userId, Long bookId);

    void deleteByBookId(Long bookId);
}
