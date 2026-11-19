package com.steven.assets.integration.fubon;

import com.steven.assets.service.FubonScheduleSwitchService;
import com.steven.assets.service.FubonScheduleSwitchService.State;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FubonScheduleSwitchAspectTest {

    static class Target {
        final AtomicInteger runs = new AtomicInteger();

        @FubonScheduleSwitched("inventory")
        public void scheduled() {
            runs.incrementAndGet();
        }

        public void unmarked() {
            runs.incrementAndGet();
        }
    }

    private Target proxy(Target target, FubonScheduleSwitchService switches) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new FubonScheduleSwitchAspect(switches));
        return factory.getProxy();
    }

    @Test
    void runsWhenSwitchIsOn() {
        FubonScheduleSwitchService switches = mock(FubonScheduleSwitchService.class);
        when(switches.state("inventory")).thenReturn(State.ON);
        Target target = new Target();
        proxy(target, switches).scheduled();
        assertThat(target.runs).hasValue(1);
    }

    @Test
    void skipsWhenSwitchIsOffMissingOrUnreadable() {
        for (State state : new State[] {State.OFF, State.MISSING, State.UNREADABLE}) {
            FubonScheduleSwitchService switches = mock(FubonScheduleSwitchService.class);
            when(switches.state("inventory")).thenReturn(state);
            Target target = new Target();
            proxy(target, switches).scheduled();
            assertThat(target.runs).as(state.name()).hasValue(0);
        }
    }

    @Test
    void leavesUnmarkedMethodsAlone() {
        FubonScheduleSwitchService switches = mock(FubonScheduleSwitchService.class);
        Target target = new Target();
        proxy(target, switches).unmarked();
        assertThat(target.runs).hasValue(1);
    }
}
