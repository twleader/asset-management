package com.steven.assets.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.ChecksumVersion;
import liquibase.changelog.ChangeLogParameters;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Frozen Boot 3.4.4 / Liquibase 4.29.2 identities, captured from main e039bd64.
 * Only migration identities/checksums are retained; no business rows or execution metadata. */
final class LiquibaseHistoricalFixture {
    static final String MASTER = "db/changelog/db.changelog-master.yaml";
    static final String CALENDAR_LOGICAL_PATH =
            "db/changelog/changes/v1.55.0-trading-calendar-export-schedule.sql";
    static final String CALENDAR_INCLUDE_ATTRIBUTE = "      logicalFilePath: " + CALENDAR_LOGICAL_PATH + "\n";
    static final History FROZEN = load();

    private LiquibaseHistoricalFixture() {}

    record Key(String id, String author, String filename) {}
    record Migration(String id, String author, String filename, String checksum) {
        Key key() { return new Key(id, author, filename); }
    }
    record History(List<Migration> master, List<Migration> retainedHistory) {
        List<Migration> all() {
            List<Migration> rows = new ArrayList<>(master);
            rows.addAll(retainedHistory);
            return List.copyOf(rows);
        }
    }

    private static History load() {
        try (var resource = LiquibaseHistoricalFixture.class.getResourceAsStream(
                "/liquibase/boot34-historical-identities.json")) {
            if (resource == null) throw new IllegalStateException("Missing frozen Liquibase history fixture");
            return new ObjectMapper().readValue(resource, History.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read frozen Liquibase history fixture", ex);
        }
    }

    static List<Migration> parse(String root, ClassLoader loader) throws Exception {
        try (var resources = new ClassLoaderResourceAccessor(loader)) {
            var changelog = ChangeLogParserFactory.getInstance().getParser(root, resources)
                    .parse(root, new ChangeLogParameters(), resources);
            return changelog.getChangeSets().stream().map(change -> new Migration(
                    change.getId(), change.getAuthor(), change.getFilePath(),
                    change.generateCheckSum(ChecksumVersion.V9).toString())).toList();
        }
    }

    /** Check frozen identities before seeding any future entries from the current parser.
     * A renamed historical file cannot masquerade as an appended migration: its frozen identity
     * must still be present, with the same checksum and historical order. */
    static List<Migration> compatibleHistory(List<Migration> currentMaster) {
        Map<Key, Migration> historical = new LinkedHashMap<>();
        for (Migration row : FROZEN.all()) {
            assertThat(historical.put(row.key(), row)).as("unique frozen migration identity").isNull();
        }
        var masterKeys = FROZEN.master().stream().map(Migration::key).toList();
        assertThat(currentMaster.stream().filter(row -> masterKeys.contains(row.key())).toList())
                .as("frozen historical master identities, SQL checksums and order")
                .containsExactlyElementsOf(FROZEN.master());
        assertThat(currentMaster.stream().map(Migration::key).toList()).doesNotHaveDuplicates();
        for (Migration row : currentMaster) {
            if (historical.containsKey(row.key())) {
                assertThat(row).as("preserve applied checksum for %s", row.key()).isEqualTo(historical.get(row.key()));
            }
        }
        List<Migration> seeded = new ArrayList<>(FROZEN.all());
        currentMaster.stream().filter(row -> !historical.containsKey(row.key())).forEach(seeded::add);
        return List.copyOf(seeded);
    }
}
