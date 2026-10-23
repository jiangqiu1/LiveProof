package com.liveproof.dto;

/**
 * 单条字幕。
 *
 * @param index     SRT 序号
 * @param startSec  起始秒
 * @param endSec    结束秒
 * @param text      文本（多行以 \n 连接）
 */
public record SubtitleCue(int index, double startSec, double endSec, String text) {
}
