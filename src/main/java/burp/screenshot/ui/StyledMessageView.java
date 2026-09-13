package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.Theme;
import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.Rules;
import burp.screenshot.engine.SyntaxHighlighter;
import burp.screenshot.engine.TextProcessor;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SlimScrollBarUI;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.Highlighter;
import javax.swing.text.ParagraphView;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.Utilities;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One side of an exchange as real, colored text: the view a screenshot is taken from.
 *
 * <p>It replaces the rendered card. The card had to rasterise a bitmap on every change, which
 * is what made the old tab slow; this draws text Burp already knows how to draw, and the
 * redaction marks are painted on top of it. Nothing here allocates an image.
 *
 * <p>Colors come from {@link SyntaxPalette} and token boundaries from
 * {@link SyntaxHighlighter}, the same two sources the header colors in a Repeater screenshot
 * come from, so a header reads the same color here as it does there.
 *
 * <p>The gutter shows the original line numbers, not the document's own indices: hidden
 * headers leave gaps, and a screenshot has to agree with the numbering Burp shows.
 */
public class StyledMessageView extends JPanel {

    /**
     * Opacity of a highlight wash, out of 255.
     *
     * <p>Painted under the glyphs, so raising it darkens the band without touching the text. The
     * default has to survive a screenshot: a wash too faint to see in the PNG is a wash that did
     * not mark anything.
     */
    private static final int HIGHLIGHT_ALPHA = 120;

    /**
     * How hard the redaction wash presses the glyph down towards the background.
     *
     * <p>Set by measuring, not by eye. The verification run renders a redacted value and an
     * unredacted one of the same text, and compares the pixels that are glyph strokes with the
     * background around them. What the constants are chosen against is in that check: the stroke
     * has to come out below the noise floor, so what a reader sees is texture and not a letter.
     *
     * <p>The first setting of this constant left the strokes at a fifth of their contrast, and a
     * fifth is still enough to read a value out of a screenshot. It is now deep enough that what
     * survives the wash is under the noise the second constant lays over it.
     */
    private static final int WASH_ALPHA = 236;

    /**
     * The noise laid over the wash, to break up whatever shape survives it.
     *
     * <p>What makes the value unreadable is the ratio between this and the wash, not either one
     * alone. The glyph comes through at a fraction of its original contrast, and the noise has to
     * be at least that fraction for the strokes to be lost in it rather than merely dimmed: a
     * wash without noise leaves faint but perfectly legible letters.
     */
    private static final int NOISE_ALPHA = 110;

    /**
     * Side of one noise cell, in pixels.
     *
     * <p>Narrow on purpose now. A wide cell leaves whole spans of a glyph unsampled between the
     * cells, and an unsampled stretch is a stroke a reader can still follow; the cell has to be
     * small enough that every part of a glyph is under some cell. Three pixels is under the
     * stroke width of the message font, so no stroke goes through the patch unbroken.
     */
    private static final int NOISE_CELL = 3;

    /** Which half of the exchange is on screen. Fixed for the life of the instance. */
    private final boolean isRequest;

    private final MessagePane pane;
    private final Gutter gutter;
    private final JScrollPane scroll;
    private final JTextField searchField;
    private final JLabel matchLabel;

    /** The search box, its stepper and the count, as one strip for the host toolbar. */
    private final JComponent searchBar;

    /** Where every search hit starts, in document order. The stepper walks this list. */
    private final List<Integer> matchOffsets = new ArrayList<>();

    /** Which entry of {@link #matchOffsets} the view is standing on, -1 when there are none. */
    private int matchIndex = -1;

    /**
     * Length of the text the offsets were found with.
     *
     * <p>Kept because the matches have to be painted again when the stepper moves and the
     * document may have changed shape since; re-reading the field would paint a stale run of
     * offsets against a new needle.
     */
    private int matchLength;

    private HttpExchangeData exchangeData;
    private TemplateConfig config;

    /** Original 1-based line number per document line; -1 where there is none. */
    private final List<Integer> lineNumbers = new ArrayList<>();

    /** Every token of the document, in offset order. The index behind the right-click menu. */
    private final List<Span> spans = new ArrayList<>();

    /** Header names on screen, lowercased, mapped to the spelling the message used. */
    private final Map<String, String> headerNames = new HashMap<>();

    /** Ranges a highlight rule matched, repainted as a translucent wash. */
    private final List<Highlight> highlights = new ArrayList<>();

    /** Ranges a redaction rule matched, painted over the text as an opaque patch. */
    private final List<Redaction> redactions = new ArrayList<>();

    /** Compiled once per rebuild, already filtered to this side of the exchange. */
    private Rules.Result rules = Rules.Result.empty();

    private boolean wrap = true;

    /** Header lines the current settings removed, shown in the host's status label. */
    private int hiddenHeaderCount;

    /** Lines the ranges removed, shown in the same label. */
    private int removedLineCount;

    /** Set by the host: called after the menu changed a rule, so it can save and redraw. */
    private Runnable onRulesChanged;

    /** Set by the host: called before the menu changes a rule, so it can remember the old one. */
    private Runnable onBeforeEdit;

    private AutoCloseable themeHandle;

