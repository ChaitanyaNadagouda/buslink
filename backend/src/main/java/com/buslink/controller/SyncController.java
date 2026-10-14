package com.buslink.controller;

import com.buslink.dto.request.SyncBatchRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.SyncBatchResponseDTO;
import com.buslink.dto.response.SyncStatusResponseDTO;
import com.buslink.security.ConductorPrincipal;
import com.buslink.service.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @PostMapping("/upload")
    public ApiResponse<SyncBatchResponseDTO> uploadSyncBatch(
            @Valid @RequestBody SyncBatchRequestDTO batch, @AuthenticationPrincipal ConductorPrincipal principal) {
        return ApiResponse.success(syncService.processSyncBatch(batch, principal.getConductor().getConductorId()));
    }

    @GetMapping("/status")
    public ApiResponse<SyncStatusResponseDTO> getSyncStatus(@AuthenticationPrincipal ConductorPrincipal principal) {
        return ApiResponse.success(syncService.getSyncStatus(principal.getConductor().getConductorId()));
    }
}
