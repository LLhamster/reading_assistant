package com.example.httpreading.dto;

/**
 * 书籍索引消息对象（用于 MQ 消息队列）
 */
public class BookIndexMessage {

    private Long bookId;
    private String action; // "index" 或 "delete"

    public BookIndexMessage() {}

    public BookIndexMessage(Long bookId, String action) {
        this.bookId = bookId;
        this.action = action;
    }

    public Long getBookId() {
        return bookId;
    }

    public void setBookId(Long bookId) {
        this.bookId = bookId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
