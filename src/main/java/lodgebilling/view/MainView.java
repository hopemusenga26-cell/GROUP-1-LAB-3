package lodgebilling.view;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lodgebilling.controller.BillingActions;
import lodgebilling.controller.PageContext;
import lodgebilling.dao.AuditLogDao;
import lodgebilling.model.Bill;
import lodgebilling.model.BillingModel;
import lodgebilling.model.security.Permission;
import lodgebilling.model.service.AuthService;
import lodgebilling.model.service.LodgeService;
import lodgebilling.view.pages.DashboardPage;
import lodgebilling.view.pages.Page;
import lodgebilling.view.pages.ReportsPage;
import lodgebilling.view.pages.ReservationDialog;
import lodgebilling.view.pages.RoomsPage;
import lodgebilling.view.pages.StaffPage;
import lodgebilling.view.pages.StaysPage;
import lodgebilling.view.pages.TapeChartPage;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The V in MVC: the whole window. A sidebar on the left switches between pages,
 * the header and status bar stay put. There is no menu bar: every action is a
 * button where it applies, and the keyboard shortcuts are set on the scene.
 */
public class MainView extends BorderPane {

    private static final DateTimeFormatter SIDEBAR_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM");

    private final BillingModel model;
    private final BillingActions actions;
    private final BillTableView table;
    private final AppToolBar toolBar;
    private final Dialogs dialogs;
    private final VBox receiptsContent;
    private final AppHeader header;
    private final PageContext pageContext;
    private final StackPane pageArea = new StackPane();

    private final Map<AppPage, Page> pages = new EnumMap<>(AppPage.class);
    private final Map<AppPage, ToggleButton> navButtons = new EnumMap<>(AppPage.class);
    private final ToggleGroup nav = new ToggleGroup();
    private final List<AppPage> visible = new ArrayList<>();
    private AppPage current;
    // Kept so they can be removed again at logout (the Scene outlives this view)
    private ChangeListener<Node> focusWatcher;
    private final Map<KeyCombination, Runnable> shortcuts = new LinkedHashMap<>();

    public MainView(BillingModel model, BillingActions actions, Stage stage, LodgeService lodge, AuditLogDao audit,
                    AuthService auth) {
        this.model = model;
        this.actions = actions;
        this.table = new BillTableView(model.bills(), actions::viewReceipt, actions::deleteSelected);
        this.toolBar = new AppToolBar(actions, model);
        this.dialogs = new Dialogs(stage, model, actions);
        this.header = new AppHeader(model, actions);
        this.pageContext = new PageContext(stage, model, lodge, audit, auth);
        pageContext.setRefreshCurrentPage(this::refreshCurrent);
        toolBar.disableWhen(table.getSelectionModel().selectedItemProperty().isNull());
        receiptsContent = buildReceiptsContent();

        // Only the pages this role may use are created and shown
        for (AppPage p : AppPage.values()) {
            if (model.session().has(p.permission())) {
                visible.add(p);
                pages.put(p, createPage(p));
            }
        }

        setLeft(buildSidebar());
        setBottom(new StatusBar(model.statusProperty()));

        // The header names the page, so it sits over the page rather than across the sidebar.
        // Header and page together are the sheet of paper the current sidebar tab joins.
        pageArea.getStyleClass().add("page-area");
        VBox.setVgrow(pageArea, Priority.ALWAYS);
        VBox sheet = new VBox(header, pageArea);
        sheet.getStyleClass().add("sheet");
        setCenter(sheet);
        if (!visible.isEmpty()) {
            showPage(visible.get(0));
        }

        // Switch colours whenever the model's theme changes
        Styles.apply(model.themeProperty().get(), this);
        model.themeProperty().addListener((obs, old, theme) -> Styles.apply(theme, this));
    }

