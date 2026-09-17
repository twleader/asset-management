package com.steven.assets.testsupport;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.testcontainers.junit.jupiter.Testcontainers;

class PostgresTestContextCleanupListenerTest {
    @Testcontainers
    static class ContainerFixture {}
    static class OrdinaryFixture {}

    private final PostgresTestContextCleanupListener listener = new PostgresTestContextCleanupListener();

    @Test
    void absentContextIsNeverLoaded() {
        TestContext context = mock(TestContext.class);
        listener.afterTestClass(context);
        verify(context).hasApplicationContext();
        verifyNoMoreInteractions(context);
    }

    @Test
    void ordinaryClassDoesNotLoadOrDirtyItsCachedContext() {
        TestContext context = mock(TestContext.class);
        when(context.hasApplicationContext()).thenReturn(true);
        doReturn(OrdinaryFixture.class).when(context).getTestClass();
        listener.afterTestClass(context);
        verify(context, never()).getApplicationContext();
        verify(context, never()).markApplicationContextDirty(any());
    }

    @Test
    void nonPostgresContextIsPreserved() {
        TestContext context = context("jdbc:h2:mem:test", "create-drop");
        listener.afterTestClass(context);
        verify(context, never()).markApplicationContextDirty(any());
    }

    @Test
    void contextWithoutDropLifecycleIsPreserved() {
        TestContext context = context("jdbc:postgresql://localhost/test", "validate");
        listener.afterTestClass(context);
        verify(context, never()).markApplicationContextDirty(any());
    }

    @Test
    void matchingFixtureClosesOnlyCurrentContextOnce() {
        TestContext context = context("jdbc:postgresql://localhost/test", "create-drop");
        listener.afterTestClass(context);
        verify(context, times(1)).markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
    }

    private static TestContext context(String url, String ddlAuto) {
        TestContext context = mock(TestContext.class);
        ApplicationContext application = mock(ApplicationContext.class);
        when(context.hasApplicationContext()).thenReturn(true);
        doReturn(ContainerFixture.class).when(context).getTestClass();
        when(context.getApplicationContext()).thenReturn(application);
        when(application.getEnvironment()).thenReturn(new MockEnvironment()
                .withProperty("spring.datasource.url", url)
                .withProperty("spring.jpa.hibernate.ddl-auto", ddlAuto));
        return context;
    }
}
