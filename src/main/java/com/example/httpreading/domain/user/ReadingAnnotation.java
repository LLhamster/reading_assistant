package com.example.httpreading.domain.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "reading_annotation", indexes = {
    @Index(name = "idx_annotation_user_book_chapter", columnList = "user_id,book_id,chapter_index")
})
public class ReadingAnnotation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "book_id", nullable = false)
    private Long bookId;

    @Column(name = "chapter_index", nullable = false)
    private Integer chapterIndex;

    @Column(name = "selected_text", nullable = false, columnDefinition = "text")
    private String selectedText;

    @Column(name = "start_offset", nullable = false)
    private Integer startOffset;

    @Column(name = "end_offset", nullable = false)
    private Integer endOffset;

    @Column(name = "prefix_text", columnDefinition = "text")
    private String prefixText;

    @Column(name = "suffix_text", columnDefinition = "text")
    private String suffixText;

    @Column(nullable = false, length = 16)
    private String color;

    @Column(name = "mark_style", nullable = false, length = 16,
        columnDefinition = "varchar(16) default 'highlight'")
    private String markStyle = "highlight";

    @Column(name = "note_content", columnDefinition = "text")
    private String noteContent;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getBookId() { return bookId; }
    public void setBookId(Long bookId) { this.bookId = bookId; }
    public Integer getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(Integer chapterIndex) { this.chapterIndex = chapterIndex; }
    public String getSelectedText() { return selectedText; }
    public void setSelectedText(String selectedText) { this.selectedText = selectedText; }
    public Integer getStartOffset() { return startOffset; }
    public void setStartOffset(Integer startOffset) { this.startOffset = startOffset; }
    public Integer getEndOffset() { return endOffset; }
    public void setEndOffset(Integer endOffset) { this.endOffset = endOffset; }
    public String getPrefixText() { return prefixText; }
    public void setPrefixText(String prefixText) { this.prefixText = prefixText; }
    public String getSuffixText() { return suffixText; }
    public void setSuffixText(String suffixText) { this.suffixText = suffixText; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getMarkStyle() { return markStyle; }
    public void setMarkStyle(String markStyle) { this.markStyle = markStyle; }
    public String getNoteContent() { return noteContent; }
    public void setNoteContent(String noteContent) { this.noteContent = noteContent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
