package com.steven.assets.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 344.3 / 344.26(m)：提領優先序的兩件<b>互不相干</b>的事各自生效——
 * <b>(a) 產品 seed</b>（{@link DataInitializer#DEPOSIT_TYPE_SEEDS}，供全新 DB 建立時用）與
 * <b>(b) 既有資料回填</b>（{@code v1.107.0-deposit-type-withdrawal-order.sql} 的一次性 UPDATE，供已部署 DB 用）。
 *
 * <p>兩者不能互相取代：{@code seedDepositTypes()} 的迴圈是「{@code findByCode(...).isEmpty()} 才 save」，
 * <b>只 INSERT、從不 UPDATE</b>，光改 seed 常數在已部署的 DB 上一列都回填不到，全部會停在 changeset 的 DEFAULT 50。</p>
 */
class DepositTypeWithdrawalOrderSeedTest {

    private static final String CHANGESET = "db/changelog/changes/v1.107.0-deposit-type-withdrawal-order.sql";

    // ===== (a) 產品 seed：六個內建類型各有指定的 withdrawalOrder =====

    @Test
    void productSeed_carriesTheIntendedWithdrawalOrders() {
        Map<String, Integer> expected = Map.of(
                "活存", 10, "美元活存", 20, "定存", 90, "美元定存", 91,
                "證券戶", 50, "信用卡待付款", 50);

        assertEquals(expected.size(), DataInitializer.DEPOSIT_TYPE_SEEDS.size());
        for (DataInitializer.DepositTypeSeed s : DataInitializer.DEPOSIT_TYPE_SEEDS) {
            assertEquals(expected.get(s.code()), s.withdrawalOrder(),
                    s.code() + " 的提領優先序不符（越小越優先被提領）");
        }
    }

    @Test
    void productSeed_neverContainsTheUsersOwnPrivateDepositType() {
        assertTrue(DataInitializer.DEPOSIT_TYPE_SEEDS.stream()
                        .noneMatch(s -> s.code().startsWith("優利活存")),
                "「優利活存 1.5%」是使用者自建的私有分類（名稱還內含會過期的利率字面值），"
                        + "寫進產品 seed 等於在任何乾淨 DB 上憑空建出別人的分類");
    }

    // ===== (b) changeset 的一次性回填：五筆既有資料（含使用者私有分類）=====

    @Test
    void changeset_backfillsExistingRowsIncludingTheUsersPrivateType() {
        String sql = readChangeset();

        assertTrue(sql.contains("ADD COLUMN IF NOT EXISTS withdrawal_order INTEGER NOT NULL DEFAULT 50"),
                "加欄位須冪等");
        assertBackfill(sql, "活存", 10);
        assertBackfill(sql, "美元活存", 20);
        assertBackfill(sql, "優利活存 1.5%", 30);   // 只能出現在這裡，不得進產品 seed
        assertBackfill(sql, "定存", 90);
        assertBackfill(sql, "美元定存", 91);
    }

    @Test
    void changeset_isRegisteredAtTheEndOfTheMasterChangelog() {
        String master = read("db/changelog/db.changelog-master.yaml");
        assertTrue(master.contains("v1.107.0-deposit-type-withdrawal-order.sql"),
                "changeset 未在 master changelog 註冊，等於不會被執行");
    }

    /**
     * {@code AND withdrawal_order = 50} 的作用是讓 changeset <b>可重複執行</b>
     * （正常單次執行時所有列皆為 DEFAULT 50）。
     * <b>不得斷言成「保護使用者已調整的值」</b>——使用者若剛好把某類型調成 50，重跑仍會被覆蓋；
     * 真正的保護是「這支 changeset 只跑一次」。
     */
    private static void assertBackfill(String sql, String code, int order) {
        String head = "UPDATE deposit_type SET withdrawal_order = " + order + " WHERE code = '" + code + "'";
        int at = sql.indexOf(head);
        assertTrue(at >= 0, "缺少 " + code + " 的回填 UPDATE");
        String rest = sql.substring(at + head.length(), sql.indexOf(';', at));
        assertTrue(rest.contains("withdrawal_order = 50"),
                code + " 的回填缺少 `AND withdrawal_order = 50`，changeset 將不可重複執行");
        assertFalse(rest.contains("display_name"), code + " 的回填不得比對顯示名稱（寫入欄位的是 code）");
    }

    private static String readChangeset() {
        return read(CHANGESET);
    }

    private static String read(String classpath) {
        try (InputStream in = DepositTypeWithdrawalOrderSeedTest.class.getClassLoader()
                .getResourceAsStream(classpath)) {
            assertNotNull(in, "找不到 " + classpath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("讀取 " + classpath + " 失敗", e);
        }
    }
}
