package com.steven.assets.controller;

import com.steven.assets.dto.WatchStockDto;
import com.steven.assets.service.WatchStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/watch-stocks")
@RequiredArgsConstructor
public class WatchStockController {

    private final WatchStockService service;

    @GetMapping
    public List<WatchStockDto.Response> findAll() {
        return service.findAll();
    }

    @PostMapping
    public WatchStockDto.Response create(@RequestBody WatchStockDto.Request req) {
        return service.create(req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody List<Long> orderedIds) {
        service.reorder(orderedIds);
        return ResponseEntity.noContent().build();
    }
}
