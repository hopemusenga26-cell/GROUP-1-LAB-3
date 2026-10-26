package lodgebilling.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.stage.Window;
import lodgebilling.model.Bill;
import lodgebilling.model.Money;
import lodgebilling.model.Theme;

import java.time.format.DateTimeFormatter;
import java.util.Random;

/** A bill drawn like a till receipt: torn paper edges, dotted rules, itemised lines and a barcode. */
public class ReceiptView extends VBox {

    private static final double PAPER_WIDTH = 340;
    private static final double TOOTH = 10;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy  HH:mm");

    public ReceiptView(Bill bill) {
        super(0);
        setMaxWidth(PAPER_WIDTH);
        setPrefWidth(PAPER_WIDTH);
        setEffect(new DropShadow(14, 0, 4, Color.rgb(0, 0, 0, 0.35)));

        VBox body = new VBox(6);
        body.getStyleClass().add("receipt-paper");
        body.setPadding(new Insets(10, 24, 10, 24));
        body.getChildren().addAll(
                header(),
                dottedRule(),
                infoRow("Receipt no.", bill.getReceiptNumber()),
                infoRow("Date", bill.getIssuedAt().format(DATE_FORMAT)),
                infoRow("Guest", bill.getGuestName()),
                infoRow("Served by", bill.getCreatedBy()),
                dottedRule(),
                charges(bill),
                solidRule(),
                totals(bill),
                solidRule());

        if (bill.getNotes() != null && !bill.getNotes().isBlank()) {
            Label notes = text("Notes: " + bill.getNotes().trim(), "receipt-small");
            notes.setWrapText(true);
            body.getChildren().addAll(notes, dottedRule());
        }

        body.getChildren().addAll(statusStamp(bill), footer(), barcode(bill));

        getChildren().addAll(edge(true), body, edge(false));
    }

