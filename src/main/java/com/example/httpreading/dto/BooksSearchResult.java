package com.example.httpreading.dto;

import java.util.List;
import java.util.Map;

import com.example.httpreading.domain.document.BooksDoc;

public class BooksSearchResult {

    private BooksDoc book;
    private Map<String, List<String>> highlights;

    public BooksSearchResult(BooksDoc book, Map<String, List<String>> highlights) {
        this.book = book;
        this.highlights = highlights;
    }

    /** 标题高亮片段（ES 返回的第一个片段，可能带 <em> 标签） */
    public String getTitleHighlight() {
        return highlightOf("title");
    }

    /** 作者高亮片段 */
    public String getAuthorHighlight() {
        return highlightOf("author");
    }

    private String highlightOf(String field) {
        if (highlights == null) return null;
        List<String> fragments = highlights.get(field);
        if (fragments == null || fragments.isEmpty()) return null;
        return fragments.get(0);
    }

    public BooksDoc getBook() {
        return book;
    }

    public Long getId() {
        return book != null ? book.getId() : null;
    }

    public String getTitle() {
        return book != null ? book.getTitle() : null;
    }

    public String getAuthor() {
        return book != null ? book.getAuthor() : null;
    }

    public void setBook(BooksDoc book) {
        this.book = book;
    }

    public Map<String, List<String>> getHighlights() {
        return highlights;
    }

    public void setHighlights(Map<String, List<String>> highlights) {
        this.highlights = highlights;
    }
}
