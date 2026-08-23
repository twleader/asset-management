package com.steven.assets.repository;

import com.steven.assets.model.StockDividendHistory;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 357.3d-1：{@link StockDividendHistoryRepository} 的兩支 JPQL 必須以
 * {@code LEAST(exDividendDate, exRightsDate)}（＝錨定日）取代裸的 {@code exDividendDate}。
 *
 * <p><b>是 {@code LEAST} 不是 {@code COALESCE}</b>（規格 357.3d-1 字面寫 COALESCE，
 * 那是規格的疏漏）：PostgreSQL 的 {@code LEAST} 忽略 {@code NULL}、取較早者，與 Java 端
 * {@link com.steven.assets.model.DividendDates#anchorDate} 逐位相同；{@code COALESCE} 取的是
 * 「第一個非 null」，在兩欄皆有值且除權日較早時會取到較晚的除息日，讓實際落在視窗內的事件
 * 被 {@code BETWEEN} 排除——與本任務要修的缺陷同型。</p>
 *
 * <p><b>為什麼要用反射讀 {@code @Query}。</b>本專案沒有 H2、Testcontainers 也需要 Docker，
 * 這兩支查詢在單元測試層沒有任何執行路徑。把查詢字串抄一份到測試裡等於自己跟自己對答案；
 * 反射讀的是<b>真正會被 Spring Data 拿去用的那一份</b>，改了 production 就會反映在這裡。</p>
 *
 * <p><b>為什麼還要跑一次 HQL 解析。</b>Spring Data JPA 在 <b>context 啟動時</b>驗證
 * {@code @Query}，語法錯誤的後果不是查詢回錯資料，是 business-services <b>起不來</b>
 * （crash loop）。{@code COALESCE(...) DESC NULLS LAST} 這種在 ORDER BY 裡包函數的寫法
 * 值得先在測試裡證明它真的能被 Hibernate 解析，而不是等 Docker 驗收才發現。
 * 此處只建 metamodel 與 SQM，不需要 JDBC 連線。</p>
 */
class StockDividendHistoryExRightsQueryTest {

    private static final String ANCHOR = "LEAST(h.exDividendDate, h.exRightsDate)";

    @Test
    @DisplayName("357.3d-1 findAdjustmentEvents 的區間與排序改用 LEAST(除息日, 除權日)")
    void adjustmentEventsQueryUsesAnchorDate() {
        String hql = queryOf("findAdjustmentEvents");

        assertThat(hql)
                .as("BETWEEN 必須套在錨定日上，否則純配股事件（除息日 null）整批消失")
                .contains(ANCHOR + " BETWEEN :fromDate AND :toDate");
        assertThat(hql)
                .as("ORDER BY 同樣要用錨定日，否則排序鍵與過濾鍵不一致")
                .contains("ORDER BY " + ANCHOR + " ASC, h.id ASC");
        assertThat(hql)
                .as("不得殘留任何裸的 h.exDividendDate 判準，也不得改回 COALESCE（語意不同）")
                .doesNotContain("h.exDividendDate BETWEEN")
                .doesNotContain("ORDER BY h.exDividendDate")
                .doesNotContain("COALESCE(h.exDividendDate");
    }

    @Test
    @DisplayName("357.3d-1 findByStockSinceYear 的同年排序改用 LEAST(除息日, 除權日)")
    void sinceYearQueryOrdersByAnchorDate() {
        String hql = queryOf("findByStockSinceYear");

        assertThat(hql)
                .as("同年內依錨定日遞減；NULLS LAST 留給兩欄皆空的年度彙總列")
                .contains("ORDER BY h.year DESC, " + ANCHOR + " DESC NULLS LAST");
        assertThat(hql).doesNotContain("h.year DESC, h.exDividendDate DESC");
        assertThat(hql).doesNotContain("COALESCE(h.exDividendDate");
    }

    /**
     * 兩支查詢都必須是合法 HQL。
     *
     * <p>失敗即代表 business-services 會在 Spring Data 驗證 {@code @Query} 時啟動失敗，
     * 而不是回錯資料——這是本專案已知的 crash loop 型故障。</p>
     *
     * <p><b>這一關擋得住什麼、擋不住什麼（實測過，不要高估它）。</b>Hibernate 6.6 會拒絕
     * 不存在的欄位路徑（下方的自我防呆即靠這一點），但<b>不會</b>拒絕未註冊的函式名——
     * 它會原樣送給資料庫。因此本測試另外直接查 {@code SqmFunctionRegistry} 確認
     * {@code least} 真的是 PostgreSQLDialect 註冊過的函式，而不是被當成不明字串放行。</p>
     */
    @Test
    @DisplayName("357.3d-1 兩支 JPQL 必須能被 Hibernate 解析（否則 context 啟動即失敗）")
    void bothQueriesParseAsValidHql() {
        try (SessionFactory factory = sessionFactory()) {
            try (var session = factory.openSession()) {
                for (String method : new String[]{"findAdjustmentEvents", "findByStockSinceYear"}) {
                    String hql = queryOf(method);
                    assertThatCode(() -> session.createQuery(hql, StockDividendHistory.class))
                            .as("%s 的 JPQL 必須合法：%s", method, hql)
                            .doesNotThrowAnyException();
                }
                assertThat(factory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class)
                        .getQueryEngine().getSqmFunctionRegistry().findFunctionDescriptor("least"))
                        .as("least 必須是 PostgreSQLDialect 註冊過的函式；"
                                + "Hibernate 對未註冊的函式名一律放行，光靠解析不會發現")
                        .isNotNull();
                // 自我防呆：解析器必須真的會拒絕壞查詢，否則上面兩條斷言是空的。
                assertThatThrownBy(() -> session.createQuery("""
                        SELECT h FROM StockDividendHistory h
                        ORDER BY COALESCE(h.exDividendDate, h.thereIsNoSuchField) DESC NULLS LAST
                        """, StockDividendHistory.class))
                        .as("這一句是刻意寫壞的，解析器沒攔下來代表本測試是空的")
                        .isInstanceOf(RuntimeException.class);
            }
        }
    }

    /** 只建 metamodel，不連 DB：明確指定 dialect 並關閉 JDBC metadata 探測。 */
    private static SessionFactory sessionFactory() {
        return new Configuration()
                .addAnnotatedClass(StockDividendHistory.class)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
                .setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .setProperty("hibernate.connection.provider_class",
                        "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl")
                .buildSessionFactory();
    }

    /** 從 repository 介面上真正的 {@code @Query} 取字串，避免測試與 production 各寫一份。 */
    private static String queryOf(String methodName) {
        for (Method method : StockDividendHistoryRepository.class.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) continue;
            Query query = method.getAnnotation(Query.class);
            if (query != null) return query.value();
        }
        throw new IllegalStateException("找不到帶 @Query 的方法：" + methodName);
    }
}
