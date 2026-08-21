package com.steven.assets.externalmaterials.service;

import java.util.Map;
import java.util.Optional;

/**
 * 完整政府辦公日曆的暫行台股年度 authority port。adapter 必須只在整年資料完全驗證後回值。
 */
public interface DgpaCalendarAuthority {

    Optional<Map<String, String>> fetchHolidays(int year);
}
