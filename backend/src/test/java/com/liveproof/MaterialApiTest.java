package com.liveproof;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.liveproof.polyv.PolyvVodClient;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 素材库接口测试。
 *
 * <p>测试环境 {@code liveproof.polyv} 的 v2 凭据为空（见 application-test.yml），
 * 因此上传接口应稳定返回 503 而不是打到真实账号；云端相关用例用 MockitoBean 替换
 * {@link PolyvVodClient}，验证的是「本地编排与校验」而不是网络。
 */
class MaterialApiTest extends ApiTestBase {

    @MockitoBean
    private PolyvVodClient vodClient;

    @Test
    @DisplayName("素材列表包含两条内置素材，且不泄露任何绝对路径")
    void listBuiltin() throws Exception {
        mockMvc.perform(get("/api/materials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].builtin").value(true))
                .andExpect(jsonPath("$[*].storage", everyItem(org.hamcrest.Matchers.is("local"))))
                .andExpect(jsonPath("$[0].status").value("ready"))
                // 关键断言：响应里不能出现盘符或目录分隔符
                .andExpect(jsonPath("$[0].fileName").value(not(containsString(":\\"))))
                .andExpect(jsonPath("$[0].fileName").value(not(containsString("/"))));
    }

    @Test
    @DisplayName("接入能力接口如实反映「上传未配置」，前端据此禁用按钮")
    void capabilities() throws Exception {
        mockMvc.perform(get("/api/materials/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadEnabled").value(false))
                .andExpect(jsonPath("$.vodConfigured").value(false))
                .andExpect(jsonPath("$.maxBytes").value(200L * 1024 * 1024))
                .andExpect(jsonPath("$.maxCount").value(20));
    }

    @Test
    @DisplayName("未配置写密钥时上传返回 503，不静默失败")
    void uploadNotConfigured() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.mp4", "video/mp4", mp4Bytes());
        mockMvc.perform(multipart("/api/materials").file(file))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("POLYV_UPLOAD_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.error.message", containsString("上传")));
    }

    @Test
    @DisplayName("内置素材禁止删除")
    void deleteBuiltinRejected() throws Exception {
        mockMvc.perform(delete("/api/materials/mat_builtin_demo"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUILTIN_MATERIAL"));
    }

    @Test
    @DisplayName("内置素材没有 play 接口可用：明确 409 指向 /media")
    void playBuiltinRejected() throws Exception {
        mockMvc.perform(get("/api/materials/mat_builtin_demo/play"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MATERIAL_NOT_READY"))
                .andExpect(jsonPath("$.error.message", containsString("/media")));
    }

    @Test
    @DisplayName("不存在的素材返回 404 而不是 500")
    void unknownMaterial() throws Exception {
        mockMvc.perform(get("/api/materials/mat_does_not_exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MATERIAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("SRT 上传校验：非法内容拒绝，合法内容落库并标记 srt 文件名")
    void uploadSubtitle() throws Exception {
        MockMultipartFile bad = new MockMultipartFile(
                "file", "bad.srt", "text/plain", "not a srt".getBytes());
        mockMvc.perform(multipart("/api/materials/mat_builtin_demo/subtitle").file(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("MATERIAL_INVALID_FILE"));

        MockMultipartFile good = new MockMultipartFile("file", "ok.srt", "text/plain",
                ("1\n00:00:01,000 --> 00:00:02,000\n测试字幕\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/materials/mat_builtin_demo/subtitle").file(good))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.srtFileName").value("mat_builtin_demo.srt"));

        // 上传后：素材登记的 srt 必须真的生效（用文件名查也要拿到它，而不是静态映射那份）
        mockMvc.perform(get("/api/subtitles").param("video", "candidateflow-demo.mp4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitleFile").value("mat_builtin_demo.srt"))
                .andExpect(jsonPath("$.cueCount").value(1));

        // 收尾：删除字幕，避免留下文件影响其他用例
        mockMvc.perform(delete("/api/materials/mat_builtin_demo/subtitle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.srtFileName").doesNotExist());

        // 删除必须真的清掉数据库里的登记列：updateById 默认跳过 null 字段，
        // 只改内存实体会出现「文件已删、登记还在」，字幕解析随后拿着不存在的文件名报错
        mockMvc.perform(get("/api/materials/mat_builtin_demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.srtFileName").doesNotExist());

        // 登记清掉后回退到静态映射，行为与上传前一致
        mockMvc.perform(get("/api/subtitles").param("video", "candidateflow-demo.mp4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitleFile").value("candidateflow-demo.srt"));
    }

    /** 最小合法 mp4 头：4 字节长度 + ftyp + 8 字节占位，足够通过魔数校验。 */
    private static byte[] mp4Bytes() {
        return new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    }
}