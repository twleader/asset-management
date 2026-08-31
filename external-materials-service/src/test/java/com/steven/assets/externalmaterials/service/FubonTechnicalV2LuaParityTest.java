package com.steven.assets.externalmaterials.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The scheduler and business cache adapters execute the same v2 pair fence. */
class FubonTechnicalV2LuaParityTest {

    private static final Path LUA = Path.of("src/main/resources/redis/fubon-technical-v2-pair-write.lua");

    @Test
    void businessAndExternalAdaptersShipByteIdenticalPairFence() throws IOException {
        Path root = repositoryRoot();

        assertThat(Files.readString(root.resolve("backend").resolve(LUA)))
                .isEqualTo(Files.readString(root.resolve("external-materials-service").resolve(LUA)));
    }

    private static Path repositoryRoot() {
        for (Path cursor = Path.of("").toAbsolutePath(); cursor != null; cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("backend").resolve(LUA))
                    && Files.isRegularFile(cursor.resolve("external-materials-service").resolve(LUA))) {
                return cursor;
            }
        }
        throw new IllegalStateException("asset-management repository root not found");
    }
}
