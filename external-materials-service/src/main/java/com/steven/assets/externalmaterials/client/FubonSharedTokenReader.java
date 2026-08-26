package com.steven.assets.externalmaterials.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Small lazy reader for the shared internal token.  It never logs a token or its path content. */
final class FubonSharedTokenReader {

    private FubonSharedTokenReader() {
    }

    static String read(String tokenPath) {
        try {
            Path path = Path.of(tokenPath == null ? "" : tokenPath);
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null;
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (InvalidPathException | java.io.IOException | SecurityException invalid) {
            return null;
        }
    }
}
