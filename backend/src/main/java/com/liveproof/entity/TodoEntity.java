package com.liveproof.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.liveproof.enums.TodoSource;
import com.liveproof.enums.TodoStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 待办（表 todos）。独立实体，不是由 cases.follow_up_required 派生的视图。
 */
@Getter
@Setter
@TableName("todos")
public class TodoEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String caseId;
    private String title;
    private String detail;
    private TodoStatus status;
    private TodoSource source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
