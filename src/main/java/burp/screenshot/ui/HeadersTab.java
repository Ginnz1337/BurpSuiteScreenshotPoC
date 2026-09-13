package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.TextProcessor;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The Headers tab: the names to hide, the side they apply to, and the lines to leave out.
 *
 * <p>A class of its own rather than a method of {@link SettingsDialog}, because a dialog cannot
 * be built without a display and this is the tab whose layout has to be measured. Extracted, the
 * verification run can build it, lay it out and read the result.
 *
 * <p>Everything below the list is a single line high. The tab used to spend three stacked rows on
 * a three-line caption, a button and a full-width segmented control, which left the list itself
 * with less room than the words explaining it.
 *
 * <p>The two line fields live here rather than on a tab of their own because they answer the same
 * question the header list answers: what should the PoC not carry.
 */
public final class HeadersTab extends JPanel {

    private final TemplateConfig config;
    private final HttpExchangeData data;

    /** Called while the user types: the apply is debounced, because this fires per keystroke. */
    private final Runnable schedule;

    /** Called when a control that commits in one step changed something. */
    private final Runnable apply;

    private final JTextArea headerArea = Fields.area(9);

    public HeadersTab(TemplateConfig config, HttpExchangeData data, Runnable schedule, Runnable apply) {
        super(new BorderLayout(0, Tokens.SM));
        this.config = config;
        this.data = data;
        this.schedule = schedule;
        this.apply = apply;

        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD));

        headerArea.setText(config.getHeadersToHide() == null ? "" : config.getHeadersToHide());
        headerArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onHeaderTextEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { onHeaderTextEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { onHeaderTextEdited(); }
        });

        // The list takes everything the controls below do not. It is the part the user types
        // into, and it used to be capped at 260 pixels with the rest of the dialog left empty.
        add(Fields.scroll(headerArea), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);
    }

    /** The header list as the user last saw it, for the host to persist. */
    public String getHeaderText() { return headerArea.getText(); }

    // ------------------------------------------------------------------ controls

    private JComponent buildControls() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(true);

        column.add(buildHintRow());
        column.add(Box.createVerticalStrut(Tokens.SM));
        column.add(buildScopeRow());
        column.add(Box.createVerticalStrut(Tokens.SM));
        column.add(buildLineRow());
        return column;
    }

    /**
     * What the syntax means, and the button that fills the list in.
     *
     * <p>The caption is one clause and its detail is in the tooltip. Three lines of explanation
     * above a list is a tab where the instructions are bigger than the thing they describe.
     */
    private JComponent buildHintRow() {
        JLabel hint = Fields.muted("One name per line.  * suffix = prefix,  ! prefix = always show.");
        hint.setToolTipText("One header name per line. A trailing * matches a prefix, a leading ! "
                + "always shows the header. Matching ignores case. "
                + "This list is what gets hidden, so delete the ones you want to keep.");

        // The names the message actually carries are the ones worth offering, so the box offers
        // them and accepts a typed name in the same place. Hidden until the button is pressed,
        // which is what keeps the row one line high while nobody is adding anything.
        JComboBox<String> nameEntry = Fields.editableCombo(messageHeaderNames());
        nameEntry.setVisible(false);

        JButton addName = Buttons.secondary("Add names from this message", Icons.plus(null, 14));
        addName.setToolTipText("Pick one of this message's header names, or type any name, and "
                + "press Enter.");

        addName.addActionListener(e -> {
            addName.setVisible(false);
            nameEntry.setVisible(true);
            revalidate();
            repaint();
            // The editor, not the combo: the combo would take the focus and swallow the caret,
            // and a box that looks ready for typing has to actually be ready for typing.
            if (nameEntry.getEditor().getEditorComponent() instanceof JComponent editor) {
                editor.requestFocusInWindow();
            }
        });

        Runnable close = () -> {
            nameEntry.setSelectedItem("");
            nameEntry.setVisible(false);
            addName.setVisible(true);
            revalidate();
            repaint();
        };

        nameEntry.addActionListener(e -> {
            Object item = nameEntry.getEditor().getItem();
            String typed = item == null ? "" : String.valueOf(item).trim();
            if (!typed.isEmpty()) {
                config.addHeaderToHide(typed);
                headerArea.setText(config.getHeadersToHide());
                apply.run();
            }
            close.run();
        });
        nameEntry.getEditor().getEditorComponent().addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) close.run();
            }
        });

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SM, 0));
        right.setOpaque(false);
        right.add(addName);
        right.add(nameEntry);

        JPanel row = new JPanel(new BorderLayout(Tokens.SM, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(hint, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        return Fields.capHeight(row);
    }

    /** Which side of the exchange the header list applies to. */
    private JComponent buildScopeRow() {
        JComboBox<ScopeTarget> scope = Fields.combo(
                new ScopeTarget[]{ScopeTarget.BOTH, ScopeTarget.REQUEST, ScopeTarget.RESPONSE});
        scope.setSelectedItem(config.getHeaderScope());
        scope.setToolTipText("Which side of the exchange the header list applies to.");
        scope.setPreferredSize(new Dimension(120, 26));
        scope.setMaximumSize(new Dimension(120, 26));
        scope.addActionListener(e -> {
            if (scope.getSelectedItem() instanceof ScopeTarget target) {
                config.setHeaderScope(target);
                apply.run();
            }
        });

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SM, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(Fields.muted("Apply the list to"));
        row.add(scope);
        return Fields.capHeight(row);
    }

    /**
     * The lines to leave out, one field per side.
     *
     * <p>Each field reports what it does to the message behind the dialog as it is typed. The
     * count comes from the same call the view uses, so the two cannot disagree, and a line that
     * was never going to be removed says so before the user closes the dialog to go and look.
     *
     * <p>The count is short and the entries the renderer could not read are named in the tooltip:
     * a label that grows to fit a sentence is a label that pushes the row it sits in past the
     * edge of the dialog.
     */
    private JComponent buildLineRow() {
        String tip = "Lines to leave out, numbered as the gutter shows them. "
                + "Examples: 17-25, 30-38. Empty or all removes nothing.";

        JTextField request = sized(Fields.text(config.getRequestLineRanges(), ""));
        JTextField response = sized(Fields.text(config.getResponseLineRanges(), ""));
        request.setToolTipText(tip);
        response.setToolTipText(tip);

        JLabel requestFeedback = Fields.muted("");
        JLabel responseFeedback = Fields.muted("");

        onLineEdit(request, true, requestFeedback);
        onLineEdit(response, false, responseFeedback);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SM, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(Fields.muted("Remove lines"));
        row.add(Fields.secondary("req"));
        row.add(request);
        row.add(requestFeedback);
        row.add(Fields.secondary("resp"));
        row.add(response);
        row.add(responseFeedback);
        return Fields.capHeight(row);
    }

    /** A range field, wide enough for "17-25, 30-38" and no wider. */
    private static JTextField sized(JTextField field) {
        Dimension size = new Dimension(112, 24);
        field.setPreferredSize(size);
        field.setMinimumSize(size);
        field.setMaximumSize(size);
        return field;
    }

    // ------------------------------------------------------------------ behaviour

    /** The header names this message carries, request first, without duplicates. */
    private String[] messageHeaderNames() {
        List<String> names = new ArrayList<>();
        if (data != null) {
            names.addAll(TextProcessor.headerNames(data.getRawRequest()));
            for (String name : TextProcessor.headerNames(data.getRawResponse())) {
                boolean seen = false;
                for (String existing : names) {
                    if (existing.equalsIgnoreCase(name)) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) names.add(name);
            }
        }
        return names.toArray(new String[0]);
    }

    /** Writes the field through to the config and reports what it does to the message. */
    private void onLineEdit(JTextField field, boolean isRequest, JLabel feedback) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyLineEdit(); }
            @Override public void removeUpdate(DocumentEvent e) { applyLineEdit(); }
            @Override public void changedUpdate(DocumentEvent e) { applyLineEdit(); }

            private void applyLineEdit() {
                if (isRequest) config.setRequestLineRanges(field.getText());
                else config.setResponseLineRanges(field.getText());
                updateLineFeedback(isRequest, feedback);
                schedule.run();
            }
        });
        updateLineFeedback(isRequest, feedback);
    }

    /**
     * Says what the field does to the message behind the dialog, as it is typed.
     *
     * <p>An entry the renderer could not read is named instead of passing silently, which is what
     * made the old field look like it had worked when it had not.
     */
    private void updateLineFeedback(boolean isRequest, JLabel feedback) {
        String raw = data == null ? null : (isRequest ? data.getRawRequest() : data.getRawResponse());
        if (raw == null || raw.isEmpty()) {
            feedback.setText("");
            feedback.setToolTipText(null);
            return;
        }

        TextProcessor.ProcessedHttp processed = isRequest
                ? TextProcessor.processRequest(raw, config)
                : TextProcessor.processResponse(raw, config);

        List<String> ignored = processed.ignoredRangeEntries;
        int count = processed.removedLineCount;

        String summary = ignored.isEmpty()
                ? (count == 0 ? "none" : (count == 1 ? "1 removed" : count + " removed"))
                : (count + " removed, " + ignored.size() + " ignored");
        feedback.setText(summary);
        feedback.setToolTipText(ignored.isEmpty()
                ? (count == 1 ? "1 line removed." : count + " lines removed.")
                : describeIgnored(ignored));
    }

    private static String describeIgnored(List<String> ignored) {
        StringBuilder text = new StringBuilder(ignored.size() == 1
                ? "1 entry could not be read: " : ignored.size() + " entries could not be read: ");
        for (int i = 0; i < ignored.size(); i++) {
            if (i > 0) text.append(", ");
            text.append('"').append(ignored.get(i)).append('"');
        }
        return text.toString();
    }

    private void onHeaderTextEdited() {
        config.setHeadersToHide(headerArea.getText());
        schedule.run();
    }

    /** The ground the dialog resolves, so the tab does not paint Burp's panel colour. */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }
}
