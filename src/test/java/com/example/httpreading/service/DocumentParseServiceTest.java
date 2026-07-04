package com.example.httpreading.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentParseServiceTest {

    @TempDir
    Path tempDir;

    private final DocumentParseService service = new DocumentParseService();

    @Test
    void epubKeepsSafeInlineImagesAndPlainText() throws IOException {
        Path epub = tempDir.resolve("illustrated.epub");
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        writeEpub(epub, Map.of(
            "META-INF/container.xml", """
                <container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>
                """,
            "OPS/book.opf", """
                <package><manifest>
                  <item id="chapter" href="text/chapter.xhtml" media-type="application/xhtml+xml"/>
                  <item id="image" href="images/picture.png" media-type="image/png"/>
                </manifest><spine><itemref idref="chapter"/></spine></package>
                """,
            "OPS/text/chapter.xhtml", """
                <html><head><title>插图章节</title></head><body>
                  <h1>插图章节</h1>
                  <p>图片之前</p>
                  <img src="../images/picture.png" alt="测试插图" onerror="alert(1)" style="width:1px"/>
                  <script>alert('xss')</script>
                  <p>图片之后</p>
                  <img src="https://example.com/external.png" alt="外部图片"/>
                </body></html>
                """,
            "OPS/images/picture.png", png));

        DocumentParseService.ParsedBook parsed = service.parse(epub, null, null, 42L);

        assertThat(parsed.chapters()).hasSize(1);
        DocumentParseService.ParsedChapter chapter = parsed.chapters().get(0);
        assertThat(chapter.content()).contains("图片之前", "图片之后");
        assertThat(chapter.contentHtml())
            .contains("图片之前")
            .contains("<img src=\"/api/books/42/assets?path=OPS%2Fimages%2Fpicture.png\"")
            .contains("图片之后")
            .contains("外部图片")
            .doesNotContain("onerror", "style=", "<script", "https://example.com");
        assertThat(parsed.assets()).containsOnlyKeys("OPS/images/picture.png");
        assertThat(parsed.assets().get("OPS/images/picture.png")).isEqualTo(png);
    }

    @Test
    void epubRejectsTraversalAndFakeImages() throws IOException {
        Path epub = tempDir.resolve("unsafe.epub");
        writeEpub(epub, Map.of(
            "META-INF/container.xml",
            "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>",
            "book.opf", """
                <package><manifest>
                  <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="chapter"/></spine></package>
                """,
            "chapter.xhtml", """
                <html><body><p>正文</p>
                  <img src="../../secret.png" alt="越界图片"/>
                  <img src="fake.png" alt="伪造图片"/>
                </body></html>
                """,
            "fake.png", "not-an-image".getBytes(StandardCharsets.UTF_8)));

        DocumentParseService.ParsedBook parsed = service.parse(epub, null, null, 7L);

        assertThat(parsed.assets()).isEmpty();
        assertThat(parsed.chapters().get(0).contentHtml())
            .contains("越界图片", "伪造图片")
            .doesNotContain("<img");
    }

    private void writeEpub(Path target, Map<String, ?> entries) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(target))) {
            for (Map.Entry<String, ?> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                byte[] value = entry.getValue() instanceof byte[] bytes
                    ? bytes
                    : entry.getValue().toString().getBytes(StandardCharsets.UTF_8);
                output.write(value);
                output.closeEntry();
            }
        }
    }
}
