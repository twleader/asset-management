package com.steven.assets.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Keeps Task408's decision resolver independent from JDBC/Redis/Lua infrastructure. */
class RadarTechnicalResolverPortIsolationTest {

    @Test
    void resolverDependsOnTechnicalPortsRatherThanInfrastructureClients() {
        assertThat(Arrays.stream(RadarTechnicalResolver.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName))
                .doesNotContain(
                        "org.springframework.jdbc.core.JdbcTemplate",
                        "org.springframework.data.redis.core.StringRedisTemplate",
                        "org.springframework.data.redis.core.script.DefaultRedisScript",
                        "org.springframework.core.io.ClassPathResource");

        assertThat(Arrays.stream(RadarTechnicalResolver.class.getDeclaredFields())
                .map(Field::getType))
                .contains(RadarTechnicalFactPort.class, RadarTechnicalCachePort.class);
    }
}
