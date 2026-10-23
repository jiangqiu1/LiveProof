package com.liveproof.ai;

/**
 * 一帧待提交给模型的图片（内存态，不落盘）。
 *
 * @param sec       该帧在视频中的时间点（秒）
 * @param mimeType  实际编码格式，固定为 image/jpeg（FFmpeg 输出）
 * @param data      JPEG 字节
 */
public record FrameImage(double sec, String mimeType, byte[] data) {

    public String dataUrl() {
        return "data:" + mimeType + ";base64," + java.util.Base64.getEncoder().encodeToString(data);
    }
}
