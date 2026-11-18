package com.togather.content.drive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import com.togather.content.drive.client.DriveApiClient;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drive 파일 본문 추출: PDF·Word·Google 문서·텍스트, 지원하지 않는 형식과 실패 */
class DriveTextExtractorTest {

    private DriveApiClient api;
    private DriveTextExtractor extractor;

    @BeforeEach
    void setUp() {
        api = mock(DriveApiClient.class);
        extractor = new DriveTextExtractor(api);
    }

    @Test
    void PDF_본문을_읽는다() throws Exception {
        when(api.downloadForText(eq("t"), eq("f1"), isNull(), anyLong())).thenReturn(pdf("Similarity threshold 0.7"));

        assertThat(extractor.extract("t", "f1", "application/pdf")).contains("Similarity threshold 0.7");
    }

    @Test
    void Word_본문을_읽는다() throws Exception {
        when(api.downloadForText(eq("t"), eq("f2"), isNull(), anyLong())).thenReturn(docx("유사도 기준은 0.7로 한다"));

        assertThat(extractor.extract("t", "f2",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contains("유사도 기준은 0.7로 한다");
    }

    @Test
    void Google_문서는_텍스트로_내보내서_읽는다() {
        when(api.downloadForText(eq("t"), eq("f3"), eq("text/plain"), anyLong()))
                .thenReturn("설계 명세서\r\n\r\n\r\n유사도 계산".getBytes(StandardCharsets.UTF_8));

        // 빈 줄은 하나로 줄인다.
        assertThat(extractor.extract("t", "f3", "application/vnd.google-apps.document"))
                .isEqualTo("설계 명세서\n\n유사도 계산");
    }

    @Test
    void 지원하지_않는_형식은_받지_않고_실패해도_빈_글() {
        assertThat(extractor.extract("t", "img", "image/png")).isEmpty();
        verify(api, never()).downloadForText(eq("t"), eq("img"), isNull(), anyLong());

        when(api.downloadForText(eq("t"), eq("bad"), isNull(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.EXTERNAL_API_ERROR));
        assertThat(extractor.extract("t", "bad", "application/pdf")).isEmpty();

        when(api.downloadForText(eq("t"), eq("broken"), isNull(), anyLong())).thenReturn("not a pdf".getBytes());
        assertThat(extractor.extract("t", "broken", "application/pdf")).isEmpty();

        when(api.downloadForText(eq("t"), eq("big"), isNull(), anyLong())).thenReturn(null);
        assertThat(extractor.extract("t", "big", "application/pdf")).isEmpty();
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        }
    }
}
