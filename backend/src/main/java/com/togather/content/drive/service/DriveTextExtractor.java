package com.togather.content.drive.service;

import com.togather.common.exception.BusinessException;
import com.togather.content.drive.client.DriveApiClient;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;
import org.springframework.stereotype.Component;

/**
 * 통합 검색이 파일 이름뿐 아니라 내용으로도 찾을 수 있게 Drive 파일의 본문 글을 뽑는다.
 *
 * <ul>
 *   <li>Google 문서·프레젠테이션: 텍스트로 내보내기, Google 스프레드시트: CSV로 내보내기</li>
 *   <li>PDF: PDFBox</li>
 *   <li>Word·PowerPoint·Excel(docx·pptx·xlsx, xls): Apache POI</li>
 *   <li>텍스트 파일(txt, md, csv, json 등): 그대로</li>
 * </ul>
 * 그 밖의 형식(이미지·한글 hwp 등), 너무 큰 파일, 읽기 실패는 빈 글로 둔다. (파일 이름으로는 계속 검색된다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DriveTextExtractor {

    /** 이보다 큰 파일은 본문을 읽지 않는다. */
    static final long MAX_BYTES = 20L * 1024 * 1024;
    /** 저장할 본문 최대 길이 */
    static final int MAX_CHARS = 50_000;

    private static final Set<String> OFFICE_TYPES = Set.of(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel");

    private final DriveApiClient driveApiClient;

    /** 본문을 뽑을 수 있는 형식인지 */
    public static boolean isSupported(String mimeType) {
        return mimeType != null && (exportType(mimeType) != null || "application/pdf".equals(mimeType)
            || OFFICE_TYPES.contains(mimeType) || isPlainText(mimeType));
    }

    /** @return 본문 글. 뽑을 수 없으면 빈 글 (예외를 던지지 않는다) */
    public String extract(String token, String fileId, String mimeType) {
        if (!isSupported(mimeType)) {
            return "";
        }
        try {
            byte[] bytes = driveApiClient.downloadForText(token, fileId, exportType(mimeType), MAX_BYTES);
            if (bytes == null) {
                log.info("Drive 파일이 커서 본문을 읽지 않음: mimeType={}", mimeType);
                return "";
            }
            return clean(toText(bytes, mimeType));
        } catch (BusinessException e) {
            log.info("Drive 본문 추출 실패: mimeType={}, error={}", mimeType, e.getErrorCode());
            return "";
        } catch (Exception | LinkageError e) {
            log.info("Drive 본문 추출 실패: mimeType={}, error={}", mimeType, e.getClass().getSimpleName());
            return "";
        }
    }

    static String toText(byte[] bytes, String mimeType) throws Exception {
        if (exportType(mimeType) != null || isPlainText(mimeType)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if ("application/pdf".equals(mimeType)) {
            try (PDDocument pdf = Loader.loadPDF(bytes)) {
                return new PDFTextStripper().getText(pdf);
            }
        }
        try (POITextExtractor extractor = ExtractorFactory.createExtractor(new ByteArrayInputStream(bytes))) {
            return extractor.getText();
        }
    }

    /** 줄마다 앞뒤 공백을 지우고 빈 줄을 하나로 줄인 뒤 최대 길이로 자른다. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean lastBlank = true;
        for (String raw : text.replace('\u0000', ' ').lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() && lastBlank) {
                continue;
            }
            sb.append(line).append('\n');
            lastBlank = line.isEmpty();
        }
        String cleaned = sb.toString().strip();
        return cleaned.length() > MAX_CHARS ? cleaned.substring(0, MAX_CHARS) : cleaned;
    }

    private static String exportType(String mimeType) {
        return switch (mimeType) {
            case "application/vnd.google-apps.document", "application/vnd.google-apps.presentation" -> "text/plain";
            case "application/vnd.google-apps.spreadsheet" -> "text/csv";
            default -> null;
        };
    }

    private static boolean isPlainText(String mimeType) {
        return mimeType.startsWith("text/") || "application/json".equals(mimeType);
    }
}
