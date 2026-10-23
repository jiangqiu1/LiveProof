package com.liveproof.exception;

/**
 * Range 请求不可满足（start 超出文件长度或范围非法）。
 * 处理器会补上 Content-Range: bytes *&#47;total。
 */
public class NotSatisfiableRangeException extends RuntimeException {

    private final long total;

    public NotSatisfiableRangeException(long total) {
        super("请求的字节范围不可满足");
        this.total = total;
    }

    public long getTotal() {
        return total;
    }
}
