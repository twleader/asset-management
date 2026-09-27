package com.steven.assets.service;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;

/** Compares local export names and targets using the actual mounted filesystem semantics. */
final class LocalExportPathComparator {
    private LocalExportPathComparator() {}

    interface FileSystemProbe {
        EntryIdentity noFollowIdentity(Path path) throws IOException;
        Object fileStore(Path path) throws IOException;
        List<Path> children(Path directory) throws IOException;
    }

    record EntryIdentity(boolean symbolicLink, Object fileKey) {}

    private static final FileSystemProbe FILES = new FileSystemProbe() {
        @Override public EntryIdentity noFollowIdentity(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new EntryIdentity(attributes.isSymbolicLink(), attributes.fileKey());
        }
        @Override public Object fileStore(Path path) throws IOException { return Files.getFileStore(path); }
        @Override public List<Path> children(Path directory) throws IOException {
            List<Path> paths = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                entries.forEach(paths::add);
            }
            return paths;
        }
    };

    static boolean sameName(String left, String right, boolean caseInsensitiveVolume) {
        if (Objects.equals(left, right)) return true;
        return left != null && right != null && caseInsensitiveVolume && left.equalsIgnoreCase(right);
    }

    static boolean sameTarget(Path left, Path right, boolean caseInsensitiveVolume) {
        Path normalizedLeft = left.toAbsolutePath().normalize();
        Path normalizedRight = right.toAbsolutePath().normalize();
        if (normalizedLeft.equals(normalizedRight)) return true;
        return caseInsensitiveVolume && normalizedLeft.toString().equalsIgnoreCase(normalizedRight.toString());
    }

    /** Resolves every existing symlink in a path while preserving any not-yet-created suffix. */
    static Path resolveExistingAliases(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        List<Path> missingComponents = new ArrayList<>();
        Path existingPrefix = normalized;
        while (existingPrefix != null && !Files.exists(existingPrefix, LinkOption.NOFOLLOW_LINKS)) {
            Path component = existingPrefix.getFileName();
            if (component != null) missingComponents.add(component);
            existingPrefix = existingPrefix.getParent();
        }
        if (existingPrefix == null) throw new IOException("找不到可解析的既存路徑前綴：" + path);

        Path resolved = existingPrefix.toRealPath();
        for (int index = missingComponents.size() - 1; index >= 0; index--) {
            resolved = resolved.resolve(missingComponents.get(index));
        }
        return resolved.normalize();
    }

    /**
     * Targets are always below one configured output base. Their nearest existing ancestors still reveal the
     * mounted volume when the actual candidate directories have not been created yet.
     */
    static boolean caseInsensitiveVolume(Path firstTarget, Path secondTarget) {
        Path firstAncestor = nearestExistingAncestor(firstTarget);
        Path secondAncestor = nearestExistingAncestor(secondTarget);
        if (firstAncestor == null || secondAncestor == null) return false;
        try {
            if (!Files.isSameFile(firstAncestor, secondAncestor)) return false;
        } catch (IOException | SecurityException ignored) {
            return false;
        }
        return hasCaseInsensitiveComponent(firstAncestor) || hasCaseInsensitiveComponent(secondAncestor);
    }

    private static Path nearestExistingAncestor(Path target) {
        Path current = target.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current)) return current;
            current = current.getParent();
        }
        return null;
    }

    static boolean hasCaseInsensitiveComponent(Path existingAncestor) {
        return hasCaseInsensitiveComponent(existingAncestor, FILES);
    }

    static boolean hasCaseInsensitiveComponent(Path existingAncestor, FileSystemProbe probe) {
        for (Path directory = existingAncestor; directory != null; directory = directory.getParent()) {
            if (hasCaseInsensitiveChild(directory, probe)) return true;

            Path parent = directory.getParent();
            if (parent == null) break;
            try {
                // Do not infer this target volume's behavior from an ancestor across a mount boundary.
                if (!Objects.equals(probe.fileStore(directory), probe.fileStore(parent))) break;
            } catch (IOException | SecurityException ignored) {
                // If the boundary cannot be determined, stop rather than probing a possibly different volume.
                break;
            }
        }
        return false;
    }

    private static boolean hasCaseInsensitiveChild(Path directory, FileSystemProbe probe) {
        try {
            List<Path> children = probe.children(directory);
            for (Path child : children) {
                Path filename = child.getFileName();
                if (filename == null) continue;
                String value = filename.toString();
                String alternate = value.toUpperCase(Locale.ROOT);
                if (alternate.equals(value)) alternate = value.toLowerCase(Locale.ROOT);
                if (alternate.equals(value)) continue;

                // Two differently-cased directory entries prove this directory is ambiguous on a
                // case-sensitive filesystem, even if those entries happen to be hard links.
                boolean ambiguous = children.stream()
                        .filter(other -> !other.equals(child))
                        .map(Path::getFileName)
                        .filter(Objects::nonNull)
                        .map(Path::toString)
                        .anyMatch(otherName -> otherName.equalsIgnoreCase(value));
                if (ambiguous) continue;

                Path sibling = child.resolveSibling(alternate);
                try {
                    EntryIdentity original = probe.noFollowIdentity(child);
                    EntryIdentity alternateEntry = probe.noFollowIdentity(sibling);
                    // A symlink target can resolve to the original entry on a case-sensitive volume.
                    // Require stable, no-follow filesystem identity; unknown identity is inconclusive.
                    if (!original.symbolicLink() && !alternateEntry.symbolicLink()
                            && original.fileKey() != null
                            && Objects.equals(original.fileKey(), alternateEntry.fileKey())) return true;
                } catch (NoSuchFileException ignored) {
                    // On a case-sensitive filesystem the swapped spelling normally has no entry.
                }
            }
        } catch (IOException | SecurityException ignored) {
            // An unreadable directory does not justify assuming case-insensitive behavior.
        }
        return false;
    }
}
