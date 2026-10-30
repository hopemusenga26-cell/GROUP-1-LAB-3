package lodgebilling.model;

import java.util.List;

/** One file of the data export: its name (without .csv), the column names and the rows. */
public record ExportTable(String name, List<String> header, List<List<Object>> rows) {

    public ExportTable {
        for (List<Object> row : rows) {
            if (row.size() != header.size()) {
                throw new IllegalArgumentException(name + ": a row has " + row.size() + " values for "
                        + header.size() + " columns");
            }
        }
    }
}
