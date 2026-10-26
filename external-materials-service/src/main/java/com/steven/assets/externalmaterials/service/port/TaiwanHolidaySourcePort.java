package com.steven.assets.externalmaterials.service.port;

import java.util.Map;
import java.util.Optional;

/** Narrow market capability implemented by the existing IO adapter. */
public interface TaiwanHolidaySourcePort {
    Map<String, String> getTwHolidays(int year);
    Optional<Map<String, String>> getTwHolidaysKnown(int year);
    Optional<Map<String, String>> peekTwHolidaysKnown(int year);
}
