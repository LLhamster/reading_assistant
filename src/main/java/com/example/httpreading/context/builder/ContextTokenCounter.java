package com.example.httpreading.context.builder;

final class ContextTokenCounter {
    private ContextTokenCounter() {
    }

    static int countTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        long cjk = text.codePoints()
            .filter(ch -> (ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF))
            .count();
        long nonCjkVisible = text.codePoints()
            .filter(ch -> !Character.isWhitespace(ch))
            .filter(ch -> !((ch >= 0x4E00 && ch <= 0x9FFF) || (ch >= 0x3400 && ch <= 0x4DBF)))
            .count();
        long estimatedNonCjkTokens = (nonCjkVisible + 3L) / 4L;
        return Math.max(1, (int) (cjk + estimatedNonCjkTokens));
    }
}
