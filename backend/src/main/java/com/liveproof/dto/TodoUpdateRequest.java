package com.liveproof.dto;

import com.liveproof.enums.TodoStatus;
import jakarta.validation.constraints.Size;

/**
 * 修改待办请求。三个字段全部可选，只更新出现的字段。
 */
public record TodoUpdateRequest(
        @Size(max = 255, message = "title 长度不能超过 255")
        String title,

        @Size(max = 5000, message = "detail 长度不能超过 5000")
        String detail,

        TodoStatus status
) {

    public boolean isEmpty() {
        return title == null && detail == null && status == null;
    }
}