    private Page createPage(AppPage p) {
        return switch (p) {
            case DASHBOARD -> new DashboardPage(pageContext);
            case TAPE_CHART -> new TapeChartPage(pageContext);
            case STAYS -> new StaysPage(pageContext);
            case ROOMS -> new RoomsPage(pageContext);
            case REPORTS -> new ReportsPage(pageContext);
            case STAFF -> new StaffPage(pageContext);
            case RECEIPTS -> new Page() {
                @Override public Node node() { return receiptsContent; }
                // Bookings and walk-ins are re-read, so their latest payments show
                @Override public void refresh() {
                    pageContext.attempt(() -> model.showStays(pageContext.lodge().stayReceipts()));
                }
            };
        };
    }

    private VBox buildSidebar() {
        VBox side = new VBox(4);
        side.getStyleClass().add("sidebar");
        Label name = new Label("Lodge Billing");
        name.getStyleClass().add("app-title");
        // The lodge's working date, which the arrivals, departures and tape chart all go by
        Label today = new Label(pageContext.lodge().today().format(SIDEBAR_DATE));
        today.getStyleClass().add("sidebar-date");
        side.getChildren().addAll(name, today);
        if (pageContext.can(Permission.MANAGE_RESERVATIONS)) {
            // Quick actions, reachable from every page (the pages do not repeat them):
            // two keys in a tray of their own, so they never pass for page tabs
            VBox desk = new VBox(6,
                    quickTile("New booking", "Reserve a room", "calendar", () -> quickBook(ReservationDialog.Mode.BOOK)),
                    quickTile("Walk-in", "Check in now", "key", () -> quickBook(ReservationDialog.Mode.WALK_IN)));
            desk.getChildren().get(0).getStyleClass().add("quick-primary");
            desk.getStyleClass().add("quick-desk");
            // The sidebar has no right padding (the page tabs run into the sheet), so the tray keeps its own
            VBox.setMargin(desk, new Insets(0, 14, 18, 0));
            side.getChildren().add(desk);
        }
        for (int i = 0; i < visible.size(); i++) {
            AppPage p = visible.get(i);
            ToggleButton b = new ToggleButton(p.label());
            b.setTooltip(new javafx.scene.control.Tooltip("Ctrl+" + (i + 1)));
            b.setToggleGroup(nav);
            b.getStyleClass().add("nav-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> showPage(p));
            navButtons.put(p, b);
            side.getChildren().add(b);
        }
        return side;
    }

    /** A quick action: an icon badge, then its name with what it is for underneath. The icon's shape is in lodge.css. */
    private static Button quickTile(String name, String hint, String icon, Runnable action) {
        Region glyph = new Region();
        glyph.getStyleClass().addAll("quick-icon", icon);
        StackPane badge = new StackPane(glyph);
        badge.getStyleClass().add("quick-badge");
        Label title = new Label(name);
        title.getStyleClass().add("quick-title");
        Label caption = new Label(hint);
        caption.getStyleClass().add("quick-hint");
        HBox face = new HBox(10, badge, new VBox(1, title, caption));
        face.setAlignment(Pos.CENTER_LEFT);

        Button tile = Ui.button(name, action);
        tile.setGraphic(face);
        tile.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        tile.setAccessibleText(name + ": " + hint);
        tile.getStyleClass().add("quick-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private void quickBook(ReservationDialog.Mode mode) {
        ReservationDialog.show(pageContext, mode, null, null, null).ifPresent(r -> pageContext.success(
                (mode == ReservationDialog.Mode.WALK_IN ? r.guestName() + " checked in to room "
                        : "Booked " + r.reference() + ": " + r.guestName() + ", room ") + r.room().number() + "."));
        refreshCurrent();
    }

    /** Switches to a page and re-reads its data. Ignored for pages this role cannot see. */
    public void showPage(AppPage page) {
        Page p = pages.get(page);
        if (p == null) {
            return;
        }
        current = page;
        pageArea.getChildren().setAll(p.node());
        header.setPage(page.label());
        ToggleButton b = navButtons.get(page);
        if (b != null) {
            b.setSelected(true);
        }
        p.refresh();
    }

    private void refreshCurrent() {
        if (current != null) {
            pages.get(current).refresh();
        }
    }

    /** Every receipt, quick bills and stays alike, with the buttons that act on the selected one. */
    private VBox buildReceiptsContent() {
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox tableCard = Ui.card("Receipts", List.of(toolBar), table);
        VBox.setVgrow(tableCard, Priority.ALWAYS);
        Label hint = new Label("Walk-ins and bookings appear here as BK receipts. "
                + "Use Walk-in on the left to bill a new guest.");
        hint.getStyleClass().add("hint");
        VBox content = new VBox(12, hint, tableCard);
        content.setPadding(new Insets(16, 20, 16, 20));
        return content;
    }

    /** Call once the scene exists: keyboard shortcuts and focus behaviour that need the whole scene. */
    public void install(Scene scene) {
        // Deselect the row when focus leaves the table, except when it goes to
        // a button that needs the selection (View receipt / Delete)
        focusWatcher = (obs, oldOwner, newOwner) -> {
            if (newOwner != null && newOwner != table && !toolBar.actsOnSelection(newOwner)) {
                table.clearSelection();
            }
        };
        scene.focusOwnerProperty().addListener(focusWatcher);

        // Ctrl+1, Ctrl+2... jump between pages without the mouse
        for (int i = 0; i < visible.size() && i < 9; i++) {
            AppPage p = visible.get(i);
            shortcuts.put(KeyCombination.keyCombination("Shortcut+" + (i + 1)), () -> showPage(p));
        }
        shortcuts.put(KeyCombination.keyCombination("Shortcut+T"), actions::toggleTheme);
        shortcuts.put(KeyCombination.keyCombination("Shortcut+L"), actions::logout);
        shortcuts.put(KeyCombination.keyCombination("Shortcut+Q"), actions::exit);
        if (pages.containsKey(AppPage.RECEIPTS)) {
            shortcuts.put(KeyCombination.keyCombination("Shortcut+R"), onReceipts(actions::viewReceipt));
            shortcuts.put(KeyCombination.keyCombination("Shortcut+P"), onReceipts(actions::printReceipt));
            shortcuts.put(KeyCombination.keyCombination("Shortcut+Y"), onReceipts(actions::recordPayment));
            shortcuts.put(KeyCombination.keyCombination("Shortcut+D"), onReceipts(actions::deleteSelected));
        }
        scene.getAccelerators().putAll(shortcuts);
    }

    /** A receipts shortcut does nothing on other pages, where there is no receipt to act on. */
    private Runnable onReceipts(Runnable action) {
        return () -> {
            if (current == AppPage.RECEIPTS) {
                action.run();
            }
        };
    }

    /** Call before the scene switches to another screen (logout). */
    public void uninstall(Scene scene) {
        if (focusWatcher != null) {
            scene.focusOwnerProperty().removeListener(focusWatcher);
            focusWatcher = null;
        }
        shortcuts.keySet().forEach(scene.getAccelerators()::remove);
        shortcuts.clear();
    }

    // ---------- What the controller can ask of the screen ----------

    public PageContext pageContext()        { return pageContext; }
    public Bill selectedBill()              { return current == AppPage.RECEIPTS ? table.selectedBill() : null; }
    public void clearSelection()            { table.clearSelection(); }
    public void focusTable()                { showPage(AppPage.RECEIPTS); table.requestFocus(); }
    public void selectBill(Bill bill)       { table.getSelectionModel().select(bill); table.scrollTo(bill); }

    public void showReceipt(Bill bill)      { dialogs.showReceipt(bill); }
    public boolean printReceipt(Bill bill)  { return dialogs.printReceipt(bill); }
    public boolean confirmDelete(Bill bill) { return dialogs.confirmDelete(bill); }
    public boolean changePassword(boolean forced, ChangePasswordDialog.Change change) {
        return dialogs.changePassword(forced, change);
    }
}
