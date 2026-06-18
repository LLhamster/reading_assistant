package com.example.httpreading.service.profile;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.example.httpreading.domain.entity.Books;
import com.example.httpreading.repository.BooksRepository;
import com.example.httpreading.service.ModelClient;
import org.springframework.stereotype.Service;

@Service
public class BookCategoryService {
    public static final String OTHER = "其他";
    private static final Set<String> ALLOWED = Set.of(
        "社会学", "技术", "历史", "文学", "哲学", "心理学", "英语", "职业成长", "经济学", OTHER);

    private final BooksRepository booksRepository;
    private final ModelClient modelClient;

    public BookCategoryService(BooksRepository booksRepository, ModelClient modelClient) {
        this.booksRepository = booksRepository;
        this.modelClient = modelClient;
    }

    public List<String> allowedCategories() {
        return List.of("社会学", "技术", "历史", "文学", "哲学", "心理学", "英语", "职业成长", "经济学", OTHER);
    }

    public boolean isAllowed(String value) {
        return value != null && ALLOWED.contains(value.trim());
    }

    public String normalize(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String trimmed = value.trim();
        return ALLOWED.contains(trimmed) ? trimmed : OTHER;
    }

    public String resolve(Long bookId, String explicitCategory) {
        if (isAllowed(explicitCategory)) {
            return explicitCategory.trim();
        }
        Optional<Books> book = bookId == null ? Optional.empty() : booksRepository.findById(bookId);
        if (book.isEmpty()) {
            return OTHER;
        }
        String inferredByRule = inferByKeywords(book.get());
        if (!OTHER.equals(inferredByRule)) {
            return inferredByRule;
        }
        return inferByModel(book.get());
    }

    private String inferByKeywords(Books book) {
        String text = ((book.getTitle() == null ? "" : book.getTitle()) + " "
            + (book.getIntro() == null ? "" : book.getIntro()) + " "
            + (book.getAuthor() == null ? "" : book.getAuthor())).toLowerCase(Locale.ROOT);
        if (matches(text, "java", "spring", "python", "redis", "mysql", "docker", "kubernetes", "算法", "计算机", "编程", "架构", "代码")) {
            return "技术";
        }
        if (matches(text, "社会", "乡土中国", "差序格局", "社会学")) {
            return "社会学";
        }
        if (matches(text, "历史", "中国史", "世界史", "民国", "近代")) {
            return "历史";
        }
        if (matches(text, "文学", "小说", "诗", "散文")) {
            return "文学";
        }
        if (matches(text, "哲学", "伦理", "存在", "形而上")) {
            return "哲学";
        }
        if (matches(text, "心理", "认知", "情绪")) {
            return "心理学";
        }
        if (matches(text, "english", "英语", "词汇", "语法")) {
            return "英语";
        }
        if (matches(text, "职业", "成长", "管理", "沟通", "领导力")) {
            return "职业成长";
        }
        if (matches(text, "经济", "金融", "货币", "市场")) {
            return "经济学";
        }
        return OTHER;
    }

    private boolean matches(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String inferByModel(Books book) {
        try {
            String raw = modelClient.chat("""
                请根据书名、作者、简介判断这本书最适合的固定分类。
                只能输出下面枚举之一，不要解释：
                社会学、技术、历史、文学、哲学、心理学、英语、职业成长、经济学、其他

                书名：%s
                作者：%s
                简介：%s
                """.formatted(book.getTitle(), book.getAuthor(), book.getIntro()));
            return normalize(raw == null ? "" : raw.replace("。", "").replace(".", "").trim());
        } catch (Exception ignored) {
            return OTHER;
        }
    }
}
