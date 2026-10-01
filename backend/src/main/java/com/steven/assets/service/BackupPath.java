package com.steven.assets.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The sole parser for backup type, Taiwan filename date, and immutable migration cutover. */
final class BackupPath {
    static final LocalDate CUTOVER = LocalDate.of(2026, 9, 29);
    private static final Pattern NAME = Pattern.compile(
            "^asset_(manual|daily_tw|daily_us|weekly|auto-pre-restore)_(\\d{8})_(\\d{6})(?:_([A-Za-z0-9-]+))?\\.dump$");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);

    private final String folder;
    private final String filename;
    private final LocalDate date;
    private final boolean autoPreRestore;

    private BackupPath(String folder, String filename, LocalDate date, boolean autoPreRestore) {
        this.folder = folder;
        this.filename = filename;
        this.date = date;
        this.autoPreRestore = autoPreRestore;
    }

    static BackupPath fromFilename(String filename) {
        Matcher match = NAME.matcher(filename == null ? "" : filename);
        if (!match.matches()) throw new IllegalArgumentException("不合法的備份檔名");
        final LocalDate date;
        try {
            date = LocalDate.parse(match.group(2), DateTimeFormatter.BASIC_ISO_DATE);
            LocalTime.parse(match.group(3), TIME);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("不合法的備份檔名日期");
        }
        String kind = match.group(1);
        String folder = kind.startsWith("daily_") ? "daily"
                : kind.equals("auto-pre-restore") ? "manual" : kind;
        return new BackupPath(folder, filename, date, kind.equals("auto-pre-restore"));
    }

    static BackupPath of(String folder, String filename) {
        BackupPath path = fromFilename(filename);
        if (!path.folder.equals(folder)) throw new IllegalArgumentException("備份檔名與類型不符");
        return path;
    }

    String folder() { return folder; }
    String filename() { return filename; }
    LocalDate date() { return date; }
    String dateFolder() { return date.toString(); }
    boolean autoPreRestore() { return autoPreRestore; }
    boolean active() { return !date.isBefore(CUTOVER); }
}
