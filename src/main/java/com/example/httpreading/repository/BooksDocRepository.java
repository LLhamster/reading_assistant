package com.example.httpreading.repository;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import com.example.httpreading.domain.document.BooksDoc;

public interface BooksDocRepository extends ElasticsearchRepository<BooksDoc, Long> {
}