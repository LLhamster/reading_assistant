package com.example.httpreading.domain.user;

import java.time.LocalDateTime;
import jakarta.persistence.*;

@Entity
@Table(name = "reading_progress")
public class Reading {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "book_id")
    private Long bookId;

    @Column(name = "chapter_index")
    private Integer chapterIndex;

    @Column(name = "`offset`")
    private Integer offset;

    @Column(name = "anchor_text", length = 500)
    private String anchorText;

    @Column(name = "prefix_text", length = 300)
    private String prefixText;

    @Column(name = "suffix_text", length = 300)
    private String suffixText;

    @Column(name = "anchor_offset")
    private Integer anchorOffset;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public Integer getChapterIndex() {
        return chapterIndex;
    }

    public void setChapterIndex(Integer chapterIndex) {
        this.chapterIndex = chapterIndex;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    public String getAnchorText() {
        return anchorText;
    }

    public void setAnchorText(String anchorText) {
        this.anchorText = anchorText;
    }

    public String getPrefixText() {
        return prefixText;
    }

    public void setPrefixText(String prefixText) {
        this.prefixText = prefixText;
    }

    public String getSuffixText() {
        return suffixText;
    }

    public void setSuffixText(String suffixText) {
        this.suffixText = suffixText;
    }

    public Integer getAnchorOffset() {
        return anchorOffset;
    }

    public void setAnchorOffset(Integer anchorOffset) {
        this.anchorOffset = anchorOffset;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
