package com.liveproof.polyv;

/**
 * 云端任务进度（只描述平台当前给出的两类确定性状态）。
 *
 * <p>56810（任务不存在）不建模为状态，直接抛 404，避免调用方把「不存在」当成「还没好」。
 */
public enum PolyvTaskState {

    /** 56812：字幕正在生成中，不是失败。 */
    PROCESSING,

    /** code=200 且返回了 subtitles 数组。 */
    READY
}
