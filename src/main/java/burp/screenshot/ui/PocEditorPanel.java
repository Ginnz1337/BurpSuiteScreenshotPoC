package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.model.UndoHistory;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SwitchToggle;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * The PoC tab Burp shows beside the message editor.
 *
 * <p>It shows the message as colored text so a screenshot can be taken of it directly, in
 * Repeater or Logger, without a render step and without leaving the tool. Nothing here edits
 * the message: the tab never writes back, which is why the host editor reports
 * {@code isModified()} as false.
 *
 * <p>Shortcuts are bound to this panel rather than to Burp's root pane. A
 * {@code WHEN_IN_FOCUSED_WINDOW} binding on the root pane would fire while the user is typing
 * in Burp's own message editor, taking those keys with it.
 */
public class PocEditorPanel extends JPanel {

    private final TemplateConfig config;
    private final StyledMessageView view;
    private final JLabel statsLabel;
    private final SwitchToggle wrapToggle;

    /** Persists the template after a change the user made from this tab. */
    private final Runnable onTemplateChanged;

    /** The settings this tab has been through, so Ctrl+Z can walk back through them. */
    private final UndoHistory history = new UndoHistory();

    /** The message, materialized. Null until something reads it. */
    private HttpExchangeData data;

    /**
     * Where the message comes from, held instead of the message.
     *
     * <p>Reading the message out of Burp means rendering both of its sides to text, and on a
     * response of a few megabytes that is the whole cost of this tab. Burp announces a message
     * to the tab of every editor it holds, whether or not that tab is on screen, so the tab is
     * registered in Proxy history and the user scrolls that list far more often than they open
     * this tab. Holding the supplier is what keeps the read from happening at all until there is
     * a reason for it.
     */
    private Supplier<HttpExchangeData> source;

    /** The message Burp last handed over, compared by reference. See {@link #setSource}. */
    private Object sourceToken;

    /** The message whose text was last handed to the view, by the same reference. */
    private Object shownToken;

    /** What the view last drew, so the same message is not drawn a second time. */
    private HttpExchangeData shown;

    /** Set when the message changed while this tab was off screen. */
    private boolean pending;

    private AutoCloseable themeHandle;

