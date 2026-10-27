package lodgebilling.controller;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes RFC 4180 CSV: fields with commas, quotes or line breaks are quoted,
 * and quotes inside are doubled.
 *
 * It also blocks "CSV injection": a guest named =HYPERLINK(...) would run as a
 * formula when the report is opened in Excel, so text starting with = + - @ is
 * prefixed with an apostrophe. Numbers are written as plain numbers.
 */
public final class CsvWriter {

    private CsvWriter() { }

    /** For opening in Excel: starts with a byte-order mark so Excel reads UTF-8 correctly. */
    public static void write(Path file, List<String> header, List<List<Object>> rows) throws IOException {
        write(file, header, rows, true);
    }

    /**
     * For scripts and databases (pandas, DuckDB, COPY): plain UTF-8 without the byte-order mark,
     * which some tools would otherwise read as part of the first column's name.
     */
    public static void writeForAnalysis(Path file, List<String> header, List<List<Object>> rows) throws IOException {
        write(file, header, rows, false);
    }

    private static void write(Path file, List<String> header, List<List<Object>> rows, boolean excelBom)
            throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            if (excelBom) {
                w.write('\uFEFF');   // so Excel reads UTF-8 names (e.g. Mwansa, Ndhlovu, Zoë) correctly
            }
            writeLine(w, header.stream().map(h -> (Object) h).toList());
            for (List<Object> row : rows) {
                writeLine(w, row);
            }
        }
    }

    private static void writeLine(Writer w, List<Object> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) w.write(',');
            w.write(escape(fields.get(i)));
        }
        w.write("\r\n");
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return value instanceof java.math.BigDecimal bd ? bd.toPlainString() : value.toString();
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
