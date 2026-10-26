package lodgebilling.controller;

import lodgebilling.model.Bill;

/** Everything the user can ask the main window to do. Views call these; the controller implements them. */
public interface BillingActions {
    void viewReceipt();
    void printReceipt();
    void printReceiptFor(Bill bill);
    void saveReceiptPdf(Bill bill);
    void recordPayment();
    void deleteSelected();
    void toggleTheme();
    void changePassword();
    void backupNow();
    void restoreBackup();
    void logout();
    void exit();
}
