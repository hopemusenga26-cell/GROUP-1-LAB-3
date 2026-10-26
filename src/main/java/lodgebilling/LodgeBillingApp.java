package lodgebilling;

import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import lodgebilling.controller.BillingController;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.dao.BillDao;
import lodgebilling.dao.DataException;
import lodgebilling.dao.Database;
import lodgebilling.dao.LegacyReceiptImporter;
import lodgebilling.dao.SqliteAuditLogDao;
import lodgebilling.dao.SqliteBillDao;
import lodgebilling.dao.SqliteCashUpDao;
import lodgebilling.dao.SqliteFolioDao;
import lodgebilling.dao.SqliteReportDao;
import lodgebilling.dao.SqliteReservationDao;
import lodgebilling.dao.SqliteRoomDao;
import lodgebilling.dao.SqliteSettingsDao;
import lodgebilling.dao.SqliteUserDao;
import lodgebilling.model.service.LodgeService;
import lodgebilling.model.BillingModel;
import lodgebilling.model.Theme;
import lodgebilling.model.User;
import lodgebilling.model.security.PasswordHasher;
import lodgebilling.model.security.Session;
import lodgebilling.model.service.AuthException;
import lodgebilling.model.service.AuthService;
import lodgebilling.model.service.BackupService;
import lodgebilling.view.AuthCard;
import lodgebilling.view.LoginView;
import lodgebilling.view.SetupView;
import lodgebilling.view.Styles;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point. Opens the database, then moves between three screens on one window:
 * first-run setup -> sign in -> the billing screen (and back to sign in at logout).
 * Start it from {@link Launcher} when running inside IntelliJ.
 */
public class LodgeBillingApp extends Application {

    private static final Logger LOG = Logger.getLogger(LodgeBillingApp.class.getName());

    private static final double PREFERRED_WIDTH = 1280;   // room for the sidebar plus the receipt form
    private static final double PREFERRED_HEIGHT = 820;
    private static final double MIN_WIDTH = 900;
    private static final double MIN_HEIGHT = 560;
    // Room left for the window's own title bar, which is not part of the scene
    private static final double TITLE_BAR_HEIGHT = 40;

    private Stage stage;
    private Scene scene;
    private Database database;
    private AuthService auth;
    private AuditLogDao audit;
    private BillDao bills;
    private LodgeService lodge;
    private BackupService backups;
    private BillingController current;
    private AuthCard authCard;           // the sign-in or setup screen while it is showing
    // Kept across logins, and shared with the sign-in screen's light/dark switch
    private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(Theme.LIGHT);

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Path file = Database.defaultPath();
        Optional<String> restored;
        try {
            // A backup chosen for restoring last time replaces the data before it is opened
            restored = BackupService.applyPendingRestore(file, Clock.systemDefaultZone());
            database = Database.open(file);
        } catch (DataException e) {
            LOG.log(Level.SEVERE, "Could not open the database", e);
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage() + "\n\n" + e.getCause().getMessage());
            alert.setHeaderText("Lodge Billing cannot start");
            alert.showAndWait();
            return;
        }
        Clock clock = Clock.systemUTC();
        audit = new SqliteAuditLogDao(database, clock);
        bills = new SqliteBillDao(database);
        auth = new AuthService(new SqliteUserDao(database), audit, new PasswordHasher(), clock);
        // Receipts saved by the older version (receipts.tsv) are copied in once
        LegacyReceiptImporter.importOnce(database, audit, LegacyReceiptImporter.defaultFile());
        // Business dates (arrivals, check-out) use the computer's own time zone
        lodge = new LodgeService(database, new SqliteRoomDao(database), new SqliteReservationDao(database),
                new SqliteFolioDao(database), new SqliteSettingsDao(database), new SqliteReportDao(database),
                new SqliteCashUpDao(database), audit, Clock.systemDefaultZone());
        backups = new BackupService(database, file, audit, Clock.systemDefaultZone());
        restored.ifPresent(backups::recordRestore);
        try {
            backups.dailyBackup();
        } catch (DataException e) {
            // A missed backup must not stop the desk from working; the next start tries again
            LOG.log(Level.WARNING, "The daily backup failed", e);
        }

        // Open at a comfortable size, but never bigger than the screen (minus the taskbar)
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(PREFERRED_WIDTH, screen.getWidth());
        double height = Math.min(PREFERRED_HEIGHT, screen.getHeight() - TITLE_BAR_HEIGHT);
        scene = new Scene(new StackPane(), width, height);
        scene.getStylesheets().add(Styles.stylesheet());

        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setTitle("Lodge Billing");
        stage.setScene(scene);

        if (auth.needsSetup()) {
            showSetup();
        } else {
            showLogin();
        }
        stage.show();
        restored.ifPresent(what -> {
            Alert done = new Alert(Alert.AlertType.INFORMATION, "The data now comes from the backup: " + what
                    + ".\n\nThe data it replaced is kept in " + backups.folder() + " as before-restore-....db.");
            done.initOwner(stage);
            done.setHeaderText("Backup restored");
            done.showAndWait();
        });
    }

    @Override
    public void stop() {
        if (database != null) {
            database.close();
        }
    }

    // ---------- Screens ----------

    private void showSetup() {
        SetupView setup = new SetupView((fullName, username, password) -> {
            try {
                User admin = auth.createFirstAdmin(username, fullName, password);
                LOG.info(() -> "First admin created: " + admin.username());
                authCard.leave(this::showLogin);
            } catch (AuthException | DataException e) {
                authCard.showError(e.getMessage());
            }
        }, theme);
        show(setup);
    }

    private void showLogin() {
        LoginView login = new LoginView((username, password) -> {
            try {
                User user = auth.login(username, password);
                authCard.leave(() -> signedIn(user));
            } catch (AuthException | DataException e) {
                authCard.showError(e.getMessage());
            }
        }, theme);
        show(login);
    }

    private void signedIn(User user) {
        Session session = new Session(user);
        BillingModel model = new BillingModel(bills, audit, session, theme.get());
        current = new BillingController(model, stage, auth, audit, lodge, backups, this::logout);
        authCard = null;
        scene.setRoot(current.view());
        current.view().install(scene);

        // The main window fades in after the sign-in card has faded out
        current.view().setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(500), current.view());
        fadeIn.setToValue(1);
        fadeIn.play();
        stage.setTitle("Lodge Billing - " + user.fullName() + " (" + user.role().getLabel() + ")");

        // An admin set a temporary password: the user must replace it before working
        if (user.mustChangePassword()) {
            boolean changed = current.view().changePassword(true,
                    (oldPw, newPw) -> auth.changeOwnPassword(session, oldPw, newPw));
            if (!changed) {
                logout();
            }
        }
    }

    private void logout() {
        if (current != null) {
            theme.set(current.theme());
            current.view().uninstall(scene);
            current = null;
        }
        stage.setTitle("Lodge Billing");
        showLogin();
    }

    private void show(AuthCard card) {
        if (authCard != null) {
            authCard.dispose();
        }
        authCard = card;
        scene.setRoot(card);
        card.install();
    }


    public static void main(String[] args) {
        launch(args);
    }
}
