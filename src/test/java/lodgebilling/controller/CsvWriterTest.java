package lodgebilling.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvWriterTest {

    @Test
    void quotesCommasAndQuotes() {
        assertEquals("\"Banda, Mwila\"", CsvWriter.escape("Banda, Mwila"));
        assertEquals("\"The \"\"Big\"\" Lodge\"", CsvWriter.escape("The \"Big\" Lodge"));
        assertEquals("O'Neil", CsvWriter.escape("O'Neil"));
    }

    @Test
    void blocksSpreadsheetFormulaInjection() {
        // Leading = gets an apostrophe; the quotes inside are then doubled and the field quoted
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CsvWriter.escape("=HYPERLINK(\"x\")"));
        assertTrue(CsvWriter.escape("+260971234567").startsWith("'"));
        assertEquals("-5.50", CsvWriter.escape(new BigDecimal("-5.50")));   // real numbers stay numbers
    }

    @Test
    void writesHeaderAndRows(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("r.csv");
        CsvWriter.write(f, List.of("guest", "owes_k"), List.of(List.of("Mwila Banda", new BigDecimal("166.80"))));
        String text = Files.readString(f, StandardCharsets.UTF_8);
        assertEquals("﻿guest,owes_k\r\nMwila Banda,166.80\r\n", text);
    }
}
