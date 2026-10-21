package lodgebilling.view;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.print.PageLayout;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.AuditEntry;
import lodgebilling.model.Bill;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Money;

import java.util.List;
import java.util.Optional;

/** The pop-up windows (receipt, delete confirmation, about), all owned by the main window. */
public class Dialogs {

    private final Stage owner;
    private final BillingModel model;
    private final BillingActions actions;

    public Dialogs(Stage owner, BillingModel model, BillingActions actions) {
        this.owner = owner;
        this.model = model;
        this.actions = actions;
    }

    public void showReceipt(Bill bill) {
        StackPane paper = new StackPane(new ReceiptView(bill));
        paper.setPadding(new Insets(10, 20, 20, 20));
        ScrollPane scroll = new ScrollPane(paper);
        scroll.getStyleClass().add("receipt-scroll");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(400);
        scroll.setPrefViewportHeight(600);

        ButtonType print = new ButtonType("Print receipt", ButtonBar.ButtonData.LEFT);
        Alert dialog = new Alert(Alert.AlertType.INFORMATION, "", print, ButtonType.CLOSE);
        dialog.setTitle("Receipt " + bill.getReceiptNumber());
        dialog.setHeaderText(null);
        dialog.setGraphic(null);
        dialog.getDialogPane().setContent(scroll);

        // Keep the dialog open after printing so the receipt can be checked or printed again.
        // Printing goes through the controller, like the toolbar button, so the status bar
        // reports the result
        Button printButton = (Button) dialog.getDialogPane().lookupButton(print);
        printButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            actions.printReceiptFor(bill);
        });
        show(dialog);
    }

    /** Sends the receipt to a printer the user picks. Returns false if cancelled or it failed. */
    public boolean printReceipt(Bill bill) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            return fail("No printer is available.");
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
            receipt.autosize();        // its preferred size, 340 x 584
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
            return fail("Printing failed.");
        } finally {
            if (job.getJobStatus() == PrinterJob.JobStatus.PRINTING
                    || job.getJobStatus() == PrinterJob.JobStatus.NOT_STARTED) {
                job.cancelJob();
            }
        }
    }

    private boolean fail(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText("Could not print");
        show(alert);
        return false;
    }

    /** Asks the user to confirm; true only if they chose Delete. */
    public boolean confirmDelete(Bill bill) {
        ButtonType delete = new ButtonType("Delete");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete receipt " + bill.getReceiptNumber() + " for " + bill.getGuestName()
                        + " (" + Money.format(bill.getTotal()) + ")?",
                delete, ButtonType.CANCEL);
        confirm.setTitle("Confirm deletion");
        confirm.setHeaderText("Delete the selected receipt?");

        // Make Cancel the safe default: Enter or a stray key press keeps the bill
        Button deleteButton = (Button) confirm.getDialogPane().lookupButton(delete);
        Button cancelButton = (Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL);
        deleteButton.setDefaultButton(false);
        deleteButton.getStyleClass().add("danger");
        cancelButton.setDefaultButton(true);
        confirm.setOnShown(e -> cancelButton.requestFocus());

        return show(confirm).orElse(ButtonType.CANCEL) == delete;
    }

    /** @return true if the password was changed */
    public boolean changePassword(boolean forced, ChangePasswordDialog.Change change) {
        return ChangePasswordDialog.show(owner, model.themeProperty().get(), forced, change);
    }

    public void showAuditLog(List<AuditEntry> entries) {
        AuditLogDialog.show(owner, model.themeProperty().get(), entries);
    }

    public void showAbout() {
        Alert about = new Alert(Alert.AlertType.INFORMATION);
        about.setTitle("About");
        about.setHeaderText("Lodge Billing");
        about.setContentText("Lodge Billing runs the front desk: bookings and walk-ins, the tape chart, guest folios "
                + "with split bills and VAT, room housekeeping, walk-in receipts and reports.\n\n"
                + "Housekeeping: room status only. Front desk: bookings, folios, receipts. "
                + "Admin: also voids, forced check-out, prices, reports, users and the audit log.\n\n"
                + "Signed in as " + model.session().user().fullName()
                + " (" + model.session().user().role().getLabel() + ").");
        show(about);
    }

    /** Dialogs are separate windows, so they need the stylesheet and current theme too. */
    private Optional<ButtonType> show(Alert dialog) {
        dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Styles.apply(model.themeProperty().get(), dialog.getDialogPane());
        return dialog.showAndWait();
    }
}
