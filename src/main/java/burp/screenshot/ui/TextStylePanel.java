package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.Theme;
import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.SyntaxHighlighter;
import burp.screenshot.engine.TextProcessor;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SegmentedControl;
import burp.screenshot.ui.components.SlimScrollBarUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The message as real, editable text, colored by the same palette the renderer uses.
 *
 * <p>It exists because a rendered image cannot be searched or copied. The colors come from
 * {@link SyntaxPalette} and the token boundaries from {@link SyntaxHighlighter}, so what is
 * on screen cannot drift from what the exported card shows.
 *
 * <p>The line numbers shown in the gutter are the ones from the original message, not the
 * document's own indices: hidden headers leave gaps in the numbering, and the card shows the
 * original numbers, so the two views must agree. Once the text is edited that mapping is gone,
 * so the gutter falls back to plain 1..n numbering.
 *
 * <p>Typing does not rebuild the document. A rebuild replaces every character, which resets the
 * caret to the top of the pane and fights the user's own edit. Only the edited line is
 * re-tokenized, and the caret is left where it is.
 */
public class TextStylePanel extends JPanel {

    /** Which half of the exchange is on screen. */
    public enum Side {
        REQUEST("Request"),
        RESPONSE("Response");

        private final String label;
        Side(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final JTextPane pane;
    private final Gutter gutter;
    private final JScrollPane scroll;
    private final SegmentedControl<Side> sideSwitch;
    private final JTextField searchField;
    private final JLabel matchLabel;
    private final JButton resetButton;

    /** Told when the user discards their edits, so the host can drop its own copy. */
    private Runnable resetListener;

    private HttpExchangeData exchangeData;
    private TemplateConfig config;
    private Side side = Side.REQUEST;

    /** Original 1-based line number per document line; -1 where there is none. */
    private final List<Integer> lineNumbers = new ArrayList<>();

    /** Grammar per document line, so one edited line can be re-tokenized on its own. */
    private final List<SyntaxHighlighter.LineKind> lineKinds = new ArrayList<>();

    /** Set while the document is being rebuilt, to keep the edit listener out of it. */
    private boolean rebuilding;
    private final Timer editDebounce;
    private Consumer<String> editListener;

    /**
     * The document as {@link #rebuild()} left it, which is the side after {@link TextProcessor}
     * has hidden headers and cropped line ranges.
     *
     * <p>Comparing the document with the raw message would call every processed message edited,
     * because the processor is what removed those lines in the first place. What the user is
     * editing is the text on screen, so that is what "edited" is measured against.
     */
    private String pristineText = "";

    /** Character range of the most recent edit, used to restyle only what changed. */
    private int pendingEditFrom;
    private int pendingEditTo;

    private AutoCloseable themeHandle;

    public TextStylePanel() {
        super(new BorderLayout());
        setOpaque(true);

        pane = new JTextPane() {
            /**
             * Without this the pane always matches the viewport width and long header values
             * wrap, which would desynchronise the gutter from the text.
             */
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return false;
            }
        };
        pane.setEditable(true);
        pane.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD));
        pane.setOpaque(true);

