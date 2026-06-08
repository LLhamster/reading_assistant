package com.example.httpreading.mq;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.example.httpreading.config.RabbitMQConfig;
import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.dto.BookIndexMessage;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.service.SearchService;

@Component
public class BookIndexConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookIndexConsumer.class);

    private final BooksRepository booksRepository;
    private final SearchService searchService;

    public BookIndexConsumer(BooksRepository booksRepository, SearchService searchService) {
        this.booksRepository = booksRepository;
        this.searchService = searchService;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void handleBookIndexMessage(BookIndexMessage message) {
        log.info("收到书籍索引消息 - bookId:{}, action:{}", message.getBookId(), message.getAction());

        if ("delete".equals(message.getAction())) {
            searchService.deleteBook(message.getBookId());
            log.info("ES 书籍删除完成 - bookId:{}", message.getBookId());
            return;
        }

        // "index" 场景：根据 bookId 查出完整信息写入 ES
        // 添加重试机制，解决事务提交延迟问题
        Books book = retryFindBook(message.getBookId(), 3, 100);
        if (book == null) {
            log.error("重试 3 次后书籍仍不存在，放弃索引 - bookId:{}", message.getBookId());
            return;
        }

        // 章节标题和内容也查出来
        List<String> chapterTitles = searchService.getChapterTitles(message.getBookId());

        searchService.indexBook(book, chapterTitles);
        log.info("ES 书籍索引完成 - bookId:{}, title:{}", book.getId(), book.getTitle());
    }

    /**
     * 重试查找书籍（解决事务提交延迟问题）
     * @param bookId 书籍 ID
     * @param maxRetries 最大重试次数
     * @param delayMs 每次重试的延迟毫秒数
     * @return 书籍对象，如果重试失败返回 null
     */
    private Books retryFindBook(Long bookId, int maxRetries, long delayMs) {
        for (int i = 0; i < maxRetries; i++) {
            Optional<Books> optBook = booksRepository.findById(bookId);
            if (optBook.isPresent()) {
                if (i > 0) {
                    log.info("第 {} 次重试成功找到书籍 - bookId:{}", i, bookId);
                }
                return optBook.get();
            }
            
            if (i < maxRetries - 1) {
                log.warn("第 {} 次查询书籍不存在，将在 {}ms 后重试 - bookId:{}", i + 1, delayMs, bookId);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("重试线程被中断 - bookId:{}", bookId);
                    return null;
                }
            }
        }
        return null;
    }
}
