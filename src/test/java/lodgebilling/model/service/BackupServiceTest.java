package lodgebilling.model.service;

import lodgebilling.dao.Database;
import lodgebilling.dao.SqliteAuditLogDao;
import lodgebilling.model.RoomType;
import lodgebilling.model.User;
import lodgebilling.model.security.AccessDeniedException;
import lodgebilling.model.security.Role;
import lodgebilling.model.security.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Daily copies, pruning, and restoring on the next start. */
class BackupServiceTest {

    private static final Session ADMIN = new Session(new User(1, "boss", "Boss", Role.ADMIN, true, false));
    private static final Session DESK = new Session(new User(2, "mary", "Mary", Role.STAFF, true, false));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T07:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private BackupService backups(Database db, Path file) {
        return new BackupService(db, file, new SqliteAuditLogDao(db, CLOCK), CLOCK);
    }

    @Test
    void theFirstStartOfTheDayMakesOneCopy() {
        Path file = dir.resolve("lodgebilling.db");
        try (Database db = Database.open(file)) {
            BackupService backups = backups(db, file);
            Optional<Path> made = backups.dailyBackup();
            assertTrue(made.isPresent());
            assertEquals("lodgebilling-2026-10-26.db", made.get().getFileName().toString());
            assertTrue(Files.isRegularFile(made.get()));
            assertTrue(backups.dailyBackup().isEmpty());   // the same day again: nothing new
        }
    }

    @Test
    void onlyTheNewestCopiesAreKept() throws Exception {
        Path file = dir.resolve("lodgebilling.db");
        Path folder = dir.resolve("backups");
        Files.createDirectories(folder);
        for (int day = 1; day <= BackupService.KEEP + 5; day++) {
            Path old = folder.resolve("lodgebilling-old-" + day + ".db");
            Files.writeString(old, "x");
            Files.setLastModifiedTime(old, FileTime.fromMillis(day * 1000L));
        }
        try (Database db = Database.open(file)) {
            backups(db, file).dailyBackup();
        }
        try (var files = Files.list(folder)) {
            assertEquals(BackupService.KEEP, files.count());
        }
        assertTrue(Files.exists(folder.resolve("lodgebilling-2026-10-26.db")));
        assertTrue(Files.notExists(folder.resolve("lodgebilling-old-1.db")));   // the oldest went first
    }

    @Test
    void aRestoreIsSwappedInOnTheNextStartAndTheOldDataIsKept() throws Exception {
        Path file = dir.resolve("lodgebilling.db");
        Path copy;
        try (Database db = Database.open(file)) {
            LodgeService lodge = LodgeServiceTest.lodgeService(db, CLOCK);
            BackupService backups = backups(db, file);
            copy = backups.backupNow(ADMIN);              // nine rooms
            lodge.addRoom(ADMIN, "401", RoomType.FAMILY);  // then a tenth
            assertThrows(AccessDeniedException.class, () -> backups.restoreOnNextStart(DESK, copy));
            backups.restoreOnNextStart(ADMIN, copy);
        }

        Optional<String> restored = BackupService.applyPendingRestore(file, CLOCK);
        assertTrue(restored.orElseThrow().contains("boss"));
        try (Database db = Database.open(file)) {
            assertEquals(9, LodgeServiceTest.lodgeService(db, CLOCK).rooms().size());
        }
        // The data it replaced (with room 401) is kept beside the backups
        try (var files = Files.list(dir.resolve("backups"))) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("before-restore-")));
        }
        assertTrue(BackupService.applyPendingRestore(file, CLOCK).isEmpty());   // done once only
    }
}
