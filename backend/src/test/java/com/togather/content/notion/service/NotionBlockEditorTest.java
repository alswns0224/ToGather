package com.togather.content.notion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.togather.common.exception.BusinessException;
import com.togather.content.notion.client.NotionApiClient;
import com.togather.content.notion.dto.NotionBlockResponse;
import com.togather.content.notion.dto.NotionBlocksSaveRequest.Item;
import com.togather.content.notion.dto.NotionBlocksSaveRequest.TableRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 블록 단위 편집: 고친 부분만, 원래 자리에 반영하는지 */
class NotionBlockEditorTest {

    private static final String T = "token";
    private static final String PAGE = "page-1";

    private NotionApiClient api;
    private NotionBlockEditor editor;

    @BeforeEach
    void setUp() {
        api = mock(NotionApiClient.class);
        editor = new NotionBlockEditor(api);
    }

    // ===================== 읽기 =====================

    @Test
    void 글_이미지_표_고칠수없는_블록을_나눠서_읽는다() {
        when(api.getBlocks(T, PAGE)).thenReturn(List.of(
                block("b1", "heading_1", Map.of("rich_text", rich("안건")), false),
                block("b2", "numbered_list_item", Map.of("rich_text", rich("첫째")), false),
                block("b3", "numbered_list_item", Map.of("rich_text", rich("둘째")), false),
                block("img", "image", Map.of("type", "external", "external", Map.of("url", "https://x/a.png"),
                        "caption", rich("그림")), false),
                block("tbl", "table", Map.of("table_width", 2, "has_column_header", true), true),
                block("code", "code", Map.of("rich_text", rich("x = 1")), false),
                block("b4", "bulleted_list_item", Map.of("rich_text", rich("하위 있음")), true)));
        when(api.getBlocks(T, "tbl")).thenReturn(List.of(
                block("r1", "table_row", Map.of("cells", List.of(rich("이름"), rich("역할"))), false)));

        List<NotionBlockResponse> blocks = editor.read(T, PAGE);

        assertThat(blocks).extracting(NotionBlockResponse::kind)
                .containsExactly("TEXT", "TEXT", "TEXT", "IMAGE", "TABLE", "FIXED", "FIXED");
        assertThat(blocks.get(0).text()).isEqualTo("# 안건");
        assertThat(blocks.get(2).text()).isEqualTo("2. 둘째");
        assertThat(blocks.get(3).image().url()).isEqualTo("https://x/a.png");
        assertThat(blocks.get(3).image().caption()).isEqualTo("그림");
        assertThat(blocks.get(4).table().width()).isEqualTo(2);
        assertThat(blocks.get(4).table().rows().getFirst().cells()).containsExactly("이름", "역할");
        assertThat(blocks.get(5).label()).isEqualTo("코드 · x = 1");
        assertThat(blocks.get(6).label()).contains("하위 항목");
    }

    // ===================== 글 =====================

    @Test
    void 바뀐_글_묶음만_앞_블록_뒤에_새로_넣고_옛_블록을_지운다() {
        when(api.insertAfter(eq(T), eq(PAGE), eq("img"), anyList())).thenReturn(List.of("n1", "n2"));

        editor.save(T, PAGE, List.of(
                text(List.of("a1"), "그대로", false),
                image("img"),
                text(List.of("b1", "b2"), "새 문단\n- 목록", true)));

        InOrder order = inOrder(api);
        order.verify(api).insertAfter(eq(T), eq(PAGE), eq("img"), anyList());
        order.verify(api).deleteBlock(T, "b1");
        order.verify(api).deleteBlock(T, "b2");
        verify(api, never()).deleteBlock(T, "a1");
    }

    @Test
    void 맨_앞_글을_고치면_원래_첫_블록_뒤에_넣고_원래_글을_지운다() {
        when(api.insertAfter(eq(T), eq(PAGE), eq("a1"), anyList())).thenReturn(List.of("n1"));

        editor.save(T, PAGE, List.of(text(List.of("a1", "a2"), "고친 글", true), image("img")));

        verify(api).insertAfter(eq(T), eq(PAGE), eq("a1"), anyList());
        verify(api).deleteBlock(T, "a1");
        verify(api).deleteBlock(T, "a2");
    }

