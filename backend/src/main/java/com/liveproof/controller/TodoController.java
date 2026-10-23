package com.liveproof.controller;

import com.liveproof.dto.TodoCreateRequest;
import com.liveproof.dto.TodoDto;
import com.liveproof.dto.TodoUpdateRequest;
import com.liveproof.enums.EnumSupport;
import com.liveproof.enums.TodoStatus;
import com.liveproof.exception.BadRequestException;
import com.liveproof.service.TodoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 待办接口：列表 / 详情 / 创建 / 修改 / 完成。
 */
@RestController
@RequestMapping("/api/todos")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping
    public List<TodoDto> list(@RequestParam(name = "status", required = false) String status,
                              @RequestParam(name = "caseId", required = false) String caseId) {
        return todoService.list(queryParam(status), caseId);
    }

    @GetMapping("/{id}")
    public TodoDto detail(@PathVariable("id") Long id) {
        return todoService.detail(id);
    }

    @PostMapping
    public ResponseEntity<TodoDto> create(@Valid @RequestBody TodoCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(todoService.create(request));
    }

    @PatchMapping("/{id}")
    public TodoDto update(@PathVariable("id") Long id,
                          @Valid @RequestBody TodoUpdateRequest request) {
        return todoService.update(id, request);
    }

    @PostMapping("/{id}/complete")
    public TodoDto complete(@PathVariable("id") Long id) {
        return todoService.complete(id);
    }

    private static TodoStatus queryParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EnumSupport.parse(TodoStatus.class, raw, "status");
        } catch (IllegalArgumentException e) {
            throw BadRequestException.invalidParameter(e.getMessage());
        }
    }
}
