package com.example.httpreading.config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.domain.entity.Chapters;
import com.example.httpreading.domain.user.Bookshelf;
import com.example.httpreading.domain.user.Reading;
import com.example.httpreading.domain.user.User;
import com.example.httpreading.mq.BookIndexProducer;
import com.example.httpreading.repository.BookshelfRepository;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.repository.ChaptersRepository;
import com.example.httpreading.repository.ReadingRepository;
import com.example.httpreading.repository.UserRepository;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final BooksRepository booksRepository;
    private final ChaptersRepository chaptersRepository;
    private final BookshelfRepository bookshelfRepository;
    private final ReadingRepository readingRepository;
    private final PasswordEncoder passwordEncoder;
    private final BookIndexProducer bookIndexProducer;

    public DataInitializer(UserRepository userRepository,
                          BooksRepository booksRepository,
                          ChaptersRepository chaptersRepository,
                          BookshelfRepository bookshelfRepository,
                          ReadingRepository readingRepository,
                          PasswordEncoder passwordEncoder,
                          BookIndexProducer bookIndexProducer) {
        this.userRepository = userRepository;
        this.booksRepository = booksRepository;
        this.chaptersRepository = chaptersRepository;
        this.bookshelfRepository = bookshelfRepository;
        this.readingRepository = readingRepository;
        this.passwordEncoder = passwordEncoder;
        this.bookIndexProducer = bookIndexProducer;
    }

    private final Random random = new Random();

    // ==================== 书籍数据 ====================
    private static final String[][] BOOKS_DATA = {
        // Java系列
        {"Java核心技术卷I", "Cay S. Horstmann", "连载中"},
        {"Java核心技术卷II", "Cay S. Horstmann", "已完结"},
        // Spring系列
        {"Spring Boot实战", "丁雪丰", "已完结"},
        {"Spring Cloud微服务实战", "丁雪丰", "连载中"},
        // Python系列
        {"Python编程：从入门到实践", "Eric Matthes", "连载中"},
        {"Python核心编程", "Wesley Chun", "已完结"},
        // 算法/计算机基础
        {"算法导论", "Thomas H. Cormen", "已完结"},
        {"数据结构与算法分析", "Mark Allen Weiss", "连载中"},
        {"深入理解计算机系统", "Randal E. Bryant", "连载中"},
        {"计算机网络：自顶向下方法", "Kurose & Ross", "连载中"},
        // 数据库
        {"Redis设计与实战", "黄健宏", "已完结"},
        {"Redis深度历险", "钱文品", "连载中"},
        {"MySQL必知必会", "Ben Forta", "已完结"},
        {"高性能MySQL", "Baron Schwartz", "连载中"},
        // 架构/运维
        {"Docker容器实战", "James Turnbull", "已完结"},
        {"Kubernetes权威指南", "龚正", "连载中"},
        {"微服务设计", "Sam Newman", "已完结"},
        {"设计模式", "Gang of Four", "已完结"},
        // 编程实践
        {"流畅的Python", "Luciano Ramalho", "连载中"},
        {"代码整洁之道", "Robert C. Martin", "已完结"},
    };

    // ==================== 用户数据 ====================
    private static final String[][] USERS_DATA = {
        {"test", "123456", "测试用户"},
        {"admin", "admin123", "管理员"},
        {"john", "john888", "普通用户"},
    };

    @Override
    @Transactional
    public void run(String... args) {
        // 检查是否已有数据
        if (booksRepository.count() > 0) {
            log.info("数据库已有数据，跳过初始化。当前书籍数：{}", booksRepository.count());
            return;
        }

        log.info("========== 开始初始化模拟数据 ==========");

        // 1. 创建用户
        List<User> users = createUsers();
        log.info("已创建 {} 个用户", users.size());

        // 2. 创建书籍和章节
        List<Books> books = createBooks();
        log.info("已创建 {} 本书籍", books.size());

        // 3. 创建书架
        createBookshelves(users, books);
        log.info("已完成书架初始化");

        // 4. 创建阅读进度
        createReadings(users, books);
        log.info("已创建阅读进度");

        log.info("========== 模拟数据初始化完成 ==========");
    }

    private List<User> createUsers() {
        List<User> users = new ArrayList<>();
        for (String[] userData : USERS_DATA) {
            User user = new User();
            user.setUsername(userData[0]);
            user.setPasswordHash(passwordEncoder.encode(userData[1]));
            user.setCreateTime(LocalDateTime.now().minusDays(random.nextInt(30)));
            users.add(userRepository.save(user));
        }
        return users;
    }

    private List<Books> createBooks() {
        List<Books> books = new ArrayList<>();
        for (int i = 0; i < BOOKS_DATA.length; i++) {
            String[] bookData = BOOKS_DATA[i];
            Books book = new Books();
            book.setTitle(bookData[0]);
            book.setAuthor(bookData[1]);
            book.setStatus(bookData[2]);
            book.setIntro(MockDataGenerator.generateBookIntro(bookData[0], bookData[1]));
            book.setCoverUrl("https://picsum.photos/seed/" + (i + 1) + "/300/400");
            book.setCreatedAt(LocalDateTime.now().minusDays(30 + random.nextInt(60)));
            book.setUpdatedAt(LocalDateTime.now().minusDays(random.nextInt(10)));
            Books savedBook = booksRepository.save(book);

            // 为每本书创建章节
            int chapterCount = 5 + random.nextInt(6); // 5-10章
            for (int j = 0; j < chapterCount; j++) {
                Chapters chapter = new Chapters();
                chapter.setBookId(savedBook.getId());
                chapter.setChapterIndex(j + 1);
                chapter.setTitle("第" + (j + 1) + "章 " + getChapterTitle(j));
                chapter.setContent(MockDataGenerator.generateChapterContent(i % 8, j));
                chapter.setCreatedAt(book.getCreatedAt().plusDays(j));
                chapter.setUpdatedAt(book.getUpdatedAt().plusDays(j));
                chaptersRepository.save(chapter);
            }

            // 通过 MQ 异步同步到 ES（发消息，不等待执行）
            bookIndexProducer.sendIndexMessage(savedBook.getId());
            log.info("书籍已创建，MQ 索引消息已发送: {}", savedBook.getTitle());

            books.add(savedBook);
        }
        return books;
    }

    private String getChapterTitle(int index) {
        String[] titles = {"概述", "环境搭建", "核心概念", "基础语法", "数据类型",
                          "控制流", "函数与模块", "面向对象", "异常处理", "文件操作",
                          "进阶用法", "设计模式", "性能优化", "最佳实践", "调试技巧"};
        return titles[index % titles.length];
    }

    private void createBookshelves(List<User> users, List<Books> books) {
        // test用户收藏 5 本
        User testUser = users.get(0);
        for (int i = 0; i < 5; i++) {
            Bookshelf bs = new Bookshelf();
            bs.setUserId(testUser.getId());
            bs.setBookId(books.get(i).getId());
            bs.setCreatedAt(LocalDateTime.now().minusDays(random.nextInt(20)));
            bookshelfRepository.save(bs);
        }

        // admin用户收藏 4 本
        User adminUser = users.get(1);
        for (int i = 5; i < 9; i++) {
            Bookshelf bs = new Bookshelf();
            bs.setUserId(adminUser.getId());
            bs.setBookId(books.get(i).getId());
            bs.setCreatedAt(LocalDateTime.now().minusDays(random.nextInt(20)));
            bookshelfRepository.save(bs);
        }

        // john用户收藏 3 本
        User johnUser = users.get(2);
        for (int i = 10; i < 13; i++) {
            Bookshelf bs = new Bookshelf();
            bs.setUserId(johnUser.getId());
            bs.setBookId(books.get(i).getId());
            bs.setCreatedAt(LocalDateTime.now().minusDays(random.nextInt(20)));
            bookshelfRepository.save(bs);
        }
    }

    private void createReadings(List<User> users, List<Books> books) {
        // test用户读了2本书，各读了几章
        User testUser = users.get(0);
        for (int bookIdx : new int[]{0, 3}) {
            Books book = books.get(bookIdx);
            Reading reading = new Reading();
            reading.setUserId(testUser.getId());
            reading.setBookId(book.getId());
            reading.setChapterIndex(2 + random.nextInt(4)); // 读到第2-5章
            reading.setOffset(random.nextInt(500)); // 阅读位置偏移
            reading.setUpdatedAt(LocalDateTime.now().minusDays(random.nextInt(5)));
            readingRepository.save(reading);
        }

        // admin用户读了3本书
        User adminUser = users.get(1);
        for (int bookIdx : new int[]{5, 8, 12}) {
            Books book = books.get(bookIdx);
            Reading reading = new Reading();
            reading.setUserId(adminUser.getId());
            reading.setBookId(book.getId());
            reading.setChapterIndex(1 + random.nextInt(6));
            reading.setOffset(random.nextInt(500));
            reading.setUpdatedAt(LocalDateTime.now().minusDays(random.nextInt(5)));
            readingRepository.save(reading);
        }

        // john用户读了2本书
        User johnUser = users.get(2);
        for (int bookIdx : new int[]{14, 17}) {
            Books book = books.get(bookIdx);
            Reading reading = new Reading();
            reading.setUserId(johnUser.getId());
            reading.setBookId(book.getId());
            reading.setChapterIndex(1 + random.nextInt(3));
            reading.setOffset(random.nextInt(500));
            reading.setUpdatedAt(LocalDateTime.now().minusDays(random.nextInt(5)));
            readingRepository.save(reading);
        }
    }
}
