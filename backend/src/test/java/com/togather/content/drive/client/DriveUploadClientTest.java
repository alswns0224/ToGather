package com.togather.content.drive.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.togather.common.exception.BusinessException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DriveUploadClientTest {
    private MockRestServiceServer server;
    private DriveApiClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DriveApiClient(builder.build(), Duration.ofSeconds(30));
    }

    @Test
    void 메타데이터는_64비트_크기와_버전과_수정권한을_읽는다() {
        metadata("\"size\":\"5000000000\",");
        var file = client.getFileMetadata("token", "file");
        assertThat(file.fileSize()).isEqualTo(5000000000L);
        assertThat(file.version()).isEqualTo("42");
        assertThat(file.canOverwrite()).isTrue();
        server.verify();
    }

    @Test
    void 크기가_없는_파일은_0이_아닌_null이다() {
        metadata("");
        assertThat(client.getFileMetadata("token", "file").fileSize()).isNull();
        server.verify();
    }

    @Test
    void 빈_파일의_크기는_0이다() {
        metadata("\"size\":\"0\",");
        assertThat(client.getFileMetadata("token", "file").fileSize()).isZero();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "9223372036854775808", "invalid"})
    void 잘못된_크기는_누락이나_0으로_저장하지_않는다(String size) {
        metadata("\"size\":\"" + size + "\",");
        assertThatThrownBy(() -> client.getFileMetadata("token", "file")).isInstanceOf(BusinessException.class);
        server.verify();
    }

    @Test
    void 동명_검색은_쿼리를_이스케이프하고_페이지를_이어받으며_폴더와_이름을_재확인한다() {
        server.expect(request -> {
            assertThat(request.getURI().getQuery()).contains("name = 'O\\'Reilly.pdf'", "'folder' in parents", "trashed = false");
        }).andRespond(withSuccess("""
            {"files":[
              {"id":"a","name":"O'Reilly.pdf","mimeType":"application/pdf","parents":["folder"],"version":"1","capabilities":{"canModifyContent":true}},
              {"id":"outside","name":"O'Reilly.pdf","mimeType":"application/pdf","parents":["outside"]},
              {"id":"different","name":"different.pdf","mimeType":"application/pdf","parents":["folder"]}],"nextPageToken":"next"}
            """, MediaType.APPLICATION_JSON));
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("pageToken=next"))
            .andRespond(withSuccess("""
            {"files":[{"id":"native","name":"O'Reilly.pdf","mimeType":"application/vnd.google-apps.document","parents":["folder"],"version":"2","capabilities":{"canModifyContent":true}}]}
            """, MediaType.APPLICATION_JSON));
        var files = client.findFilesByName("token", "folder", "O'Reilly.pdf");
        assertThat(files).extracting(DriveApiClient.DriveFile::id).containsExactly("a", "native");
        assertThat(files.get(0).canOverwrite()).isTrue();
        assertThat(files.get(1).canOverwrite()).isFalse();
        server.verify();
    }

    @Test
    void 새_파일은_POST로_부모_폴더를_지정한다() {
        session(HttpMethod.POST, "/upload/drive/v3/files", "{\"name\":\"result.pdf\",\"parents\":[\"folder\"],\"mimeType\":\"application/pdf\"}");
        assertThat(start(null)).isEqualTo(URI.create("https://www.googleapis.com/upload/drive/v3/files?upload_id=test"));
        server.verify();
    }

    @Test
    void 덮어쓰기는_기존_ID에_PATCH하며_부모_폴더를_바꾸지_않는다() {
        session(HttpMethod.PATCH, "/upload/drive/v3/files/existing", "{\"name\":\"result.pdf\",\"mimeType\":\"application/pdf\"}");
        assertThat(start("existing")).isNotNull();
        server.verify();
    }

    @Test
    void 업로드_세션이_Google_외부를_가리키면_거절한다() {
        server.expect(method(HttpMethod.PATCH)).andRespond(withStatus(HttpStatus.OK).location(URI.create("https://untrusted.example/upload")));
        assertThatThrownBy(() -> start("existing")).isInstanceOf(BusinessException.class);
        server.verify();
    }

    private URI start(String id) {
        return ReflectionTestUtils.invokeMethod(client, "startUpload", "token", "folder", "result.pdf", "application/pdf", 3L, id);
    }
    private void session(HttpMethod method, String path, String json) {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo(path);
            assertThat(request.getURI().getQuery()).contains("uploadType=resumable", "supportsAllDrives=true");
        }).andExpect(method(method)).andExpect(header("Authorization", "Bearer token"))
            .andExpect(header("X-Upload-Content-Length", "3"))
            .andExpect(content().json(json, JsonCompareMode.STRICT))
            .andRespond(withStatus(HttpStatus.OK).location(URI.create("https://www.googleapis.com/upload/drive/v3/files?upload_id=test")));
    }
    private void metadata(String size) {
        server.expect(request -> assertThat(request.getURI().getQuery()).contains("size,version,capabilities(canModifyContent)"))
            .andRespond(withSuccess("{" + size + "\"id\":\"file\",\"name\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"parents\":[\"folder\"],\"version\":\"42\",\"capabilities\":{\"canModifyContent\":true}}", MediaType.APPLICATION_JSON));
    }
}
