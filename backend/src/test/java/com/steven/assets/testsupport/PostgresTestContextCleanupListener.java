package com.steven.assets.testsupport;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Close cached fixture contexts while their class-scoped PostgreSQL container is still running. */
public class PostgresTestContextCleanupListener extends AbstractTestExecutionListener {
    @Override
    public void afterTestClass(TestContext testContext) {
        if (!testContext.hasApplicationContext()
                || !AnnotatedElementUtils.hasAnnotation(testContext.getTestClass(), Testcontainers.class)) {
            return;
        }
        var environment = testContext.getApplicationContext().getEnvironment();
        String url = environment.getProperty("spring.datasource.url", "");
        if ("create-drop".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                && url.startsWith("jdbc:postgresql:")) {
            testContext.markApplicationContextDirty(HierarchyMode.CURRENT_LEVEL);
        }
    }
}
