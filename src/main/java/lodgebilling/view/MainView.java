package lodgebilling.view;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lodgebilling.controller.BillingActions;
import lodgebilling.data.AuditLog;
import lodgebilling.model.Bill;
import lodgebilling.model.BillFormData;
import lodgebilling.model.BillingModel;
import lodgebilling.model.FormField;
import lodgebilling.security.Permission;
import lodgebilling.service.AuthService;
import lodgebilling.service.LodgeService;
import lodgebilling.view.pages.DashboardPage;
import lodgebilling.view.pages.Page;
import lodgebilling.view.pages.PageContext;
import lodgebilling.view.pages.ReportsPage;
import lodgebilling.view.pages.ReservationDialog;
import lodgebilling.view.pages.RoomsPage;
import lodgebilling.view.pages.StaffPage;
import lodgebilling.view.pages.StaysPage;
import lodgebilling.view.pages.TapeChartPage;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The V in MVC: the whole window. A sidebar on the left switches between pages,
 * the header and status bar stay put. The Receipts page is the original
 * billing screen; the other pages live in view.pages.
 */
public class MainView extends BorderPane {

    private final BillFormView form;
    private final BillTableView table;
    private final AppToolBar toolBar;
    private final PricePanel pricePanel;
    private final Dialogs dialogs;
    private final ScrollPane billingScroll;
    private final AppHeader header;
    private final PageContext pageContext;
    private final AuroraBackground aurora = new AuroraBackground();
    // A frosted pane under every page, so text never sits straight on the bright sky
    private final StackPane pageArea = new StackPane();
    private VBox top;
    private VBox sidebar;

    private final Map<AppPage, Page> pages = new EnumMap<>(AppPage.class);
    private final Map<AppPage, ToggleButton> navButtons = new EnumMap<>(AppPage.class);
    private final ToggleGroup nav = new ToggleGroup();
    private AppPage current;
    // Kept so it can be removed again at logout (the Scene outlives this view)
    private ChangeListener<Node> focusWatcher;

