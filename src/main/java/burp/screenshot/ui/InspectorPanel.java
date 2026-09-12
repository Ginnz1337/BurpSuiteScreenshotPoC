package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.LayoutMode;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.AccordionSection;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.PromptDialog;
import burp.screenshot.ui.components.SegmentedControl;
import burp.screenshot.ui.components.SlimScrollBarUI;
import burp.screenshot.ui.components.SwitchToggle;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;

/**
 * The right-hand dock: everything that changes the card, plus the rule lists.
 *
 * <p>Controls write straight into the live {@link TemplateConfig} and then call back, so the
 * preview follows each keystroke. Loading a template instead writes into the controls, and
 * must not be mistaken for a user edit; every setter is therefore guarded by
 * {@link #isInitializing}, and the toggles use their silent form.
 */
public class InspectorPanel extends JPanel {

    /** Which half of the inspector is visible. */
    public enum Tab {
        CONFIG("Settings"),
        RULES("Rules");

        private final String label;
        Tab(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public interface Listener {
        /**
         * @param templateSwitched true when a different template was loaded, so the host can
         *                         re-read the name and reset per-template view state
         */
        void configChanged(TemplateConfig config, boolean templateSwitched);
    }

    private static final String[] WIDTHS = {
            "Compact (800px)", "Medium (1000px)", "Wide (1200px)", "Full Width"
    };
    private static final String[] SCALES = {"1x", "2x", "3x"};

    private final TemplateManager templateManager;
    private final Listener listener;

    private TemplateConfig config;
    private boolean isInitializing = true;
    /** False until the constructor has finished, so building the controls fires no callback. */
    private boolean ready;

    // template
    private JComboBox<String> templateCombo;

    // headers
    private JTextArea headersArea;
    private SegmentedControl<ScopeTarget> headerScope;

    // layout
    private SegmentedControl<LayoutMode> layoutMode;
    private SegmentedControl<String> windowStyle;
    private JComboBox<String> widthCombo;
    private JComboBox<String> scaleCombo;

    // card
    private SwitchToggle roundedToggle;
    private SwitchToggle shadowToggle;
    private JTextField watermarkField;

    // display
    private SwitchToggle urlToggle;
    private SwitchToggle timestampToggle;
    private SwitchToggle responseInfoToggle;
    private SwitchToggle wrapToggle;

    // crop
    private JTextField requestRangesField;
    private JTextField responseRangesField;

    // colors
    private SyntaxColorEditor colorEditor;

    // rules
    private final JPanel highlightsContainer = column();
    private final JPanel redactionsContainer = column();

    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private final SegmentedControl<Tab> tabSwitch;

    public InspectorPanel(TemplateManager templateManager, TemplateConfig initial, Listener listener) {
        super(new BorderLayout());
        this.templateManager = templateManager;
        this.listener = listener;
        this.config = initial != null ? initial : new TemplateConfig("Default");

        setOpaque(true);

        tabSwitch = new SegmentedControl<>(List.of(Tab.CONFIG, Tab.RULES), Tab::toString,
                Tab.CONFIG, this::showTab);

        cardHost.setOpaque(false);
        cardHost.add(buildConfigTab(), Tab.CONFIG.name());
        cardHost.add(buildRulesTab(), Tab.RULES.name());

        add(buildHeader(), BorderLayout.NORTH);
        add(cardHost, BorderLayout.CENTER);

        loadConfig(this.config, false);
        ready = true;
    }

    private JComponent buildHeader() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.tokens().border),
                BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD)));
        bar.add(tabSwitch, BorderLayout.CENTER);
        return bar;
    }

    public void showTab(Tab tab) {
        tabSwitch.setSelected(tab, true);
        cards.show(cardHost, tab.name());
    }

    // ------------------------------------------------------------------ config tab

    private JComponent buildConfigTab() {
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setOpaque(false);
        stack.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.XL, Tokens.MD));

        stack.add(buildTemplateSection());
        stack.add(buildHeadersSection());
        stack.add(buildLayoutSection());
        stack.add(buildCardSection());
        stack.add(buildDisplaySection());
        stack.add(buildCropSection());
        stack.add(buildColorSection());
        stack.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        SlimScrollBarUI.install(scroll);
        return scroll;
    }

    private AccordionSection buildTemplateSection() {
        AccordionSection s = new AccordionSection("Template", "Saved presets", true);

        templateCombo = Fields.combo(templateManager.getTemplateNames().toArray(new String[0]));
        templateCombo.setSelectedItem(config.getName());
        templateCombo.addActionListener(e -> {
            if (isInitializing) return;
            String name = (String) templateCombo.getSelectedItem();
            if (name == null) return;
            TemplateConfig loaded = templateManager.getTemplate(name);
            if (loaded != null) loadConfig(loaded, true);
        });
        JButton reload = Buttons.iconOnly(Icons.refresh(null, 14),
                "Read the template list from disk again");
        reload.addActionListener(e -> reloadTemplates());

        s.addRow(Fields.row("Current", withTrailing(templateCombo, reload)));

        JButton save = Buttons.primary("Save", Icons.save(null, 13));
        save.setToolTipText("Write the changes into the current template");
        save.addActionListener(e -> saveCurrent());

        JButton saveAs = Buttons.secondary("Save as new", Icons.plus(null, 13));
        saveAs.addActionListener(e -> saveAsNew());

        // Reload sits with the combo it reloads. Three labelled buttons do not fit the
        // inspector: Reload, Save and Save as new want 376 px against the 332 px the panel
        // gives them, and a FlatButton reports its preferred width as its minimum, so
        // BoxLayout cannot shrink them and the last one is cut off at the panel edge.
        s.addRow(buttonRow(save, saveAs));
        return s;
    }

    /**
     * The control takes the width, the button keeps its own at the trailing edge.
     *
     * <p>{@link Fields#row} puts its control in the centre, so a second component has to be
     * nested rather than added to the row.
     */
    private static JComponent withTrailing(JComponent control, JComponent trailing) {
        JPanel p = new JPanel(new BorderLayout(Tokens.SM, 0));
        p.setOpaque(false);
        p.add(control, BorderLayout.CENTER);
        p.add(trailing, BorderLayout.EAST);
        return p;
    }

    private AccordionSection buildHeadersSection() {
        AccordionSection s = new AccordionSection("Hidden headers", "One header name per line", true);

        headerScope = new SegmentedControl<>(
                List.of(ScopeTarget.REQUEST, ScopeTarget.RESPONSE, ScopeTarget.BOTH),
                ScopeTarget::getDisplayName,
                config.getHeaderScope(),
                t -> {
                    config.setHeaderScope(t);
                    notifyChange(false);
                });
        s.addRow(Fields.row("Applies to", headerScope));

        headersArea = Fields.area(6);
        headersArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { headersEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { headersEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { headersEdited(); }
        });

        JScrollPane scroll = new JScrollPane(headersArea);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.tokens().border));
        scroll.setPreferredSize(new Dimension(10, 108));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 108));
        SlimScrollBarUI.install(scroll);
        s.addRow(scroll);
        s.addLabel("Prefix a name with ! to always show that header");
        return s;
    }

    private AccordionSection buildLayoutSection() {
        AccordionSection s = new AccordionSection("Layout", null, true);

        layoutMode = new SegmentedControl<>(List.of(LayoutMode.SIDE_BY_SIDE, LayoutMode.STACKED),
                LayoutMode::getDisplayName,
                config.getLayoutMode(), m -> {
            config.setLayoutMode(m);
            notifyChange(false);
        });
        s.addRow(Fields.row("Request / Response", layoutMode));

        windowStyle = new SegmentedControl<>(List.of("Caido", "Mac"),
                v -> "Mac".equals(v) ? "Mac dots" : "Caido flat",
                config.getWindowStyle(), v -> {
            config.setWindowStyle(v);
            notifyChange(false);
        });
        s.addRow(Fields.row("URL bar style", windowStyle));

        widthCombo = Fields.combo(WIDTHS);
        widthCombo.setSelectedItem(matchWidth(config.getContentWidth()));
        widthCombo.addActionListener(e -> {
            if (isInitializing) return;
            Object v = widthCombo.getSelectedItem();
            if (v != null) {
                config.setContentWidth((String) v);
                notifyChange(false);
            }
        });
        s.addRow(Fields.row("Width", widthCombo));

        scaleCombo = Fields.combo(SCALES);
        scaleCombo.setSelectedItem(scaleLabel(config.getScaleFactor()));
        scaleCombo.addActionListener(e -> {
            if (isInitializing) return;
            Object v = scaleCombo.getSelectedItem();
            if (v != null) {
                config.setScaleFactor(scaleValue((String) v));
                notifyChange(false);
            }
        });
        s.addRow(Fields.row("Resolution on save / copy", scaleCombo));
        return s;
    }

    private AccordionSection buildCardSection() {
        AccordionSection s = new AccordionSection("Card style", null, false);

        roundedToggle = new SwitchToggle(config.isRoundedCorners(), v -> {
            config.setRoundedCorners(v);
            notifyChange(false);
        });
        s.addRow(labelled("Rounded corners", roundedToggle));

        shadowToggle = new SwitchToggle(config.isDropShadow(), v -> {
            config.setDropShadow(v);
            notifyChange(false);
        });
        s.addRow(labelled("Drop shadow", shadowToggle));

        watermarkField = Fields.text(config.getWatermarkText(), "e.g. CONFIDENTIAL");
        watermarkField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { watermarkEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { watermarkEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { watermarkEdited(); }
        });
        s.addRow(Fields.row("Watermark", watermarkField));
        return s;
    }

    private AccordionSection buildDisplaySection() {
        AccordionSection s = new AccordionSection("Display", null, false);

        urlToggle = new SwitchToggle(config.isShowUrl(), v -> {
            config.setShowUrl(v);
            notifyChange(false);
        });
        s.addRow(labelled("Thanh URL", urlToggle));

        timestampToggle = new SwitchToggle(config.isShowTimestamp(), v -> {
            config.setShowTimestamp(v);
            notifyChange(false);
        });
        s.addRow(labelled("Timestamp", timestampToggle));

        responseInfoToggle = new SwitchToggle(config.isShowResponseInfo(), v -> {
            config.setShowResponseInfo(v);
            notifyChange(false);
        });
        s.addRow(labelled("Size and duration", responseInfoToggle));

        wrapToggle = new SwitchToggle(config.isWrapText(), v -> {
            config.setWrapText(v);
            notifyChange(false);
        });
        s.addRow(labelled("Wrap long lines", wrapToggle));
        return s;
    }

    private AccordionSection buildCropSection() {
        AccordionSection s = new AccordionSection("Line ranges", "For example 1-6, 15-30", false);

        requestRangesField = Fields.text(config.getRequestLineRanges());
        requestRangesField.setToolTipText("Leave empty to show everything. Gaps appear as ···");
        requestRangesField.getDocument().addDocumentListener(rangeListener());

        responseRangesField = Fields.text(config.getResponseLineRanges());
        responseRangesField.setToolTipText("Leave empty to show everything. Gaps appear as ···");
        responseRangesField.getDocument().addDocumentListener(rangeListener());

        s.addRow(Fields.row("Request lines", requestRangesField));
        s.addRow(Fields.row("Response lines", responseRangesField));
        return s;
    }

    private AccordionSection buildColorSection() {
        AccordionSection s = new AccordionSection("Syntax colors", "Tune the color of each part", false);

        colorEditor = new SyntaxColorEditor();
        colorEditor.setConfig(config);
        colorEditor.setOnChange(() -> notifyChange(false));
        colorEditor.setPreferredSize(new Dimension(10, 320));
        s.addRow(colorEditor);
        return s;
    }

    // ------------------------------------------------------------------ rules tab

    private JComponent buildRulesTab() {
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setOpaque(false);
        stack.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.XL, Tokens.MD));

        stack.add(ruleGroupHeader("Highlights", "Mark the things that matter", this::addHighlight, true));
        stack.add(highlightsContainer);
        stack.add(Box.createVerticalStrut(Tokens.MD));

        stack.add(ruleGroupHeader("Redactions", "Mask sensitive values", this::addRedaction, false));
        stack.add(redactionsContainer);
        stack.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(stack);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        SlimScrollBarUI.install(scroll);

        rebuildRules();
        return scroll;
    }

    private JComponent ruleGroupHeader(String title, String hint,
                                       Runnable onAdd, boolean highlightGroup) {
        JPanel header = new JPanel(new BorderLayout(Tokens.SM, 0));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, 0, Tokens.SM, 0));

        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        JLabel name = Fields.primary(title);
        name.setFont(Theme.tokens().uiBold);
        titles.add(name, BorderLayout.NORTH);
        titles.add(Fields.muted(hint), BorderLayout.SOUTH);
        header.add(titles, BorderLayout.CENTER);

        JButton preset = Buttons.secondary("Presets", Icons.chevronDown(null, 12));
        preset.addActionListener(e -> showPresetMenu(preset, highlightGroup));

        JButton add = Buttons.secondary("Add", Icons.plus(null, 13));
        add.addActionListener(e -> onAdd.run());

        header.add(buttonRow(preset, add), BorderLayout.EAST);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        return header;
    }

    private void showPresetMenu(JComponent anchor, boolean highlightGroup) {
        JPopupMenu menu = new JPopupMenu();
        for (RuleCard.Preset preset : RuleCard.Preset.values()) {
            if (preset.isHighlight() != highlightGroup) continue;
            JMenuItem item = new JMenuItem(preset.label());
            item.addActionListener(e -> {
                if (preset.isHighlight()) {
                    HighlightRule rule = new HighlightRule(preset.pattern(), true,
                            ScopeTarget.BOTH, preset.colorHex());
                    config.getHighlights().add(rule);
                    rebuildRules();
                } else {
                    RedactionRule rule = new RedactionRule(preset.pattern(), true,
                            ScopeTarget.BOTH, preset.mode(), preset.captureGroup());
                    config.getRedactions().add(rule);
                    rebuildRules();
                }
                notifyChange(false);
            });
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void addHighlight() {
        config.getHighlights().add(new HighlightRule());
        rebuildRules();
        notifyChange(false);
    }

    private void addRedaction() {
        config.getRedactions().add(new RedactionRule());
        rebuildRules();
        notifyChange(false);
    }

    private void rebuildRules() {
        highlightsContainer.removeAll();
        List<HighlightRule> highlights = config.getHighlights();
        for (int i = 0; i < highlights.size(); i++) {
            HighlightRule rule = highlights.get(i);
            int index = i;
            highlightsContainer.add(RuleCard.forHighlight(rule, new RuleCard.Listener() {
                @Override public void changed() { notifyChange(false); }
                @Override public void deleted() {
                    config.getHighlights().remove(index);
                    rebuildRules();
                    notifyChange(false);
                }
            }));
            highlightsContainer.add(Box.createVerticalStrut(Tokens.SM));
        }

        redactionsContainer.removeAll();
        List<RedactionRule> redactions = config.getRedactions();
        for (int i = 0; i < redactions.size(); i++) {
            RedactionRule rule = redactions.get(i);
            int index = i;
            redactionsContainer.add(RuleCard.forRedaction(rule, new RuleCard.Listener() {
                @Override public void changed() { notifyChange(false); }
                @Override public void deleted() {
                    config.getRedactions().remove(index);
                    rebuildRules();
                    notifyChange(false);
                }
            }));
            redactionsContainer.add(Box.createVerticalStrut(Tokens.SM));
        }

        if (highlights.isEmpty()) {
            highlightsContainer.add(Fields.capHeight(Fields.muted("No rules yet.")));
        }
        if (redactions.isEmpty()) {
            redactionsContainer.add(Fields.capHeight(Fields.muted("No rules yet.")));
        }

        highlightsContainer.revalidate();
        highlightsContainer.repaint();
        redactionsContainer.revalidate();
        redactionsContainer.repaint();
    }

    // ------------------------------------------------------------------ template io

    private void reloadTemplates() {
        templateManager.load();
        isInitializing = true;
        templateCombo.setModel(new javax.swing.DefaultComboBoxModel<>(
                templateManager.getTemplateNames().toArray(new String[0])));
        templateCombo.setSelectedItem(config.getName());
        isInitializing = false;
    }

    private void saveCurrent() {
        templateManager.saveTemplate(config);
        reloadTemplates();
        notifyChange(false);
    }

    private void saveAsNew() {
        String name = PromptDialog.show(this, "Save as a new template", "New template name:", config.getName());
        if (name == null) return;

        TemplateConfig copy = config.copy();
        copy.setName(name);
        templateManager.saveTemplate(copy);
        config = copy;
        loadConfig(copy, true);
        reloadTemplates();
    }

    // ------------------------------------------------------------------ state

    /** The live config. Callers must not replace it. */
    public TemplateConfig getConfig() { return config; }

    public void setConfig(TemplateConfig next) {
        loadConfig(next, true);
    }

    private void loadConfig(TemplateConfig next, boolean templateSwitched) {
        if (next == null) return;
        isInitializing = true;
        this.config = next;

        templateCombo.setSelectedItem(next.getName());
        headerScope.setSelected(next.getHeaderScope(), true);
        headersArea.setText(next.getHeadersToHide() != null ? next.getHeadersToHide() : "");

        layoutMode.setSelected(next.getLayoutMode(), true);
        windowStyle.setSelected(next.getWindowStyle(), true);
        widthCombo.setSelectedItem(matchWidth(next.getContentWidth()));
        scaleCombo.setSelectedItem(scaleLabel(next.getScaleFactor()));

        roundedToggle.setSelected(next.isRoundedCorners(), true);
        shadowToggle.setSelected(next.isDropShadow(), true);
        watermarkField.setText(next.getWatermarkText() != null ? next.getWatermarkText() : "");

        urlToggle.setSelected(next.isShowUrl(), true);
        timestampToggle.setSelected(next.isShowTimestamp(), true);
        responseInfoToggle.setSelected(next.isShowResponseInfo(), true);
        wrapToggle.setSelected(next.isWrapText(), true);

        requestRangesField.setText(next.getRequestLineRanges() != null ? next.getRequestLineRanges() : "");
        responseRangesField.setText(next.getResponseLineRanges() != null ? next.getResponseLineRanges() : "");

        if (colorEditor != null) colorEditor.setConfig(next);

        isInitializing = false;
        rebuildRules();
        notifyChange(templateSwitched);
    }

    private void notifyChange(boolean templateSwitched) {
        if (!ready || isInitializing || listener == null) return;
        listener.configChanged(config, templateSwitched);
    }

    private void headersEdited() {
        if (isInitializing) return;
        config.setHeadersToHide(headersArea.getText());
        notifyChange(false);
    }

    private void watermarkEdited() {
        if (isInitializing) return;
        config.setWatermarkText(watermarkField.getText());
        notifyChange(false);
    }

    private DocumentListener rangeListener() {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { rangesEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { rangesEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { rangesEdited(); }
        };
    }

    private void rangesEdited() {
        if (isInitializing) return;
        config.setRequestLineRanges(requestRangesField.getText().trim());
        config.setResponseLineRanges(responseRangesField.getText().trim());
        notifyChange(false);
    }

    // ------------------------------------------------------------------ helpers

    /** A settings row with the label on the left and a switch on the right. */
    private JComponent labelled(String label, JComponent control) {
        JPanel row = new JPanel(new BorderLayout(Tokens.SM, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(Fields.secondary(label), BorderLayout.WEST);
        row.add(control, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return row;
    }

    private JComponent buttonRow(JComponent... buttons) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < buttons.length; i++) {
            if (i > 0) row.add(Box.createHorizontalStrut(Tokens.SM));
            row.add(buttons[i]);
        }
        row.add(Box.createHorizontalGlue());
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                buttons.length > 0 ? buttons[0].getPreferredSize().height : 28));
        return row;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Maps a stored width label onto a known preset, tolerating an unknown old value. */
    private static String matchWidth(String stored) {
        if (stored != null) {
            for (String w : WIDTHS) {
                if (w.equalsIgnoreCase(stored)) return w;
            }
        }
        return WIDTHS[1];
    }

    private static String scaleLabel(double scale) {
        if (scale >= 3.0) return "3x";
        if (scale >= 2.0) return "2x";
        return "1x";
    }

    private static double scaleValue(String label) {
        if ("3x".equals(label)) return 3.0;
        if ("2x".equals(label)) return 2.0;
        return 1.0;
    }

    /**
     * The panel ground behind the cards.
     *
     * <p>Resolved per call rather than set once, because Montoya fires no event on a theme
     * change. Without it the panel paints Burp's own panel colour, which is close to the
     * tokens but not one of them, and shows as a light band in a light theme's dark render.
     */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }
}