    public StyledMessageView(boolean isRequest) {
        super(new BorderLayout());
        this.isRequest = isRequest;
        setOpaque(true);

        pane = new MessagePane();
        // Set before the first document, so every paragraph of every message is built by it.
        pane.setEditorKit(new WrappingEditorKit());
        pane.setEditable(false);
        pane.setBorder(BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD));
        pane.setOpaque(true);
        // An invisible caret: this is a view to photograph, and a blinking bar lands in the
        // middle of the very token the screenshot is meant to point at. Selection still works,
        // so the text can be selected, copied and turned into a rule.
        pane.setCaret(new DefaultCaret() {
            @Override public void paint(Graphics g) { }
        });
        pane.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showMenuIfTrigger(e); }
            @Override public void mouseReleased(MouseEvent e) { showMenuIfTrigger(e); }
        });

        gutter = new Gutter();
        scroll = new JScrollPane(pane);
        scroll.setBorder(null);
        // The pane stops at the width of its longest line when wrapping is off, so anything
        // past that is the viewport. Left opaque it paints the LAF's own panel color, which
        // reads as a bright band down the right of the text.
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setRowHeaderView(gutter);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        SlimScrollBarUI.install(scroll);

        searchField = Fields.text("", "Search (Ctrl+F)");
        searchField.setPreferredSize(new Dimension(140, 24));
        searchField.setMaximumSize(new Dimension(200, 24));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applySearch(); }
            @Override public void removeUpdate(DocumentEvent e) { applySearch(); }
            @Override public void changedUpdate(DocumentEvent e) { applySearch(); }
        });
        // Enter steps forward. The box has the focus while the user reads the hits, and moving
        // the hand to the arrow for each one is the cost this saves.
        searchField.addActionListener(e -> stepMatch(1));

        matchLabel = Fields.muted("");
        searchBar = buildSearchBar();

        add(scroll, BorderLayout.CENTER);
    }

    /**
     * The search box, the two arrows and the count, as one strip.
     *
     * <p>One component rather than four pieces the host has to place: the count and the arrows
     * are meaningless apart from the box, and the host had to know the order to build them in.
     */
    private JComponent buildSearchBar() {
        JButton previous = Buttons.iconOnly(Icons.chevronUp(null, 12), "Previous match");
        JButton next = Buttons.iconOnly(Icons.chevronDown(null, 12), "Next match (Enter)");
        previous.addActionListener(e -> stepMatch(-1));
        next.addActionListener(e -> stepMatch(1));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.XS, 0));
        bar.setOpaque(false);
        bar.add(searchField);
        bar.add(previous);
        bar.add(next);
        bar.add(matchLabel);
        return bar;
    }

    // ------------------------------------------------------------------ data

    public void updateData(HttpExchangeData data, TemplateConfig templateConfig) {
        // A different message starts at the top. The same message drawn again under a rule the
        // user just changed keeps them where they were reading. Both used to start at the top,
        // which made every highlight feel like the tab had reloaded.
        boolean sameMessage = sameMessage(data, this.exchangeData);
        this.exchangeData = data;
        this.config = templateConfig;
        rebuild(!sameMessage);
    }

    private static boolean sameMessage(HttpExchangeData a, HttpExchangeData b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return same(a.getRawRequest(), b.getRawRequest())
                && same(a.getRawResponse(), b.getRawResponse());
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Called after the right-click menu edited a rule, so the host can save the template. */
    public void setOnRulesChanged(Runnable listener) {
        this.onRulesChanged = listener;
    }

    /**
     * Called before the right-click menu is shown.
     *
     * <p>Before, not after: the settings the user may want back are the ones they are about to
     * leave. Someone who opened the menu and picked nothing has not changed anything, and the
     * host is expected to sort that out rather than this class tracking whether a rule was
     * really added.
     */
    public void setOnBeforeEdit(Runnable listener) {
        this.onBeforeEdit = listener;
    }

    public boolean isRequestSide() { return isRequest; }

    public int getHiddenHeaderCount() { return hiddenHeaderCount; }

    /** Lines the line ranges took out, for the host's status label. */
    public int getRemovedLineCount() { return removedLineCount; }

    /**
     * The menu the gutter would open, without opening it.
     *
     * <p>For the verification run, which has no display to open a popup on. Null when the gutter
     * has nothing selected, which is also when a press would open nothing.
     */
    public javax.swing.JPopupMenu buildGutterLineMenu() { return gutter.buildLineMenu(); }

    public String getSearchMatchLabel() { return matchLabel.getText(); }

    /**
     * The selected text, for the host editor to report back to Burp.
     *
     * <p>Text only, with no offsets: the view hides header lines, so an offset here is an
     * offset into what is on screen and not into the message Burp is holding. Reporting a
     * range that lands somewhere else would be worse than reporting none.
     */
    public String getSelectedText() {
        int start = pane.getSelectionStart();
        int end = pane.getSelectionEnd();
        return end > start ? textBetween(start, end) : "";
    }

    /** The search box, its stepper and its count, for the host toolbar. */
    public JComponent getSearchControls() { return searchBar; }

    /** Exposed for the verification run, which reads the count without opening the toolbar. */
    public JTextField getSearchField() { return searchField; }

    /** Exposed for the same reason as the search box. */
    public JLabel getMatchLabel() { return matchLabel; }

    // ------------------------------------------------------------------ wrap

    public boolean isWrap() { return wrap; }

    /**
     * Turns line wrapping on or off.
     *
     * <p>No rebuild: wrapping changes where the lines land, not what they say, and rebuilding
     * would throw away the scroll position and the selection for nothing.
     */
    public void setWrap(boolean value) {
        if (wrap == value) return;
        wrap = value;
        pane.revalidate();
        pane.repaint();
        gutter.refreshMetrics();
        gutter.repaint();
    }

    /** Puts the caret in the search box. */
    public void focusSearch() {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeHandle == null) {
            // StyledDocument attributes bake the color in, so a theme switch means a rebuild.
            themeHandle = Theme.addListener(() -> SwingUtilities.invokeLater(this::refreshTheme));
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

    /**
     * The ground behind the text and behind the strip to the right of it.
     *
     * <p>Resolved per call rather than set once, because Montoya fires no event on a theme
     * change. The pane itself stays opaque and paints {@code bgCard} on top of this.
     */
    @Override
    public Color getBackground() { return Theme.tokens().bgPanel; }

    /** Rebuilt by the host when the panel is first shown, to pick up a late theme change. */
    public void refreshTheme() { rebuild(false); }

    // ------------------------------------------------------------------ build

    /**
     * Rebuilds the document.
     *
     * @param resetToTop true when the message itself changed, false when only the settings did.
     *                   In the second case the reader is put back where they were: the rebuild
     *                   clears the document, and clearing it drops the caret to the top and takes
     *                   the viewport with it. Adding a highlight or removing a line is a change
     *                   to what is drawn, not to where the user is looking.
     */
    private void rebuild(boolean resetToTop) {
        int caretBefore = pane.getCaretPosition();
        int topBefore = topVisibleOffset();

        StyledDocument doc = pane.getStyledDocument();
        lineNumbers.clear();
        spans.clear();
        headerNames.clear();
        highlights.clear();
        redactions.clear();
        pane.getHighlighter().removeAllHighlights();

        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {
            // An empty document cannot throw, and a failed clear is recoverable by the insert.
        }

        Tokens t = Theme.tokens();
        SyntaxPalette palette = effectivePalette();
        pane.setBackground(t.bgCard);
        pane.setSelectionColor(Tokens.alpha(t.accent, 110));
        pane.setSelectedTextColor(t.textPrimary);
        pane.setFont(t.code);

        if (exchangeData != null && config != null) {
            String raw = isRequest ? exchangeData.getRawRequest() : exchangeData.getRawResponse();
            TextProcessor.ProcessedHttp processed = isRequest
                    ? TextProcessor.processRequest(raw, config)
                    : TextProcessor.processResponse(raw, config);
            rules = Rules.compile(config).forSide(isRequest);
            appendLines(doc, processed, t, palette);
            hiddenHeaderCount = countHiddenHeaders(raw, processed);
            removedLineCount = processed.removedLineCount;
        } else {
            rules = Rules.Result.empty();
            hiddenHeaderCount = 0;
            removedLineCount = 0;
        }

        if (lineNumbers.isEmpty()) lineNumbers.add(-1);

        gutter.clearSelection();
        gutter.refreshMetrics();
        gutter.repaint();
        restoreView(resetToTop ? 0 : caretBefore, resetToTop ? 0 : topBefore);
        applySearch();
        pane.repaint();
    }

    /** The model offset of the topmost row on screen, or 0 when nothing is on screen yet. */
    private int topVisibleOffset() {
        Rectangle visible = pane.getVisibleRect();
        if (visible.height <= 0) return 0;
        return Math.max(0, pane.viewToModel2D(new Point(visible.x, visible.y)));
    }

    /**
     * Puts the caret and the top row back where they were.
     *
     * <p>Setting the caret first is what makes the second step necessary: the caret is moved
     * back to its old offset, and a caret that is off screen takes the viewport with it. The
     * scroll after it is the one that decides, and it moves as little as it can, so a rebuild
     * that changed nothing about the text leaves the viewport exactly where it was.
     *
     * <p>Both offsets are clamped to the document, which is shorter after a line was removed.
     */
    private void restoreView(int caret, int topOffset) {
        int length = pane.getDocument().getLength();
        pane.setCaretPosition(Math.max(0, Math.min(caret, length)));

        JViewport viewport = scroll.getViewport();
        if (topOffset <= 0) {
            // A new message, or a reader who was already at the top. Set directly rather than by
            // scrolling the first row into sight, which lands a few pixels below the top: the
            // row is already visible, so the scroll asks for nothing and leaves the offset.
            viewport.setViewPosition(new Point(viewport.getViewPosition().x, 0));
            return;
        }

        try {
            Rectangle2D row = pane.modelToView2D(Math.min(topOffset, length));
            if (row == null) return;
            Rectangle visible = pane.getVisibleRect();
            if (visible.height <= 0) return;
            pane.scrollRectToVisible(new Rectangle(visible.x, (int) Math.round(row.getY()), 1, 1));
        } catch (BadLocationException ignored) {
            // Clamped to the document above, so the offset is always inside it.
        }
    }

    private void appendLines(StyledDocument doc, TextProcessor.ProcessedHttp processed,
                             Tokens t, SyntaxPalette palette) {
        if (processed == null || processed.lines == null) return;

        boolean first = true;
        int lineIndex = 0;
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

            int lineStart = doc.getLength();
            for (SyntaxHighlighter.Token token : SyntaxHighlighter.tokenize(line, kind)) {
                int tokenStart = doc.getLength();
                insert(doc, token.text, styleFor(token.type, t, palette));
                int tokenEnd = doc.getLength();
                if (tokenEnd > tokenStart) {
                    spans.add(new Span(tokenStart, tokenEnd, token.type, lineIndex));
                    if (token.type == TokenType.HEADER_NAME) {
                        headerNames.put(token.text.toLowerCase(Locale.ROOT), token.text);
                    }
                }
            }

            applyRules(lineStart, line, kind);
            lineIndex++;
        }
    }

    /**
     * Turns the rules loose on one line.
     *
     * <p>Token concatenation is the line, character for character, so a match offset inside
     * the line is the match offset inside the document once the line's own start is added.
     * That holds however the line is later wrapped, because wrapping never changes offsets.
     */
    private void applyRules(int lineStart, String line, SyntaxHighlighter.LineKind kind) {
        if (rules.isEmpty() || line.isEmpty()) return;
        if (kind == SyntaxHighlighter.LineKind.OMISSION) return;

        int limit = line.length();
        // Only the blurred rules paint. A hidden rule's text was replaced by a marker before the
        // line was built, so there is nothing left of it here to paint over.
        for (Rules.Rule rule : rules.blurred()) {
            rule.find(line, (start, end) -> redactions.add(new Redaction(
                    lineStart + clamp(start, limit),
                    lineStart + clamp(end, limit))));
        }
        for (Rules.Rule rule : rules.highlights()) {
            rule.find(line, (start, end) -> highlights.add(new Highlight(
                    lineStart + clamp(start, limit),
                    lineStart + clamp(end, limit),
                    rule.color != null ? rule.color : Tokens.hex(TemplateConfig.DEFAULT_HIGHLIGHT))));
        }
    }

    private static int clamp(int value, int limit) {
        if (value < 0) return 0;
        return Math.min(value, limit);
    }

    /**
     * How many header lines the settings removed.
     *
     * <p>Counted rather than reported by the processor: the processor decides what to keep and
     * has no reason to keep score, and the host needs a number to show.
     *
     * <p>Scanned over the raw text rather than split into lines. Splitting a long body allocates
     * a string per line to count a handful of headers, and the host shows this number on every
     * rebuild.
     */
    private static int countHiddenHeaders(String raw, TextProcessor.ProcessedHttp processed) {
        if (raw == null || raw.isEmpty() || processed == null) return 0;

        int rawHeaders = 0;
        int lineStart = 0;
        boolean firstLine = true;
        int length = raw.length();

        for (int i = 0; i <= length; i++) {
            if (i < length && raw.charAt(i) != '\n') continue;
            if (!firstLine) {
                if (isBlank(raw, lineStart, i)) break;
                if (indexOfColon(raw, lineStart, i) > lineStart) rawHeaders++;
            }
            firstLine = false;
            lineStart = i + 1;
        }

        int keptHeaders = 0;
        for (TextProcessor.LineItem item : processed.lines) {
            if (item.kind == SyntaxHighlighter.LineKind.HEADER) keptHeaders++;
        }
        return Math.max(0, rawHeaders - keptHeaders);
    }

    /** Whitespace only, ignoring the carriage return of a CRLF line ending. */
    private static boolean isBlank(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c != '\r' && !Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /** The offset of the first colon in a line, or -1 when there is none. */
    private static int indexOfColon(String text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == ':') return i;
        }
        return -1;
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

    // ------------------------------------------------------------------ search and highlights

    /**
     * Finds the search text again and repaints every highlighter mark.
     *
     * <p>Search and highlight rules share one {@link Highlighter}, so the search box clearing
     * its own marks would clear the rules with them. Both are laid down again together,
     * search first so a rule the user asked for sits on top of a search hit.
     *
     * <p>The hit the view is standing on starts at the first one, so typing a letter puts the
     * reader at the top match rather than wherever the last search left them.
     */
    private void applySearch() {
        String needle = searchField.getText();
        matchOffsets.clear();
        matchIndex = -1;
        matchLength = needle == null ? 0 : needle.length();

        if (matchLength > 0) {
            String haystack;
            try {
                haystack = pane.getDocument().getText(0, pane.getDocument().getLength());
            } catch (BadLocationException e) {
                haystack = "";
            }

            String lowerHay = haystack.toLowerCase(Locale.ROOT);
            String lowerNeedle = needle.toLowerCase(Locale.ROOT);

            int at = lowerHay.indexOf(lowerNeedle);
            while (at >= 0) {
                matchOffsets.add(at);
                at = lowerHay.indexOf(lowerNeedle, at + Math.max(1, matchLength));
            }
            if (!matchOffsets.isEmpty()) matchIndex = 0;
        }

        paintMarks();
        updateMatchLabel();

        if (matchIndex >= 0) {
            pane.setCaretPosition(matchOffsets.get(matchIndex));
            scrollTo(matchOffsets.get(matchIndex));
        }
    }

    /**
     * Moves to the next or previous hit, wrapping at either end.
     *
     * <p>Wrapping rather than stopping: a search that refuses to move looks broken, and there is
     * no way to tell the last hit from a box that did not register the click.
     */
    private void stepMatch(int delta) {
        if (matchOffsets.isEmpty()) return;
        matchIndex = Math.floorMod(matchIndex + delta, matchOffsets.size());
        paintMarks();
        updateMatchLabel();

        int offset = matchOffsets.get(matchIndex);
        pane.setCaretPosition(offset);
        scrollTo(offset);
    }

    /**
     * Lays down the search hits and the rule highlights.
     *
     * <p>The hit being stood on is painted darker than the rest. All hits in one colour leaves
     * the count as the only clue to which of fifteen is on screen, and the count cannot say
     * where it is.
     */
    private void paintMarks() {
        Highlighter highlighter = pane.getHighlighter();
        highlighter.removeAllHighlights();

        for (int i = 0; i < matchOffsets.size(); i++) {
            int at = matchOffsets.get(i);
            try {
                highlighter.addHighlight(at, at + matchLength,
                        new DefaultHighlighter.DefaultHighlightPainter(
                                Tokens.alpha(Theme.tokens().accent, i == matchIndex ? 190 : 80)));
            } catch (BadLocationException ignored) {
                // The offset came from the live document, so this cannot happen.
            }
        }

        for (Highlight mark : highlights) {
            try {
                highlighter.addHighlight(mark.start(), mark.end(),
                        new DefaultHighlighter.DefaultHighlightPainter(
                                Tokens.alpha(mark.color(), HIGHLIGHT_ALPHA)));
            } catch (BadLocationException ignored) {
                // The range came from the live document, so this cannot happen.
            }
        }
    }

    /** Says which hit of how many is on screen, so the arrows mean something. */
    private void updateMatchLabel() {
        if (matchLength == 0) {
            matchLabel.setText("");
        } else if (matchOffsets.isEmpty()) {
            matchLabel.setText("no matches");
        } else {
            matchLabel.setText((matchIndex + 1) + " of " + matchOffsets.size());
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

    // ------------------------------------------------------------------ right-click

    private void showMenuIfTrigger(MouseEvent e) {
        if (!e.isPopupTrigger() || config == null) return;

        int offset = pane.viewToModel2D(e.getPoint());
        if (offset < 0) offset = 0;

        if (onBeforeEdit != null) onBeforeEdit.run();
        javax.swing.JPopupMenu menu = buildContextMenu(offset);
        if (menu != null) menu.show(pane, e.getX(), e.getY());
    }

    /**
     * The menu a right-click at an offset would open, without opening it.
     *
     * <p>Both menus in one. A run of lines selected in the gutter is offered here as well as on
     * the gutter itself: the selection is made by dragging down the numbers, and the press that
     * opens the menu lands wherever the pointer happens to be, which is usually the text. Offering
     * the line actions only on the 38-pixel strip beside it made the feature look absent.
     *
     * <p>Also the seam the verification run uses, since a display-less JVM cannot open a popup.
     */
    public javax.swing.JPopupMenu buildContextMenu(int offset) {
        if (config == null) return null;

        MessageRuleMenu.Target target = targetAt(offset);
        javax.swing.JPopupMenu lineMenu = gutter.buildLineMenu();

        if (target.text().isEmpty()) return lineMenu;
        if (lineMenu == null) return MessageRuleMenu.build(config, target, this::afterRuleMenu);

        javax.swing.JPopupMenu rules = MessageRuleMenu.build(config, target, this::afterRuleMenu);
        lineMenu.addSeparator();
        for (java.awt.Component item : rules.getComponents()) {
            rules.remove(item);
            lineMenu.add(item);
        }
        return lineMenu;
    }

    private void afterRuleMenu() {
        rebuild(false);
        if (onRulesChanged != null) onRulesChanged.run();
    }

    /**
     * What the menu should act on: the selected text if there is one, otherwise the token
     * under the pointer.
     */
    private MessageRuleMenu.Target targetAt(int offset) {
        int selStart = pane.getSelectionStart();
        int selEnd = pane.getSelectionEnd();
        boolean hasSelection = selEnd > selStart;

        Span span = spanAt(hasSelection ? selStart : offset);
        String text = hasSelection ? textBetween(selStart, selEnd) : textOf(span);
        TokenType type = span != null ? span.type() : TokenType.TEXT;

        return new MessageRuleMenu.Target(text.trim(), type, headerNameFor(text, type), isRequest);
    }

    /**
     * The header name to offer hiding, or null when the pointer is not on one.
     *
     * <p>A name typed into the menu goes into a hide list that matches whole headers, so a
     * selection that merely contains text has to be recognised as a header name before it is
     * offered; otherwise the list would collect entries that hide nothing.
     */
    private String headerNameFor(String text, TokenType type) {
        String candidate = text == null ? "" : text.trim();
        if (candidate.endsWith(":")) candidate = candidate.substring(0, candidate.length() - 1).trim();
        if (candidate.isEmpty()) return null;

        if (type == TokenType.HEADER_NAME) return candidate;
        return headerNames.get(candidate.toLowerCase(Locale.ROOT));
    }

    /** The token covering {@code offset}, found by binary search over the token index. */
    private Span spanAt(int offset) {
        int lo = 0;
        int hi = spans.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Span s = spans.get(mid);
            if (offset < s.start()) {
                hi = mid - 1;
            } else if (offset >= s.end()) {
                lo = mid + 1;
            } else {
                return s;
            }
        }
        return null;
    }

    private String textOf(Span span) {
        return span == null ? "" : textBetween(span.start(), span.end());
    }

    private String textBetween(int start, int end) {
        try {
            return pane.getDocument().getText(start, end - start);
        } catch (BadLocationException e) {
            return "";
        }
    }

    /** One token of the document. Offsets are document offsets, stable across wrapping. */
    private record Span(int start, int end, TokenType type, int lineIndex) { }

    private record Highlight(int start, int end, Color color) { }

    private record Redaction(int start, int end) { }

    // ------------------------------------------------------------------ the pane

    /**
     * A paragraph that reports the width it can really be squeezed into.
     *
     * <p>{@code ParagraphView} works its minimum width out from the longest run of children that
     * cannot be broken, and a header value with no space in it is one such run: the whole value
     * counts towards it. A token can easily be wider than the pane, and the box above lays a
     * child out at its minimum when that minimum beats the width it was given. So one long value
     * widens its own paragraph past the viewport, the paragraph then has room for the value on
     * one row, and the tail of it is drawn past the right edge. Nothing on screen says so: the
     * glyphs are simply cut off at the border, which is the wrap bug this fixes.
     *
     * <p>Zero is the honest answer. A row is broken at the last character that fits and only
     * pulled back to the last space when there is one, so the paragraph can be laid out at any
     * width at all. Only the width along the text is answered this way; the height keeps the
     * inherited answer, which is the total of the rows.
     */
    private static final class WrappingParagraphView extends ParagraphView {

        WrappingParagraphView(Element elem) {
            super(elem);
        }

        @Override
        public float getMinimumSpan(int axis) {
            return axis == X_AXIS ? 0f : super.getMinimumSpan(axis);
        }
    }

    /**
     * The stock styled kit, with paragraphs that answer as above.
     *
     * <p>A kit is the only way to hand a view factory to a {@code JTextPane}: its UI asks the
     * component's kit for one. Everything the stock factory builds is kept exactly as it was.
     */
    private static final class WrappingEditorKit extends StyledEditorKit {

        private final ViewFactory stock = super.getViewFactory();

        @Override
        public ViewFactory getViewFactory() {
            return elem -> {
                View view = stock.create(elem);
                return view instanceof ParagraphView ? new WrappingParagraphView(elem) : view;
            };
        }
    }

    /**
     * The text area itself.
     *
     * <p>Redactions are painted here rather than set as character attributes, because Swing
     * gives no guarantee whether a highlight painter lands above or below the glyphs. An
     * opaque patch has to land above them: a blackout the text shows through is worse than no
     * blackout, since the author believes the secret is hidden. Overriding {@code paint} and
     * drawing after the text settles the question instead of guessing at it.
     */
    private final class MessagePane extends JTextPane {

        /**
         * Wrapping is a property of the component's width, not of the document. Returning true
         * makes the scroll pane keep the pane at the viewport width, which is what gives the
         * paragraph view a span to wrap into. Returning false lets the pane grow to its
         * longest line, which is what the horizontal scrollbar needs.
         */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return wrap;
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            if (redactions.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            Rectangle clip = g.getClipBounds();
            for (Redaction mark : redactions) {
                int width = mark.end() - mark.start();
                if (width <= 0) continue;
                for (Rectangle rect : rowRects(mark.start(), mark.end())) {
                    if (clip != null && !clip.intersects(rect)) continue;
                    paintRedaction(g2, rect);
                }
            }
            g2.dispose();
        }
    }

    /**
     * Pixel rectangles covering a document range, one per visual row.
     *
     * <p>Rows rather than one rectangle: with wrapping on, a long value the user redacted can
     * span two rows, and the bounding box of a wrapped range covers text on neither row's ends.
     */
    private List<Rectangle> rowRects(int start, int end) {
        List<Rectangle> out = new ArrayList<>();
        if (start >= end) return out;

        int pos = start;
        int guard = 0;
        while (pos < end && guard++ < 4096) {
            int rowEnd;
            try {
                rowEnd = Utilities.getRowEnd(pane, pos);
            } catch (BadLocationException e) {
                break;
            }
            if (rowEnd <= pos) rowEnd = pos + 1;

            int stop = Math.min(end, rowEnd);
            Rectangle2D first = rectAt(pos);
            Rectangle2D last = rectAt(Math.max(pos, stop - 1));
            if (first == null || last == null) break;

            int x = (int) Math.floor(first.getX());
            int y = (int) Math.floor(first.getY());
            int right = rightEdge(stop, first, last);
            int bottom = (int) Math.ceil(last.getY() + last.getHeight());
            out.add(new Rectangle(x, y, Math.max(1, right - x), Math.max(1, bottom - y)));

            pos = stop;
        }
        return out;
    }

    /**
     * Where a patch has to stop on the row that begins with {@code first}.
     *
     * <p>Measured from {@code stop}, the offset one past the last covered character, and never
     * from the last character's own box. {@code modelToView2D} answers with a caret box at a
     * character's offset, zero pixels wide, so {@code x + width} is the LEFT edge of that
     * character: a patch sized that way leaves the final glyph of the value on screen, which is
     * a leak of exactly one character and is what the off-by-one looked like.
     *
     * <p>{@code stop} lands on the next row when the range ends precisely at a wrap point, and
     * the answer there is that row's left edge. That case falls back to one character advance
     * past the last box, which is exact because the pane's font is monospaced.
     */
    private int rightEdge(int stop, Rectangle2D first, Rectangle2D last) {
        Rectangle2D after = rectAt(stop);
        if (after != null && Math.abs(after.getY() - first.getY()) < 1.0) {
            return (int) Math.ceil(after.getX());
        }
        return (int) Math.ceil(last.getX()) + charAdvance();
    }

    /** One character of the pane's font, which is monospaced, so any character will do. */
    private int charAdvance() {
        return Math.max(1, pane.getFontMetrics(pane.getFont()).charWidth('M'));
    }

    private Rectangle2D rectAt(int offset) {
        try {
            return pane.modelToView2D(offset);
        } catch (BadLocationException e) {
            return null;
        }
    }

    /**
     * A washed patch: the value is still there, and no longer readable.
     *
     * <p>Two passes over the text, neither of them opaque. A wash in the background color pulls
     * the glyph most of the way down to the background, and a field of noise cells on top
     * breaks up what is left, so the shape of a word is visible and its letters are not. Drawing
     * a solid block instead would hide the fact that a value was ever there, which is not what
     * a redacted screenshot should say.
     *
     * <p>This is obfuscation, not encryption. What it has to survive is a reader glancing at a
     * report, and the numbers it is tuned to are in the test: the wash is deep enough that a
     * glyph stroke stops standing out from the background it sits on.
     *
     * <p>A real gaussian blur would need the region rasterised, and rasterising is the cost this
     * view exists to avoid. The noise is a hash of the cell coordinates, so it does not shimmer
     * between repaints and two screenshots match.
     */
    private static void paintRedaction(Graphics2D g, Rectangle rect) {
        Shape clip = g.getClip();
        g.clipRect(rect.x, rect.y, rect.width, rect.height);

        g.setColor(washColor(WASH_ALPHA));
        g.fillRect(rect.x, rect.y, rect.width, rect.height);

        int cell = NOISE_CELL;
        int right = rect.x + rect.width;
        int bottom = rect.y + rect.height;
        for (int y = rect.y; y < bottom; y += cell) {
            for (int x = rect.x; x < right; x += cell) {
                int shade = shadeAt(x, y);
                g.setColor(new Color(shade, shade, shade, NOISE_ALPHA));
                g.fillRect(x, y, Math.min(cell, right - x), Math.min(cell, bottom - y));
            }
        }

        g.setClip(clip);
    }

    /** The color the wash is made of: whatever the text is sitting on. */
    private static Color washColor(int alpha) {
        Color base = Theme.tokens().bgPanel;
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    /** The band behind a run of selected line numbers. */
    private static Color selectionColor() {
        return Theme.tokens().bgActive;
    }

    /** Deterministic per cell: same coordinates, same shade, every repaint. */
    private static int shadeAt(int x, int y) {
        int h = x * 73856093 ^ y * 19349663;
        h ^= (h >>> 13);
        return 60 + Math.floorMod(h, 130);
    }

    // ------------------------------------------------------------------ gutter

    /**
     * Line numbers down the left edge, and the place lines are dropped from.
     *
     * <p>Positions come from {@code modelToView2D} rather than a computed line height, so the
     * numbers stay aligned if the editor kit ever reports a different line spacing, and a
     * wrapped line still gets its number on its first visual row.
     *
     * <p>Dragging down the numbers selects a run of lines and the right-click menu removes them
     * from the PoC. The numbers selected are the original ones being printed, which is the same
     * unit the line-ranges field counts in, so nothing has to be converted in between. The same
     * run is selected in the pane, because the two are views of one document and a run of numbers
     * with nothing lit up beside it is a selection the user cannot check.
     */
    private final class Gutter extends JComponent {

        private Font font = Theme.tokens().code;
        private FontMetrics metrics = getFontMetrics(font);
        private int width = 44;

        /** Ends of the selected run, as document line indices. -1 when nothing is selected. */
        private int anchorIndex = -1;
        private int focusIndex = -1;

        /**
         * Top y of every document line, filled on press and read for the rest of the drag.
         *
         * <p>Walking a 10k-line message through {@code modelToView2D} once per drag event is the
         * kind of cost this view exists to avoid, and a drag fires an event per pixel.
         */
        private int[] rowTops;

        Gutter() {
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (config == null) return;
                    buildRowTops();
                    int index = indexAt(e.getY());
                    if (index < 0) return;

                    // A right press must not disturb the run. Which button opened the menu is
                    // asked here rather than through isPopupTrigger, because that flag is only
                    // set on the release on Windows: the press would collapse the selection to
                    // the row under the pointer and the menu would then act on that one row,
                    // which is exactly the bug of a block turning into its last line.
                    if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                        if (selectedNumbers() == null) {
                            anchorIndex = index;
                            focusIndex = index;
                            selectInPane();
                            repaint();
                        }
                    } else if (!withinSelection(index)) {
                        anchorIndex = index;
                        focusIndex = index;
                        selectInPane();
                        repaint();
                    }

                    if (e.isPopupTrigger()) showLineMenu(e);
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) showLineMenu(e);
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (anchorIndex < 0) return;
                    // Extending with the right button held would grow the run while the pointer
                    // travels to the menu, so only a left drag moves the far end.
                    if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) return;
                    int index = indexAt(e.getY());
                    if (index < 0 || index == focusIndex) return;
                    focusIndex = index;
                    selectInPane();
                    repaint();
                }
            });
        }

        void refreshMetrics() {
            font = Theme.tokens().code;
            metrics = getFontMetrics(font);
            int widest = metrics.stringWidth(String.valueOf(Math.max(100, lineNumbers.size())));
            width = Math.max(38, widest + Tokens.LG);
            revalidate();
        }

        /**
         * As tall as the whole message, not as tall as the strip on screen.
         *
         * <p>This is the whole of why the numbers would not travel with the text. The gutter is
         * the scroll pane's row header, and a row header is placed by {@code ViewportLayout},
         * which pins the view to the top whenever the view is shorter than the viewport: a
         * 10-pixel-tall strip clamps its own scroll position back to zero on every layout, so
         * after any revalidate the numbers began again at line one while the text stayed where
         * the user had scrolled to. The two then disagreed by exactly the scroll offset, and the
         * run removed was that far from the run that was pointed at.
         *
         * <p>Every y in this class is a model coordinate, the same ones the pane reports, so once
         * the view is the height of the document the row header scrolls the identical distance
         * and no arithmetic is left to get wrong.
         */
        @Override
        public Dimension getPreferredSize() {
            int height = pane == null ? metrics.getHeight() : pane.getPreferredSize().height;
            return new Dimension(width, Math.max(metrics.getHeight(), height));
        }

        /**
         * One row, not the whole message.
         *
         * <p>The height above is there so the row header can be scrolled, and a layout manager
         * that asked for the minimum would then demand a panel as tall as the text: a scroll pane
         * reports the taller of its view and its row header as its own minimum.
         */
        @Override
        public Dimension getMinimumSize() { return new Dimension(width, metrics.getHeight()); }

        /** Forgets the selection, because after a rebuild the indices mean other lines. */
        void clearSelection() {
            anchorIndex = -1;
            focusIndex = -1;
            rowTops = null;
        }

        private void buildRowTops() {
            Element root = pane.getDocument().getDefaultRootElement();
            int count = root.getElementCount();
            int[] tops = new int[count];
            for (int i = 0; i < count; i++) {
                Rectangle2D r = rectAt(root.getElement(i).getStartOffset());
                tops[i] = r == null ? Integer.MAX_VALUE : (int) Math.round(r.getY());
            }
            rowTops = tops;
        }

        /**
         * The line a mouse event at {@code y} is over.
         *
         * <p>{@code y} arrives in this component's own coordinates. The rows recorded by
         * {@link #buildRowTops} are in the pane's, and the {@code modelToView2D} the numbers are
         * drawn from answers in exactly the same ones. Converting between the two is the whole of
         * this method: the two components are separate views into one message, each translated by
         * its own viewport, and reading a coordinate from one against a coordinate from the other
         * without converting is what made the run that was dragged over differ from the run that
         * came out.
         *
         * <p>Added to the scroll offset rather than converted, the error was exactly the distance
         * scrolled, and it grew with it: near the top of a message the press landed a few lines
         * low, and further down it landed past the end of the message and was read as the last
         * line, so dragging from line 110 to line 120 selected line 120 alone.
         *
         * <p>Converted per call rather than baked in when the rows were built, because the user
         * can scroll mid-drag with the wheel.
         */
        private int indexAt(int y) {
            if (rowTops == null) return -1;
            int modelY = SwingUtilities.convertPoint(this, 0, y, pane).y;
            int found = -1;
            for (int i = 0; i < rowTops.length; i++) {
                if (rowTops[i] > modelY) break;
                found = i;
            }
            return found;
        }

        private boolean withinSelection(int index) {
            if (anchorIndex < 0) return false;
            return index >= Math.min(anchorIndex, focusIndex)
                    && index <= Math.max(anchorIndex, focusIndex);
        }

        /**
         * Lights the same lines up in the text, so the two views of one document agree.
         *
         * <p>A run of numbers with nothing lit beside it reads as a selection that has not
         * happened: the user is about to drop these lines from the PoC, and the text is where
         * they check they picked the ones they meant. Selecting in the pane rather than merely
         * marking it also means the run can be copied out while it is selected.
         *
         * <p>The run is translated through the document's line elements, so a wrapped line is
         * selected whole and the last line, which has no line break, does not reach past itself.
         * The view position is restored afterwards because moving the caret scrolls the text to
         * it, and a pane that jumps while the pointer is still dragging down the numbers stops
         * showing the lines being chosen.
         */
        private void selectInPane() {
            if (pane == null || anchorIndex < 0) return;

            Element root = pane.getDocument().getDefaultRootElement();
            int count = root.getElementCount();
            if (count == 0) return;

            int from = Math.max(0, Math.min(Math.min(anchorIndex, focusIndex), count - 1));
            int to = Math.max(0, Math.min(Math.max(anchorIndex, focusIndex), count - 1));

            int start = root.getElement(from).getStartOffset();
            // Clamped, because the last line of a document that does not end in a line break
            // reports an end one past the text. A caret position past the document is refused
            // outright rather than corrected, so this is the difference between a selection and
            // an exception thrown out of a mouse drag.
            int end = Math.min(root.getElement(to).getEndOffset(), pane.getDocument().getLength());
            // Every other element ends one past its own line break. Stopping on the break keeps
            // the selection to the lines the numbers name.
            if (to < count - 1 && end > start) end--;

            JViewport viewport = scroll.getViewport();
            Point at = viewport.getViewPosition();

            // The caret's own bar is not painted, but the highlight over the selected text is,
            // and that highlight is the only thing that lets the user see which lines the numbers
            // have picked. A pane that has never held the focus reports its selection as not
            // visible, so the run would be selected and invisible at the same time.
            pane.getCaret().setSelectionVisible(true);

            pane.setCaretPosition(start);
            pane.moveCaretPosition(end);
            viewport.setViewPosition(at);
        }

        /**
         * The original line numbers the selection covers.
         *
         * <p>An omission marker has no number of its own, so it contributes nothing and a run
         * that starts or ends on one still yields the lines the user meant.
         *
         * @return the first and last number, or null when the run holds no numbered line
         */
        private int[] selectedNumbers() {
            if (anchorIndex < 0) return null;
            int first = Integer.MAX_VALUE;
            int last = Integer.MIN_VALUE;
            int from = Math.min(anchorIndex, focusIndex);
            int to = Math.max(anchorIndex, focusIndex);
            for (int i = from; i <= to && i < lineNumbers.size(); i++) {
                Integer number = lineNumbers.get(i);
                if (number == null || number <= 0) continue;
                first = Math.min(first, number);
                last = Math.max(last, number);
            }
            return first <= last ? new int[]{first, last} : null;
        }

        /**
         * The menu for the lines the gutter has selected, or null when none are.
         *
         * <p>Built before it is shown, and reachable without a press, so the verification run can
         * drive a drag and read the menu: a display-less JVM cannot open a popup at all.
         *
         * <p>Builds only. The undo snapshot belongs to whoever is about to put the menu on
         * screen, because this is also called to decide whether there is anything to show.
         */
        javax.swing.JPopupMenu buildLineMenu() {
            int[] numbers = selectedNumbers();
            if (numbers == null) return null;

            return MessageRuleMenu.buildForLines(
                    config, isRequest, numbers[0], numbers[1], () -> {
                clearSelection();
                rebuild(false);
                if (onRulesChanged != null) onRulesChanged.run();
            });
        }

        private void showLineMenu(MouseEvent e) {
            if (selectedNumbers() == null) return;
            if (onBeforeEdit != null) onBeforeEdit.run();
            javax.swing.JPopupMenu menu = buildLineMenu();
            if (menu != null) menu.show(this, e.getX(), e.getY());
        }

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

            int selectedFrom = anchorIndex < 0 ? -1 : Math.min(anchorIndex, focusIndex);
            int selectedTo = anchorIndex < 0 ? -1 : Math.max(anchorIndex, focusIndex);

            for (int i = 0; i < count; i++) {
                Integer number = lineNumbers.get(i);
                if (number == null || number <= 0) continue;

                Element line = root.getElement(i);
                Rectangle2D r = rectAt(line.getStartOffset());
                if (r == null) continue;

                if (i >= selectedFrom && i <= selectedTo) {
                    Rectangle2D next = i + 1 < root.getElementCount()
                            ? rectAt(root.getElement(i + 1).getStartOffset())
                            : null;
                    int top = (int) Math.round(r.getY());
                    int bottom = next == null
                            ? top + metrics.getHeight()
                            : (int) Math.round(next.getY());
                    g2.setColor(selectionColor());
                    g2.fillRect(0, top, getWidth() - 1, Math.max(1, bottom - top));
                    g2.setColor(effectivePalette().color(TokenType.LINE_NUMBER));
                }

                int baseline = (int) Math.round(r.getY() + metrics.getAscent());
                String text = String.valueOf(number);
                g2.drawString(text, getWidth() - Tokens.SM - metrics.stringWidth(text), baseline);
            }

            g2.dispose();
        }
    }
}