    public MainView(BillingModel model, BillingActions actions, Stage stage, LodgeService lodge, AuditLog audit,
                    AuthService auth) {
        this.form = new BillFormView(actions, model.statusProperty());
        this.table = new BillTableView(model.bills(), actions::viewReceipt, actions::deleteSelected);
        this.toolBar = new AppToolBar(actions, model);
        this.pricePanel = new PricePanel(lodge.rateCard());
        this.dialogs = new Dialogs(stage, model, actions);
        this.header = new AppHeader(model, actions);
        this.pageContext = new PageContext(stage, model, lodge, audit, auth);
        pageContext.setRefreshCurrentPage(this::refreshCurrent);

        // The aurora sits behind everything and fills the whole window
        aurora.setManaged(false);
        getChildren().add(aurora);

        billingScroll = new ScrollPane(buildBillingContent());
        billingScroll.setFitToWidth(true);
        billingScroll.setFitToHeight(true);
        billingScroll.getStyleClass().add("main-scroll");

        // Only the pages this role may use are created and shown
        List<AppPage> visible = new ArrayList<>();
        for (AppPage p : AppPage.values()) {
            if (model.session().has(p.permission())) {
                visible.add(p);
                pages.put(p, createPage(p));
            }
        }

        top = new VBox(new AppMenuBar(actions, model, visible, this::showPage), header);
        setTop(top);
        sidebar = buildSidebar(visible);
        setLeft(sidebar);
        StatusBar status = new StatusBar(model.statusProperty());
        BorderPane.setMargin(status, new Insets(0, 20, 12, 20));
        setBottom(status);

        pageArea.getStyleClass().add("page-area");
        setCenter(pageArea);
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
            case QUICK_BILLS -> new Page() {
                private final VBox node = new VBox(toolBar, billingScroll);
                { VBox.setVgrow(billingScroll, Priority.ALWAYS); }
                @Override public Node node() { return node; }
                @Override public void refresh() { pricePanel.refresh(); }
            };
        };
    }

    private VBox buildSidebar(List<AppPage> visible) {
        VBox side = new VBox(6);
        side.getStyleClass().add("sidebar");
        if (pageContext.can(Permission.MANAGE_RESERVATIONS)) {
            // Quick actions, reachable from every page
            Button book = Ui.button("+ New booking", () -> quickBook(ReservationDialog.Mode.BOOK));
            Button walkIn = Ui.button("Walk-in", () -> quickBook(ReservationDialog.Mode.WALK_IN));
            book.getStyleClass().add("quick-action");
            walkIn.getStyleClass().add("quick-action");
            book.setMaxWidth(Double.MAX_VALUE);
            walkIn.setMaxWidth(Double.MAX_VALUE);
            side.getChildren().addAll(book, walkIn);
        }
        side.getChildren().add(sideLabel(visible.size() > 1 ? "Go to   (Ctrl+1 to " + visible.size() + ")" : "Go to"));
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

    private static Label sideLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("side-label");
        return l;
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

    private void showBilling() {
        if (current != AppPage.QUICK_BILLS) {
            showPage(AppPage.QUICK_BILLS);
        }
    }

    private VBox buildBillingContent() {
        HBox.setHgrow(form, Priority.ALWAYS);
        HBox formAndPrices = new HBox(16, form, pricePanel);

        Label savedLabel = new Label("Saved receipts");
        savedLabel.getStyleClass().add("card-title");
        VBox.setVgrow(table, Priority.ALWAYS);
        VBox tableCard = new VBox(10, savedLabel, table);
        tableCard.getStyleClass().add("card");
        VBox.setVgrow(tableCard, Priority.ALWAYS);

        VBox content = new VBox(12, formAndPrices, tableCard);
        content.setPadding(new Insets(16, 20, 12, 20));
        return content;
    }

    /** Call once the scene exists: focus behaviour that needs the whole scene. */
    public void install(Scene scene) {
        Motion.liftButtons(this);
        Motion.enter(top, sidebar, getCenter());
        // Deselect the row when focus leaves the table, except when it goes to
        // a button that needs the selection (View receipt / Delete)
        focusWatcher = (obs, oldOwner, newOwner) -> {
            if (newOwner != null && newOwner != table && !toolBar.actsOnSelection(newOwner)) {
                table.clearSelection();
            }
        };
        scene.focusOwnerProperty().addListener(focusWatcher);
        if (current == AppPage.QUICK_BILLS) {
            Platform.runLater(() -> {
                form.focus(FormField.NAME);
                billingScroll.setVvalue(0);
            });
        }
    }

    /** Call before the scene switches to another screen (logout). */
    public void uninstall(Scene scene) {
        aurora.stop();
        if (focusWatcher != null) {
            scene.focusOwnerProperty().removeListener(focusWatcher);
            focusWatcher = null;
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        // the backdrop fills the whole window, behind the bars and the cards
        aurora.resizeRelocate(0, 0, getWidth(), getHeight());
    }

    // ---------- What the controller can ask of the screen ----------

    public BillFormData readForm()          { return form.read(); }
    public void clearForm()                 { showBilling(); form.clear(); }
    public void focusField(FormField field) { showBilling(); form.focus(field); }
    public Bill selectedBill()              { return current == AppPage.QUICK_BILLS ? table.selectedBill() : null; }
    public void clearSelection()            { table.clearSelection(); }
    public void focusTable()                { showBilling(); table.requestFocus(); }
    public void scrollToBill(Bill bill)     { table.scrollTo(bill); }

    public void showReceipt(Bill bill)      { dialogs.showReceipt(bill); }
    public boolean printReceipt(Bill bill)  { return dialogs.printReceipt(bill); }
    public boolean confirmDelete(Bill bill) { return dialogs.confirmDelete(bill); }
    public void showAbout()                 { dialogs.showAbout(); }
    public void manageUsers()               { showPage(AppPage.STAFF); }
    public void showAuditLog(List<AuditLog.Entry> entries) { dialogs.showAuditLog(entries); }
    public boolean changePassword(boolean forced, ChangePasswordDialog.Change change) {
        return dialogs.changePassword(forced, change);
    }
}