        // Restyling and re-rendering on every keystroke would fight the typist, so the work
        // waits for a pause. Restarting the timer on each change is what makes that a pause
        // rather than a fixed delay after the first key.
        editDebounce = new Timer(150, e -> onEditSettled());
        editDebounce.setRepeats(false);
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                scheduleEdit(e.getOffset(), e.getOffset() + e.getLength());
            }
            @Override public void removeUpdate(DocumentEvent e) {
                scheduleEdit(e.getOffset(), e.getOffset());
            }
            @Override public void changedUpdate(DocumentEvent e) {
                scheduleEdit(e.getOffset(), e.getOffset() + e.getLength());
            }
        });

        gutter = new Gutter();
        scroll = new JScrollPane(pane);
        scroll.setBorder(null);
        // The pane stops at the width of its longest line, so anything past that is the
        // viewport. Left opaque it paints the LAF's own panel colour, which reads as a bright
        // band down the right of the text.
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setRowHeaderView(gutter);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        SlimScrollBarUI.install(scroll);

        sideSwitch = new SegmentedControl<>(List.of(Side.REQUEST, Side.RESPONSE),
                Side::toString, Side.REQUEST, s -> {
            side = s;
            rebuild();
        });

        searchField = Fields.text("", "Search in the text (Ctrl+F)");
        searchField.setPreferredSize(new Dimension(160, 26));
        searchField.setMaximumSize(new Dimension(220, 26));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applySearch(); }
            @Override public void removeUpdate(DocumentEvent e) { applySearch(); }
            @Override public void changedUpdate(DocumentEvent e) { applySearch(); }
        });

        matchLabel = Fields.muted("");

        // Reset sits here rather than in the host's toolbar, because this header is the one
        // that already owns the side switch: the button acts on whichever side is showing.
        resetButton = Buttons.secondary("Reset", Icons.refresh(null, 14));
        resetButton.setToolTipText("Discard the edits and show Burp's own message again");
        resetButton.addActionListener(e -> {
            resetEdits();
            if (resetListener != null) resetListener.run();
        });

        add(buildHeader(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    private JComponent buildHeader() {
        JPanel bar = new JPanel(new BorderLayout(Tokens.MD, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.tokens().border),
                BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD)));

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, Tokens.SM, 0));
        right.setOpaque(false);
        right.add(searchField);
        right.add(matchLabel);
        right.add(resetButton);

        bar.add(sideSwitch, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeHandle == null) {
            // StyledDocument attributes bake the color in, so a theme switch means a rebuild.
            themeHandle = Theme.addListener(() -> SwingUtilities.invokeLater(this::rebuild));
        }
    }

    @Override
    public void removeNotify() {
        if (themeHandle != null) {
            try {
                themeHandle.close();
            } catch (Exception ignored) {
                // Already removed.
            }
            themeHandle = null;
        }
        super.removeNotify();
    }

    // ------------------------------------------------------------------ data

    public void updateData(HttpExchangeData data, TemplateConfig templateConfig) {
        this.exchangeData = data;
        this.config = templateConfig;
        rebuild();
    }

    public void setSide(Side value) {
        sideSwitch.setSelected(value, true);
        side = value;
        rebuild();
    }

    /** Puts the caret in the search box, used by the panel's keyboard shortcut. */
    public void focusSearch() {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    /** Called with the whole side's text whenever an edit settles. */
    public void setEditListener(Consumer<String> listener) {
        this.editListener = listener;
    }

    /** Called after Reset has restored the message, with the side already rebuilt. */
    public void setResetListener(Runnable listener) {
        this.resetListener = listener;
    }

    /** The text as it stands on screen, edited or not. */
    public String getEditedText() {
        try {
            return pane.getDocument().getText(0, pane.getDocument().getLength());
        } catch (BadLocationException impossible) {
            return "";
        }
    }

    public boolean isEdited() {
        if (exchangeData == null) return false;
        return !normalize(getEditedText()).equals(normalize(pristineText));
    }

    /** Discards the edits and puts the message back as Burp reported it. */
    public void resetEdits() {
        rebuild();
    }

    /**
     * Comparison is on line endings and the trailing newline only.
     *
     * <p>The document cannot hold a raw CRLF, so an untouched CRLF message would otherwise
     * report itself as edited the moment it is loaded.
     */
    private static String normalize(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }

    public Side getSide() { return side; }

    /**
     * The ground behind the header and behind the text card.
     *
     * <p>Resolved per call rather than set once, because Montoya fires no event on a theme
     * change. The pane itself stays opaque and paints {@code bgCard} on top of this. The strip
     * to the right of the pane is this too, because the pane stops at the width of its longest
     * line, and {@code bgPanel} is the closest token to {@code bgCard}: the step between the
     * two reads as the edge of the text rather than as a second panel.
     */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }

    // ------------------------------------------------------------------ editing

    private void scheduleEdit(int from, int to) {
        if (rebuilding) return;
        pendingEditFrom = Math.max(0, from);
        pendingEditTo = Math.max(0, to);
        editDebounce.restart();
    }

    private void onEditSettled() {
        if (rebuilding || config == null) return;

        restyleEditedLines();
        // The original line numbering no longer maps onto the document, so the gutter switches
        // to plain numbering rather than showing numbers that point at the wrong lines.
        renumberGutter();
        applySearch();

        if (editListener != null) editListener.accept(getEditedText());
    }

    /**
     * Re-tokenizes the lines an edit touched.
     *
     * <p>Rebuilding the whole document would move the caret and drop the selection, which is
     * unusable while typing. A paste can span lines, so the range is everything from the line
     * the edit started on to the line it ended on.
     */
    private void restyleEditedLines() {
        StyledDocument doc = pane.getStyledDocument();
        Element root = doc.getDefaultRootElement();
        if (root.getElementCount() == 0) return;

        int first = root.getElementIndex(Math.min(pendingEditFrom, Math.max(0, doc.getLength())));
        int last = root.getElementIndex(Math.min(pendingEditTo, Math.max(0, doc.getLength())));
        last = Math.max(first, Math.min(last, root.getElementCount() - 1));

        List<String> lines = documentLines();
        List<SyntaxHighlighter.LineKind> kinds =
                SyntaxHighlighter.kindsFor(lines, side == Side.RESPONSE);

        Tokens t = Theme.tokens();
        SyntaxPalette palette = effectivePalette();

        for (int index = first; index <= last && index < root.getElementCount(); index++) {
            Element line = root.getElement(index);
            String text;
            try {
                text = doc.getText(line.getStartOffset(), line.getEndOffset() - line.getStartOffset());
            } catch (BadLocationException impossible) {
                continue;
            }

            SyntaxHighlighter.LineKind kind = index < kinds.size()
                    ? kinds.get(index)
                    : SyntaxHighlighter.LineKind.BODY;

            // The whole line is reset first, so a run that is no longer a header stops being
            // coloured like one.
            doc.setCharacterAttributes(line.getStartOffset(),
                    line.getEndOffset() - line.getStartOffset(),
                    styleFor(TokenType.TEXT, t, palette), true);

            int cursor = line.getStartOffset();
            for (SyntaxHighlighter.Token token : SyntaxHighlighter.tokenize(text, kind)) {
                doc.setCharacterAttributes(cursor, token.text.length(),
                        styleFor(token.type, t, palette), true);
                cursor += token.text.length();
            }
        }

        lineKinds.clear();
        lineKinds.addAll(kinds);
    }

    private List<String> documentLines() {
        StyledDocument doc = pane.getStyledDocument();
        Element root = doc.getDefaultRootElement();
        List<String> lines = new ArrayList<>(root.getElementCount());
        for (int i = 0; i < root.getElementCount(); i++) {
            Element e = root.getElement(i);
            try {
                lines.add(doc.getText(e.getStartOffset(), e.getEndOffset() - e.getStartOffset()));
            } catch (BadLocationException impossible) {
                lines.add("");
            }
        }
        return lines;
    }

    private void renumberGutter() {
        if (lineKinds.isEmpty()) return;
        lineNumbers.clear();
        for (int i = 0; i < lineKinds.size(); i++) lineNumbers.add(i + 1);
        gutter.refreshMetrics();
        gutter.repaint();
    }

    // ------------------------------------------------------------------ build

    private void rebuild() {
        StyledDocument doc = pane.getStyledDocument();
        lineNumbers.clear();
        lineKinds.clear();
        editDebounce.stop();
        rebuilding = true;

        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // An empty document cannot throw, and a failed clear is recoverable by the insert.
        }

        Tokens t = Theme.tokens();
        SyntaxPalette palette = effectivePalette();
        pane.setBackground(t.bgCard);
        pane.setCaretColor(t.accent);
        pane.setSelectionColor(Tokens.alpha(t.accent, 110));
        pane.setSelectedTextColor(t.textPrimary);
        pane.setFont(t.code);

        if (exchangeData != null && config != null) {
            String raw = side == Side.REQUEST
                    ? exchangeData.getRawRequest()
                    : exchangeData.getRawResponse();
            TextProcessor.ProcessedHttp processed = side == Side.REQUEST
                    ? TextProcessor.processRequest(raw, config)
                    : TextProcessor.processResponse(raw, config);
            appendLines(doc, processed, t, palette);
        }

        if (lineNumbers.isEmpty()) lineNumbers.add(-1);

        pristineText = getEditedText();

        gutter.refreshMetrics();
        gutter.repaint();
        pane.setCaretPosition(0);
        applySearch();
        rebuilding = false;
    }

    private void appendLines(StyledDocument doc, TextProcessor.ProcessedHttp processed,
                             Tokens t, SyntaxPalette palette) {
        if (processed == null || processed.lines == null) return;

        boolean first = true;
        for (TextProcessor.LineItem item : processed.lines) {
            if (!first) {
                insert(doc, "\n", plain(t, palette));
            }
            first = false;

            lineNumbers.add(item.isOmission ? -1 : item.originalLineNumber);

            String line = item.text != null ? item.text : "";
            SyntaxHighlighter.LineKind kind = item.isOmission
                    ? SyntaxHighlighter.LineKind.OMISSION
                    : item.kind;
            lineKinds.add(kind);
            for (SyntaxHighlighter.Token token : SyntaxHighlighter.tokenize(line, kind)) {
                insert(doc, token.text, styleFor(token.type, t, palette));
            }
        }
    }

    private void insert(StyledDocument doc, String text, SimpleAttributeSet attrs) {
        if (text == null || text.isEmpty()) return;
        try {
            doc.insertString(doc.getLength(), text, attrs);
        } catch (BadLocationException ignored) {
            // Cannot happen for an append at the end of the document.
        }
    }

    private SimpleAttributeSet plain(Tokens t, SyntaxPalette palette) {
        return styleFor(TokenType.TEXT, t, palette);
    }

    private SimpleAttributeSet styleFor(TokenType type, Tokens t, SyntaxPalette palette) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, Tokens.MONO_FAMILY);
        StyleConstants.setFontSize(a, t.code.getSize());
        StyleConstants.setBold(a, SyntaxPalette.isBold(type));
        StyleConstants.setForeground(a, palette.color(type));
        return a;
    }

    /** Built-in colors for the current theme, with the template's overrides on top. */
    private SyntaxPalette effectivePalette() {
        SyntaxPalette p = Theme.isDark() ? SyntaxPalette.DARK.copy() : SyntaxPalette.LIGHT.copy();
        if (config != null) p.applyHexMap(config.getSyntaxColors());
        return p;
    }

    // ------------------------------------------------------------------ search

    private void applySearch() {
        Highlighter highlighter = pane.getHighlighter();
        highlighter.removeAllHighlights();

        String needle = searchField.getText();
        if (needle == null || needle.isEmpty()) {
            matchLabel.setText("");
            return;
        }

        String haystack;
        try {
            haystack = pane.getDocument().getText(0, pane.getDocument().getLength());
        } catch (BadLocationException e) {
            return;
        }

        String lowerHay = haystack.toLowerCase(Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);

        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(
                Tokens.alpha(Theme.tokens().accent, 90));

        int count = 0;
        int first = -1;
        int at = lowerHay.indexOf(lowerNeedle);
        while (at >= 0) {
            try {
                highlighter.addHighlight(at, at + needle.length(), painter);
            } catch (BadLocationException ignored) {
                break;
            }
            if (first < 0) first = at;
            count++;
            at = lowerHay.indexOf(lowerNeedle, at + Math.max(1, needle.length()));
        }

        matchLabel.setText(count == 1 ? "1 match" : count + " matches");

        if (first >= 0) {
            pane.setCaretPosition(first);
            scrollTo(first);
        }
    }

    private void scrollTo(int offset) {
        try {
            Rectangle2D r = pane.modelToView2D(offset);
            if (r == null) return;
            Rectangle target = r.getBounds();
            target.grow(0, 40);
            pane.scrollRectToVisible(target);
        } catch (BadLocationException ignored) {
            // The offset came from the live document, so this cannot happen.
        }
    }

    // ------------------------------------------------------------------ gutter

    /**
     * Line numbers down the left edge.
     *
     * <p>Positions come from {@code modelToView2D} rather than a computed line height, so the
     * numbers stay aligned if the editor kit ever reports a different line spacing.
     */
    private final class Gutter extends JComponent {

        private Font font = Theme.tokens().code;
        private FontMetrics metrics = getFontMetrics(font);
        private int width = 44;

        void refreshMetrics() {
            font = Theme.tokens().code;
            metrics = getFontMetrics(font);
            int widest = metrics.stringWidth(String.valueOf(Math.max(100, lineNumbers.size())));
            width = Math.max(38, widest + Tokens.LG);
            setPreferredSize(new Dimension(width, 10));
            revalidate();
        }

        @Override
        public Dimension getPreferredSize() { return new Dimension(width, 10); }

        @Override
        public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Tokens t = Theme.tokens();
            g2.setColor(t.bgGutter);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(t.border);
            g2.fillRect(getWidth() - 1, 0, 1, getHeight());

            g2.setFont(font);
            g2.setColor(effectivePalette().color(TokenType.LINE_NUMBER));

            Element root = pane.getDocument().getDefaultRootElement();
            int count = Math.min(root.getElementCount(), lineNumbers.size());

            for (int i = 0; i < count; i++) {
                Integer number = lineNumbers.get(i);
                if (number == null || number <= 0) continue;

                Element line = root.getElement(i);
                Rectangle2D r;
                try {
                    r = pane.modelToView2D(line.getStartOffset());
                } catch (BadLocationException e) {
                    continue;
                }
                if (r == null) continue;

                int baseline = (int) Math.round(r.getY() + metrics.getAscent());
                String text = String.valueOf(number);
                g2.drawString(text, getWidth() - Tokens.SM - metrics.stringWidth(text), baseline);
            }

            g2.dispose();
        }
    }

    /** Rebuilt by the host when the panel is first shown, to pick up a late theme change. */
    public void refreshTheme() {
        rebuild();
    }

    /** Exposed so a caller can bind a keyboard shortcut to the search box. */
    public JTextField getSearchField() { return searchField; }
}
