package com.steven.assets.dto;

import java.time.LocalDate;

/** Export content and the application-resolved range used by transport filenames. */
public record HistoryExport(byte[] content, String code, LocalDate start, LocalDate end) {}