    @Test
    void 기준_블록이_없는_맨_앞에는_글을_넣을_수_없다() {
        assertThatThrownBy(() -> editor.save(T, PAGE, List.of(text(List.of(), "새 글", true), image("img"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 빈_문서면_맨_뒤에_넣는다() {
        editor.save(T, PAGE, List.of(text(List.of(), "첫 글", true)));

        verify(api).appendBlocks(eq(T), eq(PAGE), anyList());
    }

    // ===================== 표 =====================

    @Test
    void 표는_바뀐_행만_고치고_새_행은_붙이고_지운_행은_지운다() {
        Item table = new Item("TABLE", "tbl", null, null, true, 2, List.of(
                new TableRow("r1", List.of("이름", "역할"), false),
                new TableRow("r2", List.of("민준", "백엔드"), true),
                new TableRow(null, List.of("찬서", "프론트"), false)),
                List.of("r3"), null, null, null, false);

        editor.save(T, PAGE, List.of(table));

        verify(api, never()).updateTableRow(eq(T), eq("r1"), anyList(), eq(2));
        verify(api).updateTableRow(T, "r2", List.of("민준", "백엔드"), 2);
        verify(api).appendTableRows(T, "tbl", List.of(List.of("찬서", "프론트")), 2);
        verify(api).deleteBlock(T, "r3");
    }

    // ===================== 이미지 =====================

    @Test
    void 이미지는_바꾸고_지우고_앞_블록_뒤에_새로_넣는다() {
        when(api.insertImage(eq(T), eq(PAGE), eq("img1"), any())).thenReturn("img-new");

        editor.save(T, PAGE, List.of(
                new Item("IMAGE", "img1", null, null, true, null, null, null, null, "upload-1", "새 캡션", false),
                new Item("IMAGE", null, null, null, true, null, null, null, "https://x/b.png", null, null, false),
                new Item("IMAGE", "img2", null, null, false, null, null, null, null, null, null, true)));

        verify(api).updateImage(eq(T), eq("img1"), eq(NotionApiClient.imageBody(null, "upload-1", "새 캡션")));
        verify(api).insertImage(eq(T), eq(PAGE), eq("img1"), eq(NotionApiClient.imageBody("https://x/b.png", null, null)));
        verify(api).deleteBlock(T, "img2");
    }

    @Test
    void 파일이나_주소가_없는_새_이미지는_거절한다() {
        assertThatThrownBy(() -> editor.save(T, PAGE, List.of(image("a"),
                new Item("IMAGE", null, null, null, true, null, null, null, null, null, null, false))))
                .isInstanceOf(BusinessException.class);
        verify(api, never()).insertImage(anyString(), anyString(), any(), any());
    }

    // ===================== 요청 JSON =====================

    @Test
    void 화면이_changed_deleted를_빼고_보내도_요청을_읽는다() {
        String json = """
                {"items":[
                  {"kind":"TEXT","blockIds":["a1"],"text":"글","changed":true},
                  {"kind":"FIXED","id":"code"},
                  {"kind":"TABLE","id":"tbl","width":3,"rows":[{"id":null,"cells":["a","b","c"],"changed":false}],"deletedRowIds":[]},
                  {"kind":"IMAGE","id":"img","fileUploadId":null,"url":null,"caption":"","changed":false,"deleted":false}
                ]}""";

        var request = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(json, com.togather.content.notion.dto.NotionBlocksSaveRequest.class);

        assertThat(request.items()).hasSize(4);
        assertThat(request.items().get(1).isChanged()).isFalse();
        assertThat(request.items().get(1).isDeleted()).isFalse();
        assertThat(request.items().get(0).isChanged()).isTrue();
    }

    // ===================== 도움 =====================

    private static Item text(List<String> blockIds, String text, boolean changed) {
        return new Item("TEXT", null, blockIds, text, changed, null, null, null, null, null, null, false);
    }

    private static Item image(String id) {
        return new Item("IMAGE", id, null, null, false, null, null, null, null, null, null, false);
    }

    private static Map<String, Object> block(String id, String type, Map<String, Object> body, boolean hasChildren) {
        return Map.of("id", id, "type", type, type, body, "has_children", hasChildren);
    }

    private static List<Map<String, Object>> rich(String text) {
        return List.of(Map.of("plain_text", text));
    }
}
