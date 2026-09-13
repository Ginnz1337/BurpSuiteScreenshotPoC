package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;

/**
 * Everything the right-click menu cannot express: the header hide list, the lines to leave out
 * of the PoC, and the full rule list.
 *
 * <p>Two tabs, down from four. The lines to leave out were a tab of their own and answer the
 * same question the header list answers, so they sit under it. A third held the syntax colors
 * and a fourth the hidden lines; the colors went because nothing but this dialog ever set them,
 * the rules already carry a color per highlight, and a tab nobody opens costs a click from
 * everyone else. {@link SyntaxColorEditor} is left in the tree, unreferenced, so the palette can
 * come back as a tab without being rewritten.
 *
 * <p>A dialog rather than a side panel. The side panel belonged to the studio window, and the
 * tab now lives inside Repeater, where a permanently open inspector would take width from the
 * text it is meant to help photograph.
 *
 * <p>Changes apply to the live config as they are made, so the tab behind the dialog redraws
 * while the user works. They are debounced, because a rule pattern fires an event per
 * keystroke and rebuilding a long message per keystroke is the cost this view exists to avoid.
 * The template is written to disk once, when the dialog closes.
 */
public final class SettingsDialog extends JDialog {

    private static final int APPLY_DELAY_MS = 250;

    private final TemplateConfig config;
    private final HttpExchangeData data;
    private final Runnable onLiveChange;
    private final Runnable onCommit;

    private final Timer applyDebounce;

    private SettingsDialog(Window owner, TemplateConfig config, HttpExchangeData data,
                           Runnable onLiveChange, Runnable onCommit) {
        super(owner, "Screenshot PoC view settings", ModalityType.APPLICATION_MODAL);
        this.config = config;
        this.data = data;
        this.onLiveChange = onLiveChange;
        this.onCommit = onCommit;

        applyDebounce = new Timer(APPLY_DELAY_MS, e -> {
            if (onLiveChange != null) onLiveChange.run();
        });
        applyDebounce.setRepeats(false);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.tokens().ui);
        tabs.addTab("Headers", tab(new HeadersTab(config, data, this::scheduleApply, this::applyNow)));
        tabs.addTab("Rules", tab(new RulesTab(config, this::scheduleApply, this::applyNow)));
        tabs.addChangeListener(e -> applyNow());

        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        // Closing with the window button has to write the template too, or the edits made in
        // the last dialog would be lost with no sign that anything was dropped.
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(640, 560);
        // Wide enough for the longest control row, which is the header hint beside the add
        // button. Narrower and the hint is clipped rather than wrapped, since it is one label.
        setMinimumSize(new Dimension(580, 400));
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the dialog and blocks until it closes.
     *
     * @param onLiveChange called, debounced, while the user edits
     * @param onCommit     called once on close, to persist the template
     */
    public static void show(Component parent, TemplateConfig config, HttpExchangeData data,
                            Runnable onLiveChange, Runnable onCommit) {
        if (config == null) return;
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        new SettingsDialog(owner, config, data, onLiveChange, onCommit).setVisible(true);
    }

    private JPanel tab(JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }

    // ------------------------------------------------------------------ footer

    private JComponent buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(Tokens.MD, 0));
        footer.setOpaque(true);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.tokens().border),
                BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD)));

        JButton close = Buttons.primary("Close", Icons.check(null, 14));
        close.addActionListener(e -> {
            applyNow();
            dispose();
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SM, 0));
        right.setOpaque(false);
        right.add(close);

        footer.add(Fields.muted("Changes appear in the tab as you make them."), BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    @Override
    public void dispose() {
        applyDebounce.stop();
        if (onCommit != null) onCommit.run();
        super.dispose();
    }

    // ------------------------------------------------------------------ plumbing

    private void scheduleApply() {
        applyDebounce.restart();
    }

    private void applyNow() {
        applyDebounce.stop();
        if (onLiveChange != null) onLiveChange.run();
    }

}
