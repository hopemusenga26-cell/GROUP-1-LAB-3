package lodgebilling.view;

import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import lodgebilling.controller.BillingActions;
import lodgebilling.model.Bill;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Money;
import lodgebilling.model.service.UserFacingException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** The pop-up windows of the receipts list (receipt, delete confirmation, password), owned by the main window. */
public class Dialogs {

    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Stage owner;
    private final BillingModel model;
    private final BillingActions actions;

    public Dialogs(Stage owner, BillingModel model, BillingActions actions) {
        this.owner = owner;
        this.model = model;
        this.actions = actions;
    }

    /** What to do with the backup picked in {@link #chooseBackup}; throw to keep the window open. */
    @FunctionalInterface
    public interface Restore {
        void restore(Path backup) throws UserFacingException;
    }

    /** Lets an admin pick a backup to restore, newest first. True if one was chosen and accepted. */
    public boolean chooseBackup(List<Path> backups, Restore restore) {
        FormDialog form = new FormDialog(owner, model.themeProperty().get(), "Restore a backup",
                "Restore a backup", "Restore on next start", "Cancel");
        ComboBox<Path> choice = form.add("Backup", new ComboBox<>(FXCollections.observableArrayList(backups)));
        choice.setConverter(new StringConverter<>() {
            @Override public String toString(Path p) { return p == null ? "" : describe(p); }
            @Override public Path fromString(String s) { return null; }
        });
        if (!backups.isEmpty()) {
            choice.setValue(backups.get(0));
        }
        form.addNote("Everything entered after the backup was made will be replaced. The data in use now "
                + "is kept in the backups folder, so this can be undone. The backup is put in place the next "
                + "time Lodge Billing starts.");
        form.onSubmit(() -> restore.restore(choice.getValue()));
        return form.showAndWait();
    }

    private static String describe(Path backup) {
        try {
            return backup.getFileName() + "  (" + BACKUP_TIME.format(Files.getLastModifiedTime(backup).toInstant())
                    + ", " + Math.max(1, Files.size(backup) / 1024) + " KB)";
        } catch (IOException e) {
            return backup.getFileName().toString();
        }
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
        ButtonType pdf = new ButtonType("Save as PDF", ButtonBar.ButtonData.LEFT);
        Alert dialog = new Alert(Alert.AlertType.INFORMATION, "", print, pdf, ButtonType.CLOSE);
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
        dialog.getDialogPane().lookupButton(pdf).addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            actions.saveReceiptPdf(bill);
        });
        show(dialog);
    }

    /** Sends the receipt to a printer the user picks. Returns false if cancelled or it failed. */
    public boolean printReceipt(Bill bill) {
        return ReceiptView.print(owner, model.themeProperty().get(), bill);
    }

    /** Saves the receipt as a PDF where the user picks. Returns the file, or null if cancelled or it failed. */
    public File saveReceiptPdf(Bill bill) {
        return ReceiptView.savePdf(owner, model.themeProperty().get(), bill);
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

    /** Dialogs are separate windows, so they need the stylesheet and current theme too. */
    private Optional<ButtonType> show(Alert dialog) {
        dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Styles.apply(model.themeProperty().get(), dialog.getDialogPane());
        return dialog.showAndWait();
    }
}
