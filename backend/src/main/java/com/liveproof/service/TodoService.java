package com.liveproof.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.liveproof.dto.TodoCreateRequest;
import com.liveproof.dto.TodoDto;
import com.liveproof.dto.TodoUpdateRequest;
import com.liveproof.entity.TodoEntity;
import com.liveproof.enums.TodoSource;
import com.liveproof.enums.TodoStatus;
import com.liveproof.exception.BadRequestException;
import com.liveproof.exception.NotFoundException;
import com.liveproof.mapper.CaseMapper;
import com.liveproof.mapper.TodoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待办业务。独立持久化实体，不依赖 cases.follow_up_required 派生。
 */
@Service
public class TodoService {

    private final TodoMapper todoMapper;
    private final CaseMapper caseMapper;

    public TodoService(TodoMapper todoMapper, CaseMapper caseMapper) {
        this.todoMapper = todoMapper;
        this.caseMapper = caseMapper;
    }

    public List<TodoDto> list(TodoStatus status, String caseId) {
        LambdaQueryWrapper<TodoEntity> wrapper = Wrappers.lambdaQuery(TodoEntity.class);
        if (status != null) {
            wrapper.eq(TodoEntity::getStatus, status);
        }
        if (caseId != null && !caseId.isBlank()) {
            wrapper.eq(TodoEntity::getCaseId, caseId.trim());
        }
        wrapper.orderByDesc(TodoEntity::getCreatedAt).orderByDesc(TodoEntity::getId);
        return todoMapper.selectList(wrapper).stream().map(TodoService::toDto).toList();
    }

    public TodoDto detail(Long id) {
        return toDto(require(id));
    }

    @Transactional
    public TodoDto create(TodoCreateRequest request) {
        String caseId = request.caseId().trim();
        if (caseMapper.selectById(caseId) == null) {
            throw NotFoundException.caseNotFound(caseId);
        }

        TodoEntity entity = new TodoEntity();
        entity.setCaseId(caseId);
        entity.setTitle(request.title().trim());
        entity.setDetail(request.detail());
        entity.setStatus(TodoStatus.OPEN);
        entity.setSource(TodoSource.MANUAL);
        todoMapper.insert(entity);

        return toDto(require(entity.getId()));
    }

    @Transactional
    public TodoDto update(Long id, TodoUpdateRequest request) {
        if (request == null || request.isEmpty()) {
            throw BadRequestException.emptyPatch();
        }
        TodoEntity existing = require(id);

        LambdaUpdateWrapper<TodoEntity> update = Wrappers.lambdaUpdate(TodoEntity.class)
                .eq(TodoEntity::getId, id)
                .set(TodoEntity::getUpdatedAt, LocalDateTime.now());
        if (request.title() != null) {
            String title = request.title().trim();
            if (title.isEmpty()) {
                throw BadRequestException.invalidParameter("title 不能为空");
            }
            update.set(TodoEntity::getTitle, title);
        }
        if (request.detail() != null) {
            update.set(TodoEntity::getDetail, request.detail());
        }
        if (request.status() != null) {
            update.set(TodoEntity::getStatus, request.status());
            // completed_at 与 status 联动：进入 done 写时间，离开 done 清空
            if (request.status() == TodoStatus.DONE) {
                update.set(TodoEntity::getCompletedAt,
                        existing.getCompletedAt() != null ? existing.getCompletedAt() : LocalDateTime.now());
            } else {
                update.set(TodoEntity::getCompletedAt, null);
            }
        }
        todoMapper.update(null, update);
        return toDto(require(id));
    }

    /**
     * 标记完成。重复调用幂等：已完成则保留原完成时间，不重写。
     */
    @Transactional
    public TodoDto complete(Long id) {
        TodoEntity existing = require(id);
        if (existing.getStatus() == TodoStatus.DONE && existing.getCompletedAt() != null) {
            return toDto(existing);
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<TodoEntity> update = Wrappers.lambdaUpdate(TodoEntity.class)
                .eq(TodoEntity::getId, id)
                .set(TodoEntity::getStatus, TodoStatus.DONE)
                .set(TodoEntity::getCompletedAt, now)
                .set(TodoEntity::getUpdatedAt, now);
        todoMapper.update(null, update);
        return toDto(require(id));
    }

    private TodoEntity require(Long id) {
        TodoEntity entity = todoMapper.selectById(id);
        if (entity == null) {
            throw NotFoundException.todoNotFound(id);
        }
        return entity;
    }

    private static TodoDto toDto(TodoEntity e) {
        return new TodoDto(e.getId(), e.getCaseId(), e.getTitle(), e.getDetail(),
                e.getStatus(), e.getSource(), e.getCreatedAt(), e.getUpdatedAt(), e.getCompletedAt());
    }
}
