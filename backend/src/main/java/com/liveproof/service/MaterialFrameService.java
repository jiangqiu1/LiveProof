package com.liveproof.service;

import com.liveproof.config.PolyvProperties;
import com.liveproof.entity.MaterialEntity;
import com.liveproof.exception.ApiException;
import com.liveproof.polyv.PolyvVodClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 画面取帧调度：按素材存储类型选择取帧方式。
 *
 * <p>两条轨道：
 * <ul>
 *   <li>{@code storage=local} → 直接委托 {@link FrameExtractionService}（本地 FFmpeg，精度 0.01s）</li>
 *   <li>{@code storage=polyv} → 保利威「指定时间点截图」API（异步，精度为<b>整秒</b>）</li>
 * </ul>
 *
 * <p>降级原则：截图失败<b>不静默</b>。返回的 {@code note} 会进入提示词与响应，
 * 明确写出「本次未做画面核验（仅基于字幕判断）」。
 */
@Service
public class MaterialFrameService {

    private static final Logger log = LoggerFactory.getLogger(MaterialFrameService.class);

    /** 云端取帧的精度说明，会作为一条 normalizationNote 回传前端。 */
    static final String PRECISION_NOTE = "云端画面帧由保利威按整秒截取（请求 18.4s 实际取 18s），时间点存在不超过 0.5s 的偏差";

    private final FrameExtractionService localFrames;
    private final MaterialService materialService;
    private final PolyvVodClient vodClient;
    private final PolyvProperties polyvProperties;

    public MaterialFrameService(FrameExtractionService localFrames,
                                MaterialService materialService,
                                PolyvVodClient vodClient,
                                PolyvProperties polyvProperties) {
        this.localFrames = localFrames;
        this.materialService = materialService;
        this.vodClient = vodClient;
        this.polyvProperties = polyvProperties;
    }

    /**
     * 取帧。
     *
     * @param videoRef 用例里的视频引用（内置素材为文件名，云端素材为素材 id）
     * @param times    时间点（秒）
     */
    public FrameExtractionService.Extraction extract(String videoRef, List<Double> times) {
        MaterialEntity material = materialService.findByRef(videoRef);
        if (material == null || material.isLocal()) {
            return localFrames.extract(videoRef, times);
        }
        if (!polyvProperties.vodConfigured()) {
            return new FrameExtractionService.Extraction(false,
                    "保利威点播未配置，本次未做画面核验（仅基于字幕判断）", List.of());
        }
        if (material.getPolyvVid() == null || material.getPolyvVid().isBlank()) {
            return new FrameExtractionService.Extraction(false,
                    "该素材缺少保利威 vid，本次未做画面核验（仅基于字幕判断）", List.of());
        }
        if (times == null || times.isEmpty()) {
            return new FrameExtractionService.Extraction(true, null, List.of());
        }

        long started = System.currentTimeMillis();
        try {
            List<PolyvVodClient.Snapshot> snapshots = vodClient.snapshot(
                    material.getPolyvVid(), times,
                    polyvProperties.getSnapshotWidth(),
                    material.getWidth(), material.getHeight());
            List<FrameExtractionService.Frame> frames = new ArrayList<>(snapshots.size());
            for (PolyvVodClient.Snapshot snapshot : snapshots) {
                byte[] jpeg = vodClient.downloadImage(snapshot.imageUrl());
                int[] size = FrameExtractionService.jpegSize(jpeg);
                frames.add(new FrameExtractionService.Frame(
                        snapshot.offsetSec(), jpeg,
                        size == null ? 0 : size[0], size == null ? 0 : size[1]));
            }
            log.info("云端取帧完成：素材={} 请求帧={} 返回帧={} 耗时={}ms",
                    material.getId(), times.size(), frames.size(), System.currentTimeMillis() - started);
            return new FrameExtractionService.Extraction(true, PRECISION_NOTE, frames);
        } catch (ApiException e) {
            log.warn("云端取帧失败：素材={} 原因={}", material.getId(), e.getMessage());
            return new FrameExtractionService.Extraction(false,
                    "保利威截图任务未成功（" + e.getMessage() + "），本次未做画面核验（仅基于字幕判断）",
                    List.of());
        }
    }
}
