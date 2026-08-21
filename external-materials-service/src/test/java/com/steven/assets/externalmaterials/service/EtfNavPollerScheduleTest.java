package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EtfNavPollerScheduleTest {

    @Test
    void scheduledAnnotationsKeepExactlyThreeJobsAndTwoMinuteTwIntradayCadence() throws Exception {
        Scheduled twIntraday = scheduled("scheduledTwUpdate");
        Scheduled twClose = scheduled("scheduledTwCloseUpdate");
        Scheduled usClose = scheduled("scheduledUsUpdate");

        assertThat(twIntraday.cron()).isEqualTo("0 1/2 9-13 * * MON-FRI");
        assertThat(twIntraday.zone()).isEqualTo("Asia/Taipei");
        assertThat(twClose.cron()).isEqualTo("0 30 17 * * MON-FRI");
        assertThat(twClose.zone()).isEqualTo("Asia/Taipei");
        assertThat(usClose.cron()).isEqualTo("0 30 18 * * MON-FRI");
        assertThat(usClose.zone()).isEqualTo("America/New_York");

        assertThat(Arrays.stream(EtfNavPoller.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .map(Method::getName))
                .containsExactlyInAnyOrder("scheduledTwUpdate", "scheduledTwCloseUpdate", "scheduledUsUpdate");
    }

    private static Scheduled scheduled(String method) throws Exception {
        return EtfNavPoller.class.getMethod(method).getAnnotation(Scheduled.class);
    }
}
