package lodgebilling.model.service;

import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.DataException;
import lodgebilling.dao.Database;
import lodgebilling.model.AuditAction;
import lodgebilling.model.security.Permission;
import lodgebilling.model.security.Session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Copies of the database in data/backups: one made automatically on the first start of
 * each day, and any made by hand. Only the newest {@link #KEEP} are kept.
 *
 * A restore cannot replace the file the app has open, so it is done in two steps: the
 * chosen backup is put next to the database as restore-pending.db, and the next start
 * swaps it in before opening the database. The data it replaces is kept in the backups
 * folder as before-restore-....db, so a restore can itself be undone.
 */
public class BackupService {

    public static final int KEEP = 30;
    private static final String PREFIX = "lodgebilling-";
    private static final String PENDING = "restore-pending.db";
    private static final String PENDING_NOTE = "restore-pending.txt";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");

    private final Database database;
    private final Path databaseFile;
    private final AuditLogDao audit;
    private final Clock clock;

    public BackupService(Database database, Path databaseFile, AuditLogDao audit, Clock clock) {
        this.database = database;
        this.databaseFile = databaseFile;
        this.audit = audit;
        this.clock = clock;
    }

    public Path folder() {
        return folderFor(databaseFile);
    }

    private static Path folderFor(Path databaseFile) {
        return databaseFile.toAbsolutePath().getParent().resolve("backups");
    }

    /** The first start of the day makes a copy; later starts that day do nothing. Returns the new copy, if any. */
    public Optional<Path> dailyBackup() {
        Path today = folder().resolve(PREFIX + LocalDateTime.now(clock).format(DAY) + ".db");
        if (Files.exists(today)) {
            return Optional.empty();
        }
        copy(today);
        return Optional.of(today);
    }

    /** A copy made on request, e.g. before a big change. */
    public Path backupNow(Session session) {
        session.require(Permission.MANAGE_BACKUPS);
        Path file = folder().resolve(PREFIX + LocalDateTime.now(clock).format(MINUTE) + ".db");
        copy(file);
        audit.record(session.username(), AuditAction.BACKUP_MADE, file.getFileName().toString());
        return file;
    }

    private void copy(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(file);   // VACUUM INTO refuses to overwrite
        } catch (IOException e) {
            throw new DataException("Could not prepare the backups folder " + file.getParent(), e);
        }
        database.copyTo(file);
        prune();
    }

    /** Every backup, newest first (including the copies kept from before a restore). */
    public List<Path> backups() {
        if (!Files.isDirectory(folder())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder())) {
            return files.filter(f -> f.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparing(BackupService::modified).reversed())
                    .toList();
        } catch (IOException e) {
            throw new DataException("Could not list the backups in " + folder(), e);
        }
    }

    /** Deletes the oldest copies beyond KEEP. Copies kept from before a restore are never deleted. */
    private void prune() {
        List<Path> ours = backups().stream().filter(f -> f.getFileName().toString().startsWith(PREFIX)).toList();
        for (Path old : ours.subList(Math.min(KEEP, ours.size()), ours.size())) {
            try {
                Files.deleteIfExists(old);
            } catch (IOException e) {
                throw new DataException("Could not delete the old backup " + old, e);
            }
        }
    }

    private static java.nio.file.attribute.FileTime modified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    /** Puts a backup in place to be restored on the next start of the app. */
    public void restoreOnNextStart(Session session, Path backup) throws LodgeException {
        session.require(Permission.MANAGE_BACKUPS);
        if (backup == null || !Files.isRegularFile(backup)) {
            throw new LodgeException("Choose a backup to restore.");
        }
        Path folder = databaseFile.toAbsolutePath().getParent();
        try {
            Files.copy(backup, folder.resolve(PENDING), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(folder.resolve(PENDING_NOTE),
                    session.username() + "\n" + backup.getFileName() + "\n");
        } catch (IOException e) {
            throw new DataException("Could not prepare the restore from " + backup, e);
        }
    }

    /**
     * Call before opening the database: swaps in a backup chosen for restoring, if there is one.
     *
     * @return who asked for it and from which file, to record once the database is open
     */
    public static Optional<String> applyPendingRestore(Path databaseFile, Clock clock) {
        Path folder = databaseFile.toAbsolutePath().getParent();
        Path pending = folder.resolve(PENDING);
        if (!Files.isRegularFile(pending)) {
            return Optional.empty();
        }
        try {
            Path note = folder.resolve(PENDING_NOTE);
            List<String> lines = Files.isRegularFile(note) ? Files.readAllLines(note) : List.of();
            if (Files.exists(databaseFile)) {
                Path kept = folderFor(databaseFile).resolve("before-restore-"
                        + LocalDateTime.now(clock).format(MINUTE) + ".db");
                Files.createDirectories(kept.getParent());
                Files.move(databaseFile, kept, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(pending, databaseFile);
            Files.deleteIfExists(note);
            String who = lines.isEmpty() ? "an admin" : lines.get(0);
            String from = lines.size() < 2 ? "a backup" : lines.get(1);
            return Optional.of(who + " restored " + from);
        } catch (IOException e) {
            throw new DataException("Could not restore the backup waiting in " + pending, e);
        }
    }

    /** Records a restore that was applied at start-up, in the restored database. */
    public void recordRestore(String what) {
        audit.record("system", AuditAction.BACKUP_RESTORED, what);
    }
}
