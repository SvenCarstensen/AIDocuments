package de.aidocuments.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reste im tmp-Ordner (halb empfangene Dateien, Momentaufnahmen) werden beim Start weggeräumt. */
class FileStorageLeftoversTest {

    @TempDir
    Path dir;

    @Test
    void removesOldEntriesButKeepsRecentOnesAndLinkTargets() throws Exception {
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Instant old = Instant.now().minus(Duration.ofHours(3));
        Path oldFile = Files.writeString(tmp.resolve("upload-alt.part"), "halb");
        Path oldDirectory = Files.createDirectories(tmp.resolve("snapshot-alt"));
        Files.writeString(oldDirectory.resolve("documents.db"), "kopie");
        Path recent = Files.writeString(tmp.resolve("upload-neu.part"), "läuft gerade");
        // ein Link in einem alten Ordner: entfernt wird der Link, nicht das, worauf er zeigt
        Path outside = Files.createDirectories(dir.resolve("woanders"));
        Files.writeString(outside.resolve("wichtig.txt"), "bleibt");
        Path link = Files.createSymbolicLink(oldDirectory.resolve("link"), outside);
        for (Path path : new Path[] {oldFile, oldDirectory.resolve("documents.db"), oldDirectory}) {
            Files.setLastModifiedTime(path, FileTime.from(old));
        }

        FileStorage.removeLeftovers(tmp, Instant.now().minus(FileStorage.LEFTOVER_AGE));

        assertThat(oldFile).doesNotExist();
        assertThat(oldDirectory).doesNotExist();
        assertThat(recent).exists();
        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(outside.resolve("wichtig.txt")).hasContent("bleibt");
    }
}
