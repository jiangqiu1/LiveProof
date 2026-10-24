package com.liveproof.service;

import com.liveproof.config.MaterialProperties;
import com.liveproof.config.MediaProperties;
import com.liveproof.dto.SubtitleResponse;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.exception.ApiException;
import com.liveproof.mapper.MaterialMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 字幕来源解析顺序。
 *
 * <p>这个顺序是被一次真实走查逼出来的：给内置素材（白名单文件名）上传 SRT 后，
 * 素材页显示「已上传」，但工作台与 AI 核验拿到的仍是静态映射那份内置字幕——
 * 因为解析顺序把静态映射排在了素材登记字幕前面，而 cases.video 对内置素材是<b>文件名</b>不是素材 id。
 *
 * <p>结论：用户在素材页显式上传的字幕必须最优先，且「素材 id」与「白名单文件名」
 * 两种标识都要能解析到同一个素材行。
 */
class SubtitleSourceResolutionTest {

    @TempDir
    Path uploadDir;

    private static final String UPLOADED = """
            1
            00:00:02,000 --> 00:00:05,000
            用户上传的字幕
            """;

    private static final String BUILTIN = """
            1
            00:00:00,481 --> 00:00:10,061
            内置配套字幕
            """;

    private MaterialProperties materialProperties() {
        MaterialProperties props = new MaterialProperties();
        props.setUploadDir(uploadDir.toString());
        return props;
    }

    private SubtitleService service(MediaProperties media, MaterialMapper mapper) {
        return new SubtitleService(media, materialProperties(), mapper);
    }

    private MaterialEntity material(String id, String fileName, String srtFileName) {
        MaterialEntity entity = new MaterialEntity();
        entity.setId(id);
        entity.setFileName(fileName);
        entity.setSrtFileName(srtFileName);
        return entity;
    }

    @Test
    @DisplayName("内置素材上传 SRT 后：用文件名查也要拿到上传的那份，而不是静态映射")
    void uploadedSrtWinsOverStaticMappingWhenQueriedByFileName() throws Exception {
        Path builtin = uploadDir.resolve("candidateflow-demo.srt");
        Path uploaded = uploadDir.resolve("mat_builtin_demo_nosub.srt");
        Files.writeString(builtin, BUILTIN);
        Files.writeString(uploaded, UPLOADED);

        MediaProperties media = mock(MediaProperties.class);
        when(media.subtitleNameOf("candidateflow-demo-nosub.mp4")).thenReturn(Optional.of("candidateflow-demo.srt"));
        when(media.resolveSubtitle("candidateflow-demo.srt")).thenReturn(Optional.of(builtin));

        MaterialMapper mapper = mock(MaterialMapper.class);
        when(mapper.selectById("candidateflow-demo-nosub.mp4")).thenReturn(null);
        when(mapper.selectOne(any())).thenReturn(
                material("mat_builtin_demo_nosub", "candidateflow-demo-nosub.mp4", "mat_builtin_demo_nosub.srt"));

        SubtitleResponse response = service(media, mapper).forVideo("candidateflow-demo-nosub.mp4");

        assertThat(response.subtitleFile()).isEqualTo("mat_builtin_demo_nosub.srt");
        assertThat(response.source()).isEqualTo(SubtitleResponse.SOURCE_UPLOADED);
        assertThat(response.cues()).hasSize(1);
        assertThat(response.cues().get(0).text()).isEqualTo("用户上传的字幕");
    }

    @Test
    @DisplayName("云端素材（video = 素材 id）同样读到上传的 SRT")
    void uploadedSrtResolvableByMaterialId() throws Exception {
        Files.writeString(uploadDir.resolve("mat_cloud_1.srt"), UPLOADED);

        MediaProperties media = mock(MediaProperties.class);
        MaterialMapper mapper = mock(MaterialMapper.class);
        when(mapper.selectById("mat_cloud_1")).thenReturn(material("mat_cloud_1", null, "mat_cloud_1.srt"));

        SubtitleResponse response = service(media, mapper).forVideo("mat_cloud_1");

        assertThat(response.subtitleFile()).isEqualTo("mat_cloud_1.srt");
        assertThat(response.cues()).hasSize(1);
    }

    @Test
    @DisplayName("没有上传字幕时回退静态映射，行为与修复前一致")
    void fallsBackToStaticMappingWhenNothingUploaded() throws Exception {
        Path builtin = uploadDir.resolve("candidateflow-demo.srt");
        Files.writeString(builtin, BUILTIN);

        MediaProperties media = mock(MediaProperties.class);
        when(media.subtitleNameOf("candidateflow-demo.mp4")).thenReturn(Optional.of("candidateflow-demo.srt"));
        when(media.resolveSubtitle("candidateflow-demo.srt")).thenReturn(Optional.of(builtin));

        MaterialMapper mapper = mock(MaterialMapper.class);
        when(mapper.selectById("candidateflow-demo.mp4")).thenReturn(null);
        when(mapper.selectOne(any())).thenReturn(material("mat_builtin_demo", "candidateflow-demo.mp4", null));

        SubtitleResponse response = service(media, mapper).forVideo("candidateflow-demo.mp4");

        assertThat(response.subtitleFile()).isEqualTo("candidateflow-demo.srt");
        assertThat(response.source()).isEqualTo(SubtitleResponse.SOURCE_MAPPING);
        assertThat(response.cues().get(0).text()).isEqualTo("内置配套字幕");
    }

    @Test
    @DisplayName("既没登记也没映射：{标识}.srt 回退命中时来源标为 legacy")
    void legacyFileFallbackReportsLegacySource() throws Exception {
        Files.writeString(uploadDir.resolve("mat_legacy.srt"), UPLOADED);

        MediaProperties media = mock(MediaProperties.class);
        when(media.subtitleNameOf("mat_legacy")).thenReturn(Optional.empty());

        MaterialMapper mapper = mock(MaterialMapper.class);
        when(mapper.selectById("mat_legacy")).thenReturn(material("mat_legacy", null, null));

        SubtitleResponse response = service(media, mapper).forVideo("mat_legacy");

        assertThat(response.subtitleFile()).isEqualTo("mat_legacy.srt");
        assertThat(response.source()).isEqualTo(SubtitleResponse.SOURCE_LEGACY);
    }

    @Test
    @DisplayName("登记了字幕但文件缺失：如实报错，不用内置字幕顶替")
    void missingUploadedFileRaisesInsteadOfSilentFallback() throws Exception {
        Files.writeString(uploadDir.resolve("candidateflow-demo.srt"), BUILTIN);

        MediaProperties media = mock(MediaProperties.class);
        when(media.subtitleNameOf("mat_ghost")).thenReturn(Optional.empty());

        MaterialMapper mapper = mock(MaterialMapper.class);
        when(mapper.selectById("mat_ghost")).thenReturn(material("mat_ghost", null, "mat_ghost.srt"));

        SubtitleService service = service(media, mapper);
        assertThatThrownBy(() -> service.forVideo("mat_ghost"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("素材登记的字幕文件缺失");
    }
}