    public PocEditorPanel(boolean isRequest, TemplateConfig config, Runnable onTemplateChanged) {
        super(new BorderLayout());
        this.config = config;
        this.onTemplateChanged = onTemplateChanged;
        setOpaque(true);

        view = new StyledMessageView(isRequest);
        view.setOnRulesChanged(this::persist);
        view.setOnBeforeEdit(this::rememberForUndo);
        view.setWrap(config.isWrapText());

        statsLabel = Fields.muted("Waiting for a request");

        wrapToggle = new SwitchToggle(config.isWrapText(), on -> {
            rememberForUndo();
            config.setWrapText(on);
            view.setWrap(on);
            updateStats();
            persist();
        });

        add(buildHeader(), BorderLayout.NORTH);
        add(view, BorderLayout.CENTER);
        installShortcuts();

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                flushPending();
            }
        });
    }

    /**
     * The strip above the text: what the tab is showing, what it left out, and the controls.
     *
     * <p>One row, and as short as the controls allow. Every pixel here is a pixel of message
     * that is not in the screenshot, which is the point of the tab, so the title carries the
     * side and the stats line carries the rest rather than each having a row.
     */
    private JComponent buildHeader() {
        JPanel bar = new JPanel(new BorderLayout(Tokens.SM, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.tokens().border),
                BorderFactory.createEmptyBorder(2, Tokens.MD, 2, Tokens.MD)));

        JLabel title = Fields.primary(view.isRequestSide() ? "Request" : "Response");
        title.setFont(Theme.tokens().uiBold);
        title.setIcon(Icons.code(null, 14));
        title.setIconTextGap(Tokens.XS);
        title.setToolTipText("The PoC view of this side of the exchange.");

        // The label carries the wrap state in its tooltip rather than as a word of its own:
        // "wrap on" beside a switch that is visibly on says the same thing twice.
        statsLabel.setToolTipText("Headers hidden and lines removed by the current settings.");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SM, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(statsLabel);

        JButton settings = Buttons.iconOnly(Icons.palette(null, 14), "Headers, lines and rules");
        settings.addActionListener(e -> {
            // One state for the whole visit, so Ctrl+Z after closing the dialog comes back to
            // the settings the dialog was opened with rather than to the last keystroke in it.
            rememberForUndo();
            SettingsDialog.show(this, config, data, this::applyConfigChange, this::onSettingsClosed);
        });

        wrapToggle.setToolTipText("Wrap long lines. Off gives one line per line of the message.");

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SM, 0));
        right.setOpaque(false);
        right.add(wrapToggle);
        right.add(view.getSearchControls());
        right.add(settings);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------ data

    /**
     * Points the tab at the message Burp is showing. Called again on every selection change.
     *
     * <p>Three cases, in order of how often they happen in Proxy history, where the list is
     * scrolled faster than any tab is read:
     *
     * <ol>
     *   <li>The same message again, by reference. Burp re-announces a message on every change
     *       anywhere in the tab strip. Nothing is done, and the comparison costs nothing, where
     *       comparing the text would cost a copy of the message.</li>
     *   <li>This tab is not the one on screen. Nothing is read and nothing is built; the supplier
     *       is held until somebody looks. This is the case the whole method exists for.</li>
     *   <li>Otherwise the message is read and drawn.</li>
     * </ol>
     *
     * @param token  identifies the message, compared by reference. Burp's own
     *               {@code HttpRequestResponse} is what the host editor passes.
     * @param source reads the message. Called only when a draw is about to happen, and never
     *               while this tab is off screen.
     */
    public void setSource(Object token, Supplier<HttpExchangeData> source) {
        this.sourceToken = token;
        this.source = source;

        if (token != null && token == shownToken) return;
        if (!isShowing()) {
            pending = true;
            return;
        }
        drawPending();
    }

    /** The message itself, for a caller that already holds it. */
    public void setData(HttpExchangeData exchange) {
        setSource(exchange, () -> exchange);
    }

    /**
     * Reads the pending message and draws it. The only place the supplier is called.
     *
     * <p>Nothing is read twice: {@link #sourceToken} records which message the current draw
     * belongs to, so a message announced while the tab was hidden is read once, when it is
     * finally looked at, rather than once per announcement.
     */
    private void drawPending() {
        pending = false;

        HttpExchangeData fresh = source == null ? null : source.get();
        if (fresh == null) return;

        data = fresh;
        if (!sameText(fresh, shown)) {
            view.updateData(fresh, config);
            shown = fresh;
        }
        shownToken = sourceToken;
        updateStats();
    }

    /**
     * Draws a message that arrived while the tab was off screen.
     *
     * <p>Also called while painting. That is the safety net: Burp is not obliged to fire
     * {@code componentShown} for a tab it shows again, and a tab that comes back blank would be
     * a worse bug than the one this defers around.
     */
    private void flushPending() {
        if (pending) drawPending();
    }

    /** Whether a message has been read but not yet drawn, which a check reads to see the defer. */
    public boolean isPending() { return pending; }

    @Override
    public void paint(Graphics g) {
        flushPending();
        super.paint(g);
    }

    private static boolean sameText(HttpExchangeData a, HttpExchangeData b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return same(a.getRawRequest(), b.getRawRequest())
                && same(a.getRawResponse(), b.getRawResponse());
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** The selected text, reported to Burp by the host editor. */
    public String getSelectedText() { return view.getSelectedText(); }

    /** Exposed so a test can reach the text view without going through the header. */
    public StyledMessageView getView() { return view; }

    /**
     * The tab's ground, resolved on every call.
     *
     * <p>The tab is embedded in Burp's own window, so the alternative is Burp's panel color,
     * which the text tokens were not picked against.
     */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }

    // ------------------------------------------------------------------ actions

    private void updateStats() {
        if (data == null) {
            statsLabel.setText("Waiting for a request");
            return;
        }
        int hidden = view.getHiddenHeaderCount();
        String headers = hidden == 0 ? "no headers hidden"
                : (hidden == 1 ? "1 header hidden" : hidden + " headers hidden");

        // Shown only when it is not zero: a view that removed nothing should not spend a word
        // saying so. The wrap state used to be a third clause here and is now the switch beside
        // it, which shows the same thing without being read.
        int removed = view.getRemovedLineCount();
        String lines = removed == 0 ? ""
                : "  ·  " + (removed == 1 ? "1 line removed" : removed + " lines removed");

        statsLabel.setText(headers + lines);
    }

    /** Redraws from the config while the settings dialog is open. */
    private void applyConfigChange() {
        redraw();
    }

    /**
     * Draws the message already read, under the current settings.
     *
     * <p>Separate from {@link #drawPending}: this one has a message in hand, because a rule the
     * user just edited is only reachable from the settings dialog, which is only reachable from
     * this tab while it is on screen.
     */
    private void redraw() {
        if (data == null) {
            updateStats();
            return;
        }
        view.updateData(data, config);
        shown = data;
        updateStats();
    }

    private void onSettingsClosed() {
        persist();
        updateStats();
    }

    private void persist() {
        if (onTemplateChanged != null) onTemplateChanged.run();
    }

    // ------------------------------------------------------------------ undo

    /**
     * Remembers the settings as they are, so the change about to happen can be taken back.
     *
     * <p>Called before an edit rather than after one. Opening the menu and picking nothing leaves
     * a duplicate behind, which {@link UndoHistory#undo} drops when it looks for a state that
     * differs; the alternative is this class tracking whether a change really happened.
     */
    public void rememberForUndo() {
        history.remember(config);
    }

    /**
     * Puts the settings back the way they were before the last change. Bound to {@code Ctrl + Z}.
     *
     * <p>Written into the existing config rather than swapped for the remembered one: the view
     * and the settings dialog hold a reference to it. The template is saved, so a change taken
     * back stays taken back after a restart.
     */
    public void undoLastChange() {
        TemplateConfig previous = history.undo(config);
        if (previous == null) return;

        config.copyFrom(previous);
        wrapToggle.setSelected(config.isWrapText(), true);
        redraw();
        persist();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Only {@link JComponent#WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}.
     *
     * <p>The panel lives inside Burp's own window, so any wider scope would take these keys
     * from Burp whenever the Repeater tab happened to be open.
     */
    private void installShortcuts() {
        int scope = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
        int menu = menuMask();

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), scope, view::focusSearch);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), scope, this::undoLastChange);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), scope, view::refreshTheme);
    }

    /**
     * The platform's menu shortcut, Ctrl on Windows and Linux and Command on macOS.
     *
     * <p>A display-less JVM has no toolkit to ask, and this panel is built by the verification
     * run as well as by Burp. Ctrl is what the mask resolves to on the platforms this extension
     * is built for, so a headless run binds the same key a user would press.
     */
    private static int menuMask() {
        return GraphicsEnvironment.isHeadless()
                ? InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    private void bind(KeyStroke key, int scope, Runnable action) {
        registerKeyboardAction(e -> action.run(), key, scope);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeHandle == null) {
            themeHandle = Theme.addListener(() -> SwingUtilities.invokeLater(() -> {
                view.refreshTheme();
                repaint();
            }));
        }
    }

    @Override
    public void removeNotify() {
        if (themeHandle != null) {
            try {
                themeHandle.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            themeHandle = null;
        }
        super.removeNotify();
    }
}
