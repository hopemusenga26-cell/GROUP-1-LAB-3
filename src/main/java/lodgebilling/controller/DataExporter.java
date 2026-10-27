package lodgebilling.controller;

import lodgebilling.model.ExportTable;
import lodgebilling.model.service.LodgeService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes the data export to disk: one folder per export, named by date and time, holding a
 * CSV file per table and a manifest.json saying when it was made, in which format version,
 * and how many rows each file has. A script that loads the files can check the manifest
 * first, so a half-copied or older-format export is noticed instead of silently analysed.
 */
public final class DataExporter {

    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    private DataExporter() { }

    /** Writes a new export folder inside parent and returns it. */
    public static Path export(List<ExportTable> tables, Path parent, LocalDateTime now) throws IOException {
        Path folder = parent.resolve("lodge-export-" + now.format(FOLDER));
        Files.createDirectories(folder);
        StringBuilder files = new StringBuilder();
        for (ExportTable t : tables) {
            CsvWriter.writeForAnalysis(folder.resolve(t.name() + ".csv"), t.header(), t.rows());
            if (!files.isEmpty()) {
                files.append(",\n");
            }
            files.append("    {\"file\": \"").append(t.name()).append(".csv\", \"rows\": ").append(t.rows().size())
                    .append(", \"columns\": [");
            for (int i = 0; i < t.header().size(); i++) {
                files.append(i == 0 ? "" : ", ").append('"').append(t.header().get(i)).append('"');
            }
            files.append("]}");
        }
        // Written last: if the manifest is there, every CSV before it was written completely
        String manifest = "{\n"
                + "  \"exported_at\": \"" + now + "\",\n"
                + "  \"format_version\": " + LodgeService.EXPORT_FORMAT_VERSION + ",\n"
                + "  \"money\": \"Zambian kwacha, plain decimals\",\n"
                + "  \"files\": [\n" + files + "\n  ]\n"
                + "}\n";
        Files.writeString(folder.resolve("manifest.json"), manifest, StandardCharsets.UTF_8);
        return folder;
    }
}
