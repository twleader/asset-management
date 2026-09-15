package com.steven.assets.service;

import com.steven.assets.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the Taiwan master boundary against a future shortcut back to user-payload SQL writes. */
class StockMasterWriteBoundaryArchitectureTest {
    @Test
    void onlyThePackagePrivatePersistenceAdapterContainsWritableStockSql() throws IOException {
        List<Path> sources;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(path -> path.toString().endsWith(".java")).toList();
        }

        List<Path> directWrites = sources.stream()
                .filter(path -> containsStockWrite(path))
                .toList();

        assertThat(directWrites).containsExactly(Path.of("src/main/java/com/steven/assets/service/StockMasterPersistence.java"));
        assertThat(StockMasterPersistence.class.getModifiers() & java.lang.reflect.Modifier.PUBLIC).isZero();
        assertThat(Repository.class.isAssignableFrom(StockRepository.class)).isTrue();
        assertThat(StockRepository.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("save", "delete", "deleteAll");
    }

    @Test
    void servicesMayQueryButNeverCallRepositoryMutationOrEmbedStockWriteSql() throws IOException {
        List<Path> services;
        try (var files = Files.walk(Path.of("src/main/java/com/steven/assets/service"))) {
            services = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("StockMasterPersistence.java")).toList();
        }
        String all = services.stream().map(StockMasterWriteBoundaryArchitectureTest::read).reduce("", String::concat);

        assertThat(all).doesNotContain("stockMasterRepo.save(", "stockMasterRepo.delete(",
                "INSERT INTO stock", "UPDATE stock ", "DELETE FROM stock");
    }

    @Test
    void mutableAdapterIsReferencedOnlyByItselfAndTheMasterService() throws IOException {
        List<Path> sources;
        try (var files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> read(path).contains("StockMasterPersistence")).toList();
        }

        assertThat(sources).containsExactlyInAnyOrder(
                Path.of("src/main/java/com/steven/assets/service/StockMasterPersistence.java"),
                Path.of("src/main/java/com/steven/assets/service/StockMasterService.java"));
    }

    private static boolean containsStockWrite(Path path) {
        return Pattern.compile("(?s)\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+stock\\b", Pattern.CASE_INSENSITIVE)
                .matcher(read(path)).find();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
