package com.steven.assets.integration.fubon;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 標記受「富邦證 API」頁排程開關控制的排程方法（Requirement 218／Task 612）；value 為 {@code fubon_schedule_switch.schedule_key}。 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FubonScheduleSwitched {
    String value();
}