    /** Sends a receipt to a printer the user picks. Returns false if cancelled or it failed. */
    public static boolean print(Window owner, Theme theme, Bill bill) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            Ui.info(owner, theme, "Could not print", "No printer is available.");
            return false;
        }
        try {
            if (!job.showPrintDialog(owner)) {
                return false;
            }
            ReceiptView receipt = new ReceiptView(bill);
            receipt.setEffect(null);   // the on-screen drop shadow would print as a grey smudge
            // A scene is needed for the CSS to apply. It is never shown, so it has no size:
            // a StackPane here would be 0 x 0 and centre the receipt on its corner, pushing
            // half of it off the page. A Group never resizes or centres its children.
            Scene scene = new Scene(new Group(receipt));
            scene.getStylesheets().add(Styles.stylesheet());
            receipt.applyCss();
            receipt.autosize();        // its preferred size, about 340 x 584
            receipt.layout();

            // Shrink the receipt to fit the printable area if it is too wide or too tall
            PageLayout page = job.getJobSettings().getPageLayout();
            double scale = Math.min(page.getPrintableWidth() / receipt.getWidth(),
                    page.getPrintableHeight() / receipt.getHeight());
            if (scale > 0 && scale < 1) {
                receipt.getTransforms().add(new Scale(scale, scale));
            }

            if (job.printPage(receipt)) {
                job.endJob();
                return true;
            }
            Ui.info(owner, theme, "Could not print", "Printing failed.");
            return false;
        } finally {
            if (job.getJobStatus() == PrinterJob.JobStatus.PRINTING
                    || job.getJobStatus() == PrinterJob.JobStatus.NOT_STARTED) {
                job.cancelJob();
            }
        }
    }

    // ---------- Sections ----------

    private Node header() {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(8, 0, 4, 0));
        box.getChildren().addAll(
                text("LODGE BILLING", "receipt-title"),
                text("GUEST RECEIPT", "receipt-small"));
        return box;
    }

    private Node charges(Bill bill) {
        VBox box = new VBox(4);
        box.getChildren().add(text(bill.getRoomType() + " room", "receipt-text"));
        box.getChildren().add(line("  " + bill.getNights() + " night(s) x "
                + Money.format(bill.getNightlyRate()), Money.format(bill.getRoomCharge())));
        if (bill.getDiscount().signum() > 0) {
            box.getChildren().add(line("  Discount (" + Bill.discountPercentLabel() + ")", "- " + Money.format(bill.getDiscount())));
        }
        if (bill.getExtras().signum() > 0) {
            box.getChildren().add(line("Extras", Money.format(bill.getExtras())));
        }
        if (bill.getVat().signum() > 0) {
            box.getChildren().add(line("VAT (" + bill.getVatPercent().stripTrailingZeros().toPlainString() + "%)",
                    Money.format(bill.getVat())));
        }
        return box;
    }

    private Node totals(Bill bill) {
        Node total = line("TOTAL", Money.format(bill.getTotal()));
        total.getStyleClass().add("receipt-total");
        Node balance = line("BALANCE DUE", Money.format(bill.getBalance()));
        balance.getStyleClass().add("receipt-total");
        VBox box = new VBox(4, total, line("Amount paid", Money.format(bill.getPaid())), balance);
        return box;
    }

    private Node statusStamp(Bill bill) {
        boolean paidInFull = bill.getBalance().signum() == 0;
        Label stamp = text(paidInFull ? "PAID IN FULL" : "PAYMENT PENDING", "receipt-stamp");
        stamp.getStyleClass().add(paidInFull ? "receipt-stamp-paid" : "receipt-stamp-due");
        VBox box = new VBox(stamp);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(8, 0, 4, 0));
        return box;
    }

    private Node footer() {
        VBox box = new VBox(2);
        box.setAlignment(Pos.CENTER);
        box.getChildren().addAll(
                text("THANK YOU FOR STAYING", "receipt-text"),
                text("WITH US. COME AGAIN!", "receipt-text"));
        return box;
    }

    /** Decorative barcode: the same bill number always gives the same bars. */
    private Node barcode(Bill bill) {
        Random random = new Random(bill.getReceiptNumber().hashCode());
        HBox bars = new HBox(2);
        bars.setAlignment(Pos.CENTER);
        for (int i = 0; i < 38; i++) {
            Rectangle bar = new Rectangle(1 + random.nextInt(3), 44);
            bar.setFill(Color.rgb(25, 25, 25));
            bars.getChildren().add(bar);
        }
        VBox box = new VBox(4, bars, text(bill.getReceiptNumber(), "receipt-small"));
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(10, 0, 6, 0));
        return box;
    }

    // ---------- Building blocks ----------

    private static Label text(String content, String styleClass) {
        Label label = new Label(content);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static HBox infoRow(String name, String value) {
        return line(name, value);
    }

    /** A left label and a right-aligned value, like a till receipt line. */
    private static HBox line(String left, String right) {
        Label name = text(left, "receipt-text");
        Label value = text(right, "receipt-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, name, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region dottedRule() {
        Region rule = new Region();
        rule.getStyleClass().add("receipt-dotted");
        rule.setMinHeight(1);
        rule.setPrefHeight(1);
        VBox.setMargin(rule, new Insets(4, 0, 4, 0));
        return rule;
    }

    private static Region solidRule() {
        Region rule = new Region();
        rule.getStyleClass().add("receipt-solid");
        rule.setMinHeight(2);
        rule.setPrefHeight(2);
        VBox.setMargin(rule, new Insets(4, 0, 4, 0));
        return rule;
    }

    /** A row of paper-coloured teeth for the torn top or bottom edge. */
    private static Polygon edge(boolean top) {
        Polygon teeth = new Polygon();
        double flat = top ? TOOTH : 0;     // the straight side touching the paper body
        double tip = top ? 0 : TOOTH;      // the pointed side
        for (double x = 0; x < PAPER_WIDTH; x += TOOTH * 2) {
            teeth.getPoints().addAll(x, flat, x + TOOTH, tip);
        }
        teeth.getPoints().addAll(PAPER_WIDTH, flat);
        teeth.getStyleClass().add("receipt-edge");
        return teeth;
    }
}
