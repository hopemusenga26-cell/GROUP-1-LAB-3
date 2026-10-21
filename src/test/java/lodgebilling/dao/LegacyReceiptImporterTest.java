package lodgebilling.dao;

import lodgebilling.model.Bill;
import lodgebilling.model.BillInput;
import lodgebilling.model.RoomType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyReceiptImporterTest {

    @TempDir
    Path dir;

    private static final String GOOD_1 =
            "R001\t2026-10-01T09:30\tJohn Phiri\tDELUXE\t5\t200\t1000\tLate check-out";
    private static final String GOOD_2 =
            "R004\t2026-10-02T14:05\tMary\\tBanda\tSTANDARD\t2\t0\t700\tline one\\nline two";
    private static final String DAMAGED = "R005\tnot-a-date\tBroken Line\tSTANDARD\t1\t0\t0\t";

    @Test
    void copiesReceiptsKeepingTheirNumbersAndRenamesTheFile() throws Exception {
        Path file = write(GOOD_1, GOOD_2, DAMAGED, "");
        try (Database db = Database.inMemory()) {
            AuditLogDao audit = new SqliteAuditLogDao(db, Clock.systemUTC());

            LegacyReceiptImporter.Result result = LegacyReceiptImporter.importOnce(db, audit, file);

            assertEquals(new LegacyReceiptImporter.Result(2, 1, 0), result);
            List<Bill> bills = new SqliteBillDao(db).findAll();
            assertEquals("R001", bills.get(0).getReceiptNumber());
            assertEquals(new BigDecimal("2812.50"), bills.get(0).getTotal());   // 5 x 550 - 5% + 200
            assertEquals("R004", bills.get(1).getReceiptNumber());
            assertEquals("Mary\tBanda", bills.get(1).getGuestName());
            assertEquals("line one\nline two", bills.get(1).getNotes());
            assertFalse(Files.exists(file), "the old file is renamed so it is never imported twice");
            assertTrue(Files.exists(file.resolveSibling("receipts.tsv.imported")));
        }
    }

    @Test
    void newReceiptsCarryOnAfterTheHighestImportedNumber() throws Exception {
        Path file = write(GOOD_1, GOOD_2);
        try (Database db = Database.inMemory()) {
            LegacyReceiptImporter.importOnce(db, new SqliteAuditLogDao(db, Clock.systemUTC()), file);

            Bill next = new SqliteBillDao(db).insert(input("New Guest"), LocalDateTime.now(), "sammy");

            assertEquals("R005", next.getReceiptNumber());
        }
    }

    @Test
    void aTakenNumberGetsTheNextFreeOneAndKeepsTheOldNumberInTheNotes() throws Exception {
        Path file = write(GOOD_1);
        try (Database db = Database.inMemory()) {
            new SqliteBillDao(db).insert(input("Already Here"), LocalDateTime.now(), "sammy");   // takes R001

            LegacyReceiptImporter.Result result =
                    LegacyReceiptImporter.importOnce(db, new SqliteAuditLogDao(db, Clock.systemUTC()), file);

            assertEquals(1, result.renumbered());
            Bill imported = new SqliteBillDao(db).findAll().get(1);
            assertEquals("R002", imported.getReceiptNumber());
            assertTrue(imported.getNotes().endsWith("[was R001]"));
        }
    }

    @Test
    void noFileMeansNothingToDo() {
        try (Database db = Database.inMemory()) {
            LegacyReceiptImporter.Result result = LegacyReceiptImporter.importOnce(
                    db, new SqliteAuditLogDao(db, Clock.systemUTC()), dir.resolve("receipts.tsv"));
            assertEquals(0, result.imported());
        }
    }

    private Path write(String... lines) throws Exception {
        Path file = dir.resolve("receipts.tsv");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private static BillInput input(String name) {
        return new BillInput(name, RoomType.STANDARD, RoomType.STANDARD.getNightlyRate(), 1,
                BigDecimal.ZERO, BigDecimal.ZERO, "");
    }
}
