package burp.screenshot;

import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.Theme;
import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.Rules;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.engine.TextProcessor;
import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.ScopeTarget;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.model.UndoHistory;
import burp.screenshot.ui.HeadersTab;
import burp.screenshot.ui.MessageRuleMenu;
import burp.screenshot.ui.PocEditorPanel;
import burp.screenshot.ui.RuleCard;
import burp.screenshot.ui.RulesTab;
import burp.screenshot.ui.StyledMessageView;
import burp.screenshot.ui.components.SegmentedControl;
import com.google.gson.Gson;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless checks over the text view and the settings that feed it.
 *
 * <p>Run with {@code -ea}: every check below is an {@code assert}, which the JVM silently skips
 * without it. Both build scripts pass the flag.
 *
 * <p>The images written to {@code build/} are the artefact a human is meant to inspect. The
 * assertions cover what a machine can decide: that a theme switch repaints, that wrapping takes
 * the rows it should, and above all that a redaction covers the glyphs it was asked to cover.
 */
public class ScreenshotVerificationTest {

    private static final File OUT = new File("build");

    /**
     * Runs the checks and then exits, always.
     *
     * <p>The exit is not decoration. Rendering offscreen still starts an {@code EventQueue} in
     * an otherwise display-less JVM, and the AWT shutdown thread then keeps the process alive
     * indefinitely. Without an explicit exit the build would hang after a green run, and would
     * hang just as hard after a failed one, since an {@code AssertionError} leaving {@code main}
     * does not stop those threads either.
     */
    public static void main(String[] args) {
        // The whole suite runs as one event on the event thread, which is where Burp runs this
        // extension. Building Swing components from the main thread while the event thread is
        // inside the same text layout crashes in the view machinery, and it does so about half
        // the time, which is the worst possible rate: green often enough to look trustworthy.
        // Nothing is queued while one event is running, so the two cannot meet.
        Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    runChecks();
                } catch (Throwable t) {
                    failure[0] = t;
                }
            });
        } catch (Throwable t) {
            failure[0] = t;
        }

        if (failure[0] == null) {
            System.out.println("==================================================");
            System.out.println(" ALL CHECKS PASSED");
            System.out.println("==================================================");
            System.exit(0);
        }
        System.out.println("==================================================");
        System.out.println(" VERIFICATION FAILED");
        failure[0].printStackTrace(System.out);
        System.out.println("==================================================");
        System.exit(1);
    }

    private static void runChecks() throws Exception {
        System.out.println(">>> Screenshot PoC verification");

        HttpExchangeData data = HttpExchangeData.createSampleData();
        assert data.getRawRequest() != null : "sample request";
        assert data.getRawResponse() != null : "sample response";
        System.out.println("[PASS] sample exchange");

        TemplateManager templates = new TemplateManager();
        TemplateConfig config = templates.getTemplate("Default");
        assert config != null : "the Default template must exist";
        System.out.println("[PASS] templates: " + templates.getTemplateNames());

        TextProcessor.ProcessedHttp request = TextProcessor.processRequest(data.getRawRequest(), config);
        TextProcessor.ProcessedHttp response = TextProcessor.processResponse(data.getRawResponse(), config);
        assert request.lines.size() > 1 : "the request must keep more than one line";
        assert response.lines.size() > 1 : "the response must keep more than one line";
        System.out.println("[PASS] text processing: " + request.lines.size() + " request lines, "
                + response.lines.size() + " response lines");

        checkDefaultDateHighlight();
        checkDateSurvivesMissingField();
        checkDateRuleCoversValue();
        checkMalformedPatternIsDropped();
        checkHeaderHiding();
        checkLineRangesRemove();
        checkLineRangesBadInput();
        checkThemeListenerRemoval();
        checkConfigRepair();
        checkTokenSeparation();
        writePaletteSheet(true);
        writePaletteSheet(false);
        checkPocTabScope();
        checkNothingIsReadUntilLookedAt();
        checkRuleChangeKeepsScrollPosition();
        checkRuleMenu();
        checkRedactionPresets();
        checkDefaultRedactions();
        checkGutterMenuRemovesLines();
        checkGutterSelectionSelectsText();
        checkHideTakesSpanOutOfLine();
        checkRuleMenuOffersHide();
        checkRulesTab();
        checkRuleNames();
        checkSearchStepper();
        checkToolbarIsOneRow();
        checkHeadersTabLayout();
        checkUndo();
        checkView();
    }

    // ------------------------------------------------------------------ settings

    /**
     * A new template must point at the response's Date header.
     *
     * <p>The requirement was explicit, and this is the one default a reader notices first: the
     * tab opens with the Date already marked. The rule has to be a real one, so it shows up in
     * the settings list and can be recoloured or deleted.
     */
    private static void checkDefaultDateHighlight() {
        TemplateConfig fresh = new TemplateConfig();
        assert fresh.getHighlights().size() == 1
                : "a new template must carry exactly the default Date rule, it has "
                + fresh.getHighlights().size();

        HighlightRule date = fresh.getHighlights().get(0);
        assert TemplateConfig.DEFAULT_DATE_PATTERN.equals(date.getPattern())
                : "the default pattern is " + date.getPattern();
        assert date.isRegex() : "the default Date rule is a pattern, not a literal";
        assert date.getTarget() == ScopeTarget.RESPONSE : "Date is a response header";
        assert date.isEnabled() : "an off-by-default rule would show nothing";
        assert TemplateConfig.DEFAULT_HIGHLIGHT.equals(date.getColorHex())
                : "the default colour is " + date.getColorHex();

        Rules.Result compiled = Rules.compile(fresh);
        assert compiled.highlights().size() == 1 : "the Date rule must compile";
        assert compiled.forSide(true).highlights().isEmpty()
                : "a response-only rule must not fire on a request";

        // What the rule covers, not just where it starts: a rule that stopped at the colon left
        // the value, which is the part a reader looks for, unmarked.
        String dateLine = "Date: Mon, 01 Sep 2026 10:00:00 GMT";
        List<String> hits = new ArrayList<>();
        Rules.Rule rule = compiled.forSide(false).highlights().get(0);
        rule.find(dateLine, (s, e) -> hits.add(dateLine.substring(s, e)));
        rule.find("X-Date-Offset: 1", (s, e) -> hits.add("not-the-first-column"));
        assert hits.equals(List.of(dateLine))
                : "the rule must anchor to the start of the line and cover the value, it matched "
                + hits;

        System.out.println("[PASS] default highlight: " + TemplateConfig.DEFAULT_DATE_PATTERN
                + " on a response, anchored, covering the value, on by default");
    }

    /**
     * A settings file written before the Date rule existed must still get it.
     *
     * <p>Gson leaves a field the file does not mention at its constructor value, which is the
     * whole reason the default survives an upgrade. This checks that, rather than assuming it:
     * a {@code normalize} that rebuilt the lists from scratch would silently drop the default on
     * every load.
     */
    private static void checkDateSurvivesMissingField() {
        TemplateConfig loaded = new Gson().fromJson("{\"name\":\"Default\"}", TemplateConfig.class);
        assert loaded != null : "a template must parse";
        assert loaded.getHighlights() != null && loaded.getHighlights().size() == 1
                : "a file with no highlights field must still get the Date rule, it has "
                + (loaded.getHighlights() == null ? "null" : "" + loaded.getHighlights().size());

        TemplateManager.normalize(loaded);
        assert loaded.getHighlights().size() == 1 : "normalize must not drop the default rule";
        assert TemplateConfig.DEFAULT_DATE_PATTERN.equals(loaded.getHighlights().get(0).getPattern())
                : "and the rule that survived is the Date rule, it is "
                + loaded.getHighlights().get(0).getPattern();
        System.out.println("[PASS] a settings file without a highlights field keeps the Date rule");
    }

    /**
     * One bad pattern must not take the view down with it.
     *
     * <p>A malformed regex is a typo in a settings field. Throwing would leave the user with a
     * blank tab and no way back to the field that caused it.
     */
    private static void checkMalformedPatternIsDropped() {
        TemplateConfig broken = new TemplateConfig();
        broken.getHighlights().add(new HighlightRule("([unclosed", true, ScopeTarget.BOTH, "#ffffff"));
        broken.getRedactions().add(new RedactionRule("a{2,1}", true, ScopeTarget.BOTH, 0));

        // The shipped rules are on the same list, so they are counted rather than assumed away:
        // what is asserted is that the two broken ones are missing, not that nothing compiled.
        int shipped = TemplateConfig.defaultRedactions().size();

        Rules.Result result = Rules.compile(broken);
        assert result.highlights().size() == 1
                : "the malformed highlight must be dropped, leaving only Date, got "
                + result.highlights().size();
        assert result.redactions().size() == shipped
                : "the malformed redaction must be dropped and the shipped ones kept, expected "
                + shipped + " and got " + result.redactions().size();
        System.out.println("[PASS] a malformed pattern is dropped, never thrown");
    }

    /** The hide list: prefix, exact, case and the {@code !} override. */
    private static void checkHeaderHiding() {
        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("x-*\n!Accept\nServer");

        String raw = "GET / HTTP/1.1\r\n"
                + "Accept: */*\r\n"
                + "X-Custom: 1\r\n"
                + "Server: nginx\r\n"
                + "X-Other: 2\r\n"
                + "\r\n";

        List<String> kept = new ArrayList<>();
        for (TextProcessor.LineItem item : TextProcessor.processRequest(raw, config).lines) {
            kept.add(item.text);
        }

        assert kept.get(0).startsWith("GET ") : "line 0 is never filtered, it is " + kept.get(0);
        assert kept.contains("Accept: */*") : "a leading ! must beat the hide rules, kept: " + kept;
        assert !kept.contains("X-Custom: 1") : "a trailing * must hide by prefix, kept: " + kept;
        assert !kept.contains("X-Other: 2") : "a trailing * must hide by prefix, kept: " + kept;
        assert !kept.contains("Server: nginx")
                : "an exact rule must match whatever the case, kept: " + kept;

        List<String> names = TextProcessor.headerNames(raw);
        assert names.equals(List.of("Accept", "X-Custom", "Server", "X-Other"))
                : "the names offered to the settings dialog came back as " + names;

        System.out.println("[PASS] header hiding: prefix, exact, ! override, case-insensitive");
    }

    // ------------------------------------------------------------------ line ranges

    /**
     * The ranges field removes the lines the gutter numbers, and nothing else.
     *
     * <p>This is the whole of the bug it replaces. The field used to be a keep list counted after
     * the hidden headers were taken out, while the gutter printed original line numbers, so the
     * numbers the user typed and the numbers they could see were two different sets. The check
     * below states the mapping in the one unit the user has: the numbers left on screen.
     */
    private static void checkLineRangesRemove() {
        TemplateConfig config = new TemplateConfig();
        // Line 15 is a header name, so the hide list takes it out. It is outside both ranges, so
        // it must stay out of the removed count and leave a gap rather than a marker.
        config.setHeadersToHide("X-Line-15");
        config.setResponseLineRanges("17-25,30-38");

        // Forty numbered lines, so every line says which one it is.
        StringBuilder raw = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 2; i <= 40; i++) {
            raw.append("X-Line-").append(i).append(": value\r\n");
        }
        raw.append("\r\nbody");

        TextProcessor.ProcessedHttp processed = TextProcessor.processResponse(raw.toString(), config);
        assert processed.removedLineCount == 18
                : "17-25 and 30-38 are 18 lines, the field removed " + processed.removedLineCount;
        assert processed.ignoredRangeEntries.isEmpty()
                : "nothing in that field is unreadable, it ignored " + processed.ignoredRangeEntries;

        Set<Integer> kept = new TreeSet<>();
        int omissions = 0;
        for (TextProcessor.LineItem item : processed.lines) {
            if (item.isOmission) {
                omissions++;
                continue;
            }
            kept.add(item.originalLineNumber);
        }

        // 40 header lines, then the separating blank line and the body, which are lines 41 and 42.
        Set<Integer> expected = new TreeSet<>(Set.of(41, 42));
        for (int line = 1; line <= 40; line++) {
            if (line >= 17 && line <= 25) continue;
            if (line >= 30 && line <= 38) continue;
            if (line == 15) continue; // the hidden header
            expected.add(line);
        }
        assert kept.equals(expected)
                : "the lines on screen must be the original numbers the field did not name, "
                + "expected " + expected + " and got " + kept;

        assert omissions == 2
                : "each removed run leaves one marker, the message left " + omissions;
        assert kept.contains(14) && kept.contains(16) && !kept.contains(15)
                : "a hidden header must leave a silent gap in the numbering, not a marker";

        System.out.println("[PASS] line ranges: " + processed.removedLineCount
                + " lines removed by number, " + kept.size() + " left, " + omissions + " marker(s)");
    }

    /**
     * A field that cannot be read must remove nothing and say which entry it did not understand.
     *
     * <p>The old parser swallowed these silently, which is how a field that did nothing looked
     * exactly like a field that worked. {@code all} is the value the old field shipped with, so it
     * has to keep meaning "remove nothing": a stray word must never take the message away.
     */
    private static void checkLineRangesBadInput() {
        String raw = "HTTP/1.1 200 OK\r\nX-A: 1\r\nX-B: 2\r\n\r\nbody";

        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");
        config.setResponseLineRanges("17-,abc,40-10");

        TextProcessor.ProcessedHttp processed = TextProcessor.processResponse(raw, config);
        assert processed.removedLineCount == 0
                : "an unreadable field must remove nothing, it removed "
                + processed.removedLineCount;
        assert processed.lines.size() == 5
                : "every line must still be on screen, the view has " + processed.lines.size();
        assert processed.ignoredRangeEntries.equals(List.of("17-", "abc", "40-10"))
                : "every entry that could not be read must be reported, it reported "
                + processed.ignoredRangeEntries;

        config.setResponseLineRanges("all");
        assert TextProcessor.processResponse(raw, config).removedLineCount == 0
                : "all means remove nothing";

        config.setResponseLineRanges("");
        assert TextProcessor.processResponse(raw, config).removedLineCount == 0
                : "an empty field means remove nothing";

        config.setResponseLineRanges("2-3");
        assert TextProcessor.processResponse(raw, config).removedLineCount == 2
                : "a plain range still has to work after the bad ones";

        // The gutter menu asks this before it offers to remove a span, so it has to count the
        // lines in the middle and not only the ends.
        assert TextProcessor.removedBetween("17-19,22-24", 17, 24) == 6
                : "two ranges either side of a span cover six of its eight lines, it counted "
                + TextProcessor.removedBetween("17-19,22-24", 17, 24);
        assert TextProcessor.removedBetween("17-24", 17, 24) == 8 : "a full span is eight lines";
        assert TextProcessor.removedBetween("all", 17, 24) == 0 : "all covers nothing";

        System.out.println("[PASS] line ranges: bad input removes nothing and is reported, "
                + "all and empty are no-ops");
    }

    /**
     * The default Date rule has to cover the value, and a saved template has to be upgraded to it.
     *
     * <p>The shipped pattern used to stop at the colon, so the one header a reader always looks
     * for was marked on its name and left its value plain. A template already on disk carries the
     * old pattern, so changing the default alone would leave everyone who has run the extension
     * looking at the same half-marked line after a reload.
     */
    private static void checkDateRuleCoversValue() {
        assert "^Date:.*".equals(TemplateConfig.DEFAULT_DATE_PATTERN)
                : "the shipped pattern is " + TemplateConfig.DEFAULT_DATE_PATTERN;

        TemplateConfig saved = new TemplateConfig();
        saved.getHighlights().clear();
        saved.getHighlights().add(new HighlightRule(TemplateConfig.LEGACY_DATE_PATTERN,
                true, ScopeTarget.RESPONSE, TemplateConfig.DEFAULT_HIGHLIGHT));
        saved.getHighlights().add(new HighlightRule(TemplateConfig.LEGACY_DATE_PATTERN,
                true, ScopeTarget.REQUEST, TemplateConfig.DEFAULT_HIGHLIGHT));

        TemplateManager.normalize(saved);
        assert TemplateConfig.DEFAULT_DATE_PATTERN.equals(saved.getHighlights().get(0).getPattern())
                : "a saved response rule on the old pattern must be widened, it is "
                + saved.getHighlights().get(0).getPattern();
        assert TemplateConfig.LEGACY_DATE_PATTERN.equals(saved.getHighlights().get(1).getPattern())
                : "a rule the user wrote for the request side must be left alone, it is "
                + saved.getHighlights().get(1).getPattern();

        // And the widened pattern covers a real Date line end to end.
        String line = "Date: Mon, 01 Sep 2026 10:00:00 GMT";
        List<String> hits = new ArrayList<>();
        Rules.compile(saved.copy()).forSide(false).highlights().get(0)
                .find(line, (s, e) -> hits.add(line.substring(s, e)));
        assert hits.equals(List.of(line))
                : "the upgraded rule must cover the whole header, it matched " + hits;

        System.out.println("[PASS] Date rule: covers name and value, saved templates upgraded, "
                + "request-side rules untouched");
    }

    /** A listener must be removable, or every window open leaks one into the static list. */
    private static void checkThemeListenerRemoval() {
        AtomicInteger fired = new AtomicInteger();

        Theme.setMode(Theme.Mode.DARK);
        AutoCloseable handle = Theme.addListener(fired::incrementAndGet);
        Theme.setMode(Theme.Mode.LIGHT);
        assert fired.get() > 0 : "an installed listener must be told about a theme change";

        close(handle);
        int afterClose = fired.get();
        Theme.setMode(Theme.Mode.DARK);
        assert fired.get() == afterClose
                : "a closed listener must not fire again";
        System.out.println("[PASS] theme listener removal");
    }

    /**
     * A template read back from JSON must not carry nulls into the UI.
     *
     * <p>Every null here is a crash the old code would have taken: a null {@code highlights} list
     * throws in the first {@code for} over it, and a null {@code name} throws inside a combo box.
     */
    private static void checkConfigRepair() {
        TemplateConfig broken = new TemplateConfig();
        broken.setName(null);
        broken.setHeaderScope(null);
        broken.setHeadersToHide(null);
        broken.setRequestLineRanges(null);
        broken.setHighlights(null);
        broken.setRedactions(Arrays.asList((RedactionRule) null));
        // Pinned to the current rule set so the one-time default migration stays out of this
        // check. A config still at version zero and holding no redactions is exactly what the
        // migration is for, and it would add the shipped rules before the nulls were counted.
        broken.setDefaultsVersion(TemplateConfig.DEFAULTS_VERSION);
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("METHOD", null);
        colors.put("TARGET", "#1a5fb4");
        broken.setSyntaxColors(colors);

        TemplateConfig repaired = TemplateManager.normalize(broken);
        assert repaired == broken : "normalize repairs the config in place";
        assert "Default".equals(repaired.getName()) : "a blank name must fall back to Default";
        assert repaired.getHeaderScope() != null : "a null scope must fall back";
        assert repaired.getHeadersToHide() != null : "a null hide list must fall back to empty";
        assert repaired.getHighlights() != null && repaired.getHighlights().isEmpty()
                : "a null highlight list must become empty";
        assert repaired.getRedactions().isEmpty() : "a null entry in the list must be dropped";
        assert !repaired.getSyntaxColors().containsKey("METHOD") : "a null colour must be dropped";
        assert "#1a5fb4".equals(repaired.getSyntaxColors().get("TARGET")) : "a real colour must survive";

        TemplateConfig rules = new TemplateConfig();
        rules.getHighlights().add(new HighlightRule(null, true, null, "not-a-colour"));
        rules.getRedactions().add(new RedactionRule(null, true, null, -3));
        TemplateManager.normalize(rules);

        // The last entry: a new template starts with the Date rule, so the one just added is
        // behind it rather than in front.
        HighlightRule h = rules.getHighlights().get(rules.getHighlights().size() - 1);
        assert "".equals(h.getPattern()) && h.getTarget() != null && Tokens.hex(h.getColorHex()) != null
                : "a highlight with a null pattern and a broken colour must become usable";
        RedactionRule r = rules.getRedactions().get(rules.getRedactions().size() - 1);
        assert "".equals(r.getPattern()) && r.getTarget() != null && r.getCaptureGroup() == 0
                : "a redaction with nulls must become usable";

        // copy() reads the lists directly, so a config that survived normalize must also survive
        // a save and reload cycle.
        int highlights = rules.getHighlights().size();
        int redactions = rules.getRedactions().size();
        TemplateConfig copy = rules.copy();
        assert copy.getHighlights().size() == highlights && copy.getRedactions().size() == redactions
                : "a repaired config must round-trip through copy(), it went from "
                + highlights + "/" + redactions + " to " + copy.getHighlights().size()
                + "/" + copy.getRedactions().size();
        System.out.println("[PASS] config repair: nulls take the constructor defaults");
    }

    /**
     * The colours the user asked to be able to tell apart must actually differ.
     *
     * <p>The requirement was explicit: header name against header value, the parts of a URL
     * against each other, and the parts of a body against each other. Two tokens sharing a
     * colour is not a crash, so nothing else in this suite would catch it.
     *
     * <p>The groups are what must differ. Inside a URL, Repeater paints the path and the query
     * key the same blue; the palette follows it, so asserting those two apart would contradict
     * the screenshots. What matters is that header, URL and body land in three different hues,
     * and that a key never looks like its value.
     */
    private static void checkTokenSeparation() {
        for (SyntaxPalette palette : new SyntaxPalette[]{SyntaxPalette.DARK, SyntaxPalette.LIGHT}) {
            String theme = palette == SyntaxPalette.DARK ? "dark" : "light";

            for (TokenType type : TokenType.values()) {
                assert palette.color(type) != null : theme + ": " + type + " resolves to no colour";
            }

            assertDistinct(theme, palette, TokenType.HEADER_NAME,
                    TokenType.URL_PATH, TokenType.HTML_TAG);
            assertDistinct(theme, palette, TokenType.METHOD, TokenType.TARGET);
            assertDistinct(theme, palette, TokenType.HEADER_NAME, TokenType.HEADER_VALUE);
            assertDistinct(theme, palette, TokenType.URL_QUERY_KEY, TokenType.URL_QUERY_VALUE);
            assertDistinct(theme, palette, TokenType.HTML_TAG, TokenType.HTML_ATTR,
                    TokenType.HTML_VALUE);
            assertDistinct(theme, palette, TokenType.JSON_KEY,
                    TokenType.JSON_STRING, TokenType.JSON_NUMBER);
        }
        System.out.println("[PASS] token separation: header, URL and body tokens stay distinct");
    }

    private static void assertDistinct(String theme, SyntaxPalette palette, TokenType... types) {
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                assert !palette.color(types[i]).equals(palette.color(types[j]))
                        : theme + ": " + types[i] + " and " + types[j]
                        + " share a colour, so they cannot be told apart";
            }
        }
    }

    /** A representative string for every token, so the reference sheet shows real content. */
    private static final Map<TokenType, String> SAMPLES = samples();

    private static Map<TokenType, String> samples() {
        Map<TokenType, String> m = new LinkedHashMap<>();
        m.put(TokenType.METHOD, "GET");
        m.put(TokenType.TARGET, "/api/v1/users?id=1337");
        m.put(TokenType.PROTOCOL, "HTTP/1.1");
        m.put(TokenType.URL_SCHEME, "https://");
        m.put(TokenType.URL_HOST, "target.example.com");
        m.put(TokenType.URL_PORT, ":8443");
        m.put(TokenType.URL_PATH, "/api/v1/users");
        m.put(TokenType.URL_QUERY_KEY, "id");
        m.put(TokenType.URL_QUERY_VALUE, "1337");
        m.put(TokenType.URL_QUERY_SEP, "? & =");
        m.put(TokenType.URL_FRAGMENT, "#section");
        m.put(TokenType.HEADER_NAME, "Authorization");
        m.put(TokenType.HEADER_COLON, ":");
        m.put(TokenType.HEADER_VALUE, "Bearer eyJhbGci");
        m.put(TokenType.STATUS_2XX, "200 OK");
        m.put(TokenType.STATUS_3XX, "302 Found");
        m.put(TokenType.STATUS_4XX, "403 Forbidden");
        m.put(TokenType.STATUS_5XX, "500 Server Error");
        m.put(TokenType.STATUS_TEXT, "OK");
        m.put(TokenType.JSON_KEY, "\"user\"");
        m.put(TokenType.JSON_STRING, "\"admin\"");
        m.put(TokenType.JSON_NUMBER, "1337");
        m.put(TokenType.JSON_LITERAL, "true");
        m.put(TokenType.HTML_TAG, "<script>");
        m.put(TokenType.HTML_ATTR, "class");
        m.put(TokenType.HTML_VALUE, "\"btn\"");
        m.put(TokenType.FORM_KEY, "username");
        m.put(TokenType.FORM_VALUE, "admin");
        m.put(TokenType.TEXT, "plain text");
        m.put(TokenType.MUTED, "-- boundary");
        m.put(TokenType.LINE_NUMBER, "42");
        m.put(TokenType.OMISSION, "...");
        return m;
    }

    /**
     * Writes the colour reference a human holds next to a Burp Repeater screenshot.
     *
     * <p>The palette was read off two screenshots at screen resolution, so the hues are
     * approximate and no assertion can settle them. This sheet is the artefact that can: it draws
     * every token on the card's own background, in the card's own font, with its hex beside it.
     * Where it disagrees with Repeater, the user corrects it in the settings dialog, which is why
     * the palette is editable in the app rather than compiled in.
     */
    private static void writePaletteSheet(boolean dark) throws Exception {
        Tokens t = dark ? Tokens.DARK : Tokens.LIGHT;
        SyntaxPalette palette = dark ? SyntaxPalette.DARK : SyntaxPalette.LIGHT;

        final int pad = 16;
        final int rowHeight = 26;
        final int width = 660;
        final int sampleX = 300;
        final int hexX = 570;

        int height = pad + 56 + TokenType.values().length * rowHeight + pad;

        // Drawn at 2x so it can be held against a retina screenshot without going soft.
        BufferedImage image = new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        applyHints(g);
        g.scale(2, 2);

        g.setColor(t.bgCard);
        g.fillRect(0, 0, width, height);

        g.setFont(t.uiSmallBold);
        g.setColor(t.textPrimary);
        g.drawString("Bảng màu cú pháp: nền " + (dark ? "tối" : "sáng"), pad, pad + 14);

        g.setFont(t.uiSmall);
        g.setColor(t.textMuted);
        g.drawString("Đối chiếu với ảnh Burp Repeater. Chỉnh trong Cài đặt > Màu cú pháp.", pad, pad + 34);

        int y = pad + 56;
        for (TokenType type : TokenType.values()) {
            String sample = SAMPLES.get(type);
            assert sample != null : "every token needs a sample line, missing: " + type;

            java.awt.Font font = SyntaxPalette.isBold(type) ? t.codeBold : t.code;
            int sampleWidth = g.getFontMetrics(font).stringWidth(sample);
            assert sampleX + sampleWidth < hexX - 8
                    : "the sample for " + type + " runs into the hex column";

            g.setFont(t.uiSmall);
            g.setColor(t.textSecondary);
            g.drawString(type.label(), pad, y + 16);

            g.setFont(font);
            g.setColor(palette.color(type));
            g.drawString(sample, sampleX, y + 16);

            g.setFont(t.code);
            g.setColor(t.textMuted);
            g.drawString(Tokens.toHex(palette.color(type)), hexX, y + 16);

            g.setColor(t.border);
            g.drawLine(pad, y + rowHeight - 1, width - pad, y + rowHeight - 1);
            y += rowHeight;
        }

        g.dispose();
        write(image, dark ? "palette_dark.png" : "palette_light.png");
        System.out.println("[PASS] palette reference: build/palette_"
                + (dark ? "dark" : "light") + ".png");
    }

    /**
     * The PoC tab must appear in Repeater and Logger, and in no other tool.
     *
     * <p>Burp asks for an editor in every tool that can show one. Returning an editor instead
     * of null is what puts the tab there, so this is the difference between a tab in Proxy
     * intercept and no tab at all.
     */
    private static void checkPocTabScope() {
        assert BurpExtender.showsPocTab(tool(ToolType.REPEATER)) : "Repeater must get the tab";
        assert BurpExtender.showsPocTab(tool(ToolType.LOGGER)) : "Logger must get the tab";
        // Proxy is where HTTP history is, and the tab only reads a message when it is looked at,
        // so carrying it there costs the list nothing while the user scrolls.
        assert BurpExtender.showsPocTab(tool(ToolType.PROXY)) : "Proxy must get the tab";

        for (ToolType other : new ToolType[]{ToolType.INTRUDER, ToolType.SCANNER,
                ToolType.TARGET, ToolType.SUITE, ToolType.SEQUENCER, ToolType.COMPARER,
                ToolType.EXTENSIONS}) {
            assert !BurpExtender.showsPocTab(tool(other)) : other + " must not get the tab";
        }
        assert !BurpExtender.showsPocTab(null) : "a missing tool source must not get the tab";

        // The tab is named in full. "PoC" beside Pretty, Raw, Hex and Unicode does not say which
        // extension put it there, and the name has to match the extension name Burp lists.
        assert "ScreenshotPoC".equals(BurpExtender.TAB_CAPTION)
                : "the tab must be captioned in full, it is \"" + BurpExtender.TAB_CAPTION + "\"";
        assert !BurpExtender.TAB_CAPTION.isEmpty() && !"PoC".equals(BurpExtender.TAB_CAPTION)
                : "the short caption must not come back";

        System.out.println("[PASS] " + BurpExtender.TAB_CAPTION
                + " tab scope: Repeater, Logger and Proxy, caption spelled out");
    }

    /**
     * Changing a rule leaves the reader where they were; changing the message starts at the top.
     *
     * <p>Both cases used to start at the top. The rebuild clears the document, and clearing it
     * drops the caret to offset zero and takes the viewport with it, so every highlight added
     * from the right-click menu threw the user back to line one of a message they were reading
     * at line forty. The two cases are told apart by whether the message changed, not by which
     * control was used: a line removed is a change to what is drawn, not to where the reader is.
     */
    private static void checkRuleChangeKeepsScrollPosition() {
        StringBuilder long_ = new StringBuilder("HTTP/1.1 200 OK\r\nServer: nginx\r\n\r\n");
        for (int i = 1; i <= 200; i++) long_.append("row ").append(i).append(" of the body\r\n");

        HttpExchangeData message = new HttpExchangeData();
        message.setRawResponse(long_.toString());

        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");
        StyledMessageView view = new StyledMessageView(false);
        view.updateData(message, config);
        paint(view, VIEW_WIDTH, 300);

        JTextPane pane = findPane(view);
        JScrollPane scroll = scrollPaneOf(pane);
        assert scroll != null : "the view must hold the text pane in a scroll pane";

        int shift = 400;
        scroll.getViewport().setViewPosition(new Point(0, shift));
        int before = scroll.getViewport().getViewPosition().y;
        assert before == shift : "the view must really be scrolled before this check means "
                + "anything, it sits at " + before;

        // The same message under a rule the user just added from the right-click menu. The text
        // is untouched, so the very rows on screen are the rows they are reading.
        config.getHighlights().add(new HighlightRule("row 42", false, ScopeTarget.RESPONSE, "#ff0000"));
        view.updateData(message, config);
        int after = scroll.getViewport().getViewPosition().y;
        assert Math.abs(after - before) <= 20
                : "adding a highlight must not move the view, it was at " + before
                + " and is at " + after;
        assert after != 0 : "and it must not be sent back to the top";

        // Removing lines rewrites the text, and the reader still stays put. The exact pixel is
        // not the contract here, since the rows below the removal moved up; staying off the top
        // is, because that is the fault the user reported.
        config.setResponseLineRanges("10-20");
        view.updateData(message, config);
        int removed = scroll.getViewport().getViewPosition().y;
        assert removed > 0
                : "removing lines must not send the view to the top, it is at " + removed;

        // A different message is the case that does start at the top. Long as well, so a view
        // position of zero means it was really reset rather than that there was nowhere to go.
        StringBuilder other = new StringBuilder("HTTP/1.1 200 OK\r\nServer: nginx\r\n\r\n");
        for (int i = 1; i <= 200; i++) other.append("line ").append(i).append(" of another\r\n");
        HttpExchangeData next = new HttpExchangeData();
        next.setRawResponse(other.toString());
        view.updateData(next, config);
        assert scroll.getViewport().getViewPosition().y == 0
                : "a new message must start at the top, it starts at "
                + scroll.getViewport().getViewPosition().y;

        System.out.println("[PASS] scroll position: a rule change holds at " + before + " -> "
                + after + ", a removal holds at " + removed + ", a new message starts at 0");
    }

    /**
     * A message announced to a tab nobody is looking at is not read and not drawn.
     *
     * <p>This is the whole cost of carrying the tab in Proxy history, where Burp announces every
     * row the user clicks and the user scrolls far more rows than they open tabs. The count of
     * reads is what is measured, not the time: reading a message renders both of its sides to
     * text, and on a multi-megabyte response that is the entire cost of the tab. A count of zero
     * is the only answer that means the scroll costs nothing.
     */
    private static void checkNothingIsReadUntilLookedAt() throws Exception {
        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");
        PocEditorPanel panel = new PocEditorPanel(false, config, () -> { });
        panel.setSize(VIEW_WIDTH, VIEW_HEIGHT);

        HttpExchangeData message = new HttpExchangeData();
        message.setRawResponse(VIEW_RESPONSE);

        AtomicInteger reads = new AtomicInteger();
        Object[] rows = new Object[12];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Object();
            // Twelve different rows scrolled past, as Proxy history announces them.
            panel.setSource(rows[i], () -> {
                reads.incrementAndGet();
                return message;
            });
        }
        assert reads.get() == 0
                : "a tab nobody is looking at must not read the message, it read " + reads.get()
                + " time(s)";
        assert panel.isPending() : "and it must remember that there is something to draw";
        assert findPane(panel).getText().isEmpty()
                : "and nothing may be drawn, the tab already shows "
                + findPane(panel).getText().length() + " characters";

        // The tab comes on screen. The twelve announcements collapse into one read, of the
        // message that is current now, rather than twelve reads of messages already scrolled past.
        BufferedImage shot = paint(panel, VIEW_WIDTH, VIEW_HEIGHT);
        write(shot, "test_lazy_tab.png");
        assert reads.get() == 1
                : "looking at the tab must read the message once, it read " + reads.get();
        assert !panel.isPending() : "and there must be nothing left pending";
        assert !findPane(panel).getText().isEmpty() : "and the message must be on screen";
        assert findPane(panel).getText().contains("Date: Mon, 01 Sep 2026")
                : "and it must be the message that was current, not an earlier one";

        // Painting again draws nothing new. A repaint must not re-read the message.
        paint(panel, VIEW_WIDTH, VIEW_HEIGHT);
        assert reads.get() == 1 : "a repaint must not read the message again, it read " + reads.get();

        // The same row announced again, by the reference Burp passes, is not read either.
        panel.setSource(rows[rows.length - 1], () -> {
            reads.incrementAndGet();
            return message;
        });
        assert reads.get() == 1
                : "the message already on screen must not be read again, it read " + reads.get();

        System.out.println("[PASS] lazy tab: 12 rows scrolled past cost 0 reads, looking at the "
                + "tab costs 1, a repaint and a repeat cost 0, capture build/test_lazy_tab.png");
    }

    /** A tool source that answers like Burp's: true only for the tool it was built with. */
    private static ToolSource tool(ToolType type) {
        return new ToolSource() {
            @Override public ToolType toolType() { return type; }
            @Override public boolean isFromTool(ToolType... candidates) {
                for (ToolType candidate : candidates) if (candidate == type) return true;
                return false;
            }
        };
    }

    // ------------------------------------------------------------------ the rule menu

    /**
     * Hide takes the characters out of the line; blur only paints over them.
     *
     * <p>This is the difference the feature exists for. A payload sitting inside one very long
     * line cannot be removed as a line, and a blur leaves it in the document: selectable,
     * copyable, and present in anything that reads the text rather than the picture. So the
     * measurement is on the line the processor produces, which is what both the view and every
     * export read, and the assertion is that the payload is not in it.
     */
    private static void checkHideTakesSpanOutOfLine() {
        String payload = "<script>alert(document.cookie)</script>";
        String longLine = "  <div class=\"row\">" + payload + "<span>tail</span></div>";
        String raw = "HTTP/1.1 200 OK\r\n"
                + "X-Demo: 1\r\n"
                + "\r\n"
                + longLine;

        TemplateConfig hidden = new TemplateConfig();
        hidden.getHighlights().clear();
        hidden.getRedactions().clear();
        hidden.getRedactions().add(new RedactionRule(payload, false, ScopeTarget.RESPONSE, 0, true));

        TextProcessor.ProcessedHttp processed = TextProcessor.processResponse(raw, hidden);
        assert processed.lines.size() == 4
                : "hiding inside a line must not change the line count, it is "
                + processed.lines.size();

        TextProcessor.LineItem body = processed.lines.get(3);
        assert !body.text.contains(payload)
                : "a hidden payload must not be in the line at all, the line is " + body.text;
        assert body.text.equals("  <div class=\"row\">"
                + TextProcessor.hiddenMarker(payload.length()) + "<span>tail</span></div>")
                : "the marker must replace the payload and leave both ends of the line alone, got "
                + body.text;
        assert body.originalLineNumber == 4 : "the gutter number must not move";
        assert processed.removedLineCount == 0 : "hiding a span is not removing a line";

        // A marker nobody can read the length of is a marker that cannot be told from a rule that
        // swallowed the wrong span, which is the mistake this feature invites.
        assert body.text.contains("[" + payload.length() + " chars hidden]")
                : "the marker must carry the count, got " + body.text;

        // The same rule without the flag is the blur that was already there, and it must leave
        // the text alone: blurring is a paint decision, not an edit.
        TemplateConfig blurred = new TemplateConfig();
        blurred.getHighlights().clear();
        blurred.getRedactions().clear();
        blurred.getRedactions().add(new RedactionRule(payload, false, ScopeTarget.RESPONSE, 0));
        assert longLine.equals(TextProcessor.processResponse(raw, blurred).lines.get(3).text)
                : "a blur must not touch the text";

        // Scope still applies: a response-side hide must not reach into the request.
        assert TextProcessor.processRequest("GET /a?" + payload + " HTTP/1.1\r\n\r\n", hidden)
                .lines.get(0).text.contains(payload)
                : "a response-only hide rule must not fire on a request";

        // Two rules over the same characters are one omission. Reporting the run twice would
        // claim characters went away that only went away once.
        String doubled = "</b>" + payload + "</i>";
        TemplateConfig overlapping = new TemplateConfig();
        overlapping.getHighlights().clear();
        overlapping.getRedactions().clear();
        overlapping.getRedactions().add(new RedactionRule(payload, false, ScopeTarget.RESPONSE, 0, true));
        overlapping.getRedactions().add(new RedactionRule(
                "alert(document.cookie)", false, ScopeTarget.RESPONSE, 0, true));
        String mergedLine = TextProcessor.processResponse(
                "HTTP/1.1 200 OK\r\n\r\n" + doubled, overlapping).lines.get(2).text;
        assert mergedLine.equals("</b>" + TextProcessor.hiddenMarker(payload.length()) + "</i>")
                : "overlapping hides must merge into one marker, got " + mergedLine;

        // The compiled rule reports its own style, so the renderer can sort the two apart.
        Rules.Result compiled = Rules.compile(hidden).forSide(false);
        assert compiled.hidden().size() == 1 && compiled.blurred().isEmpty()
                : "a hide rule must come back as hidden and not as blurred";
        assert compiled.hidden().get(0).isHide() : "the compiled rule carries the style";

        System.out.println("[PASS] hide: the span leaves the line, keeps the number, merges "
                + "overlaps, and does not fire on the other side");
    }

    /**
     * The right-click menu offers a hide beside the blur, and each counts on its own.
     *
     * <p>The selection is the only way to point at a span inside a line, so the entry has to be
     * there and it has to show how much was caught: a preview of 28 characters says nothing about
     * whether a drag landed on the whole payload or half of it.
     */
    private static void checkRuleMenuOffersHide() {
        TemplateConfig config = new TemplateConfig();
        config.getHighlights().clear();
        config.getRedactions().clear();

        String payload = "<script>alert(document.cookie)</script>";
        AtomicInteger applied = new AtomicInteger();
        JPopupMenu menu = MessageRuleMenu.build(config, target(payload, TokenType.TEXT, null),
                applied::incrementAndGet);

        JMenuItem hide = findMenuItem(menu, "Hide \"");
        assert hide != null : "the menu must offer a hide beside the blur";
        assert hide.getText().contains("(" + payload.length() + " chars)")
                : "a selection longer than the preview must say how long it is, the entry reads \""
                + hide.getText() + "\"";
        JMenuItem blur = findMenuItem(menu, "Blur \"");
        assert blur != null : "the blur must still be offered";

        click(hide);
        assert config.getRedactions().size() == 1 && config.getRedactions().get(0).isHide()
                : "clicking hide must add a hiding rule, got " + config.getRedactions();
        assert applied.get() == 1 : "the view is told to redraw once";
        click(hide);
        assert config.getRedactions().size() == 1
                : "hiding the same value twice must not stack two rules, got "
                + config.getRedactions().size();

        // Both styles on one value is a decision either way, so the blur must not be greyed out
        // by the hide that is already there.
        JPopupMenu again = MessageRuleMenu.build(config, target(payload, TokenType.TEXT, null),
                () -> { });
        JMenuItem blurAgain = findMenuItem(again, "Blur \"");
        assert blurAgain.isEnabled()
                : "a value that is already hidden must still be blurrable";
        click(blurAgain);
        assert config.getRedactions().size() == 2
                : "a blur must be addable over a value that is already hidden, got "
                + config.getRedactions();
        // Counted rather than indexed: which of the two was added first is not the point, and an
        // assertion on the order would break the day the menu lists them the other way round.
        int hiding = 0;
        RedactionRule hidingRule = null;
        RedactionRule blurringRule = null;
        for (RedactionRule r : config.getRedactions()) {
            if (r.isHide()) { hiding++; hidingRule = r; } else { blurringRule = r; }
        }
        assert hiding == 1
                : "one rule blurs and one hides, " + hiding + " of them hide: " + config.getRedactions();

        // A short selection needs no count; the preview already shows all of it.
        JPopupMenu shortMenu = MessageRuleMenu.build(config, target("abc", TokenType.TEXT, null),
                () -> { });
        assert !findMenuItem(shortMenu, "Hide \"").getText().contains("chars")
                : "a selection the preview shows in full needs no count, the entry reads \""
                + findMenuItem(shortMenu, "Hide \"").getText() + "\"";

        // A rule is tried against one line at a time, so a selection that ran past the end of a
        // line cannot match. Offering it would be an entry that silently does nothing, and a drag
        // that overshoots a line is the normal way to select a long one.
        JPopupMenu spanning = MessageRuleMenu.build(config,
                target("first line\nsecond line", TokenType.TEXT, null), () -> { });
        assert !findMenuItem(spanning, "Hide \"").isEnabled()
                && !findMenuItem(spanning, "Blur \"").isEnabled()
                : "a selection spanning two lines must not offer a rule that cannot match";
        JMenu highlightMenu = null;
        for (Component c : spanning.getComponents()) {
            if (c instanceof JMenu sub) highlightMenu = sub;
        }
        assert highlightMenu != null && !highlightMenu.isEnabled()
                && highlightMenu.getToolTipText() != null
                : "and it must say why, rather than leaving the user to guess";

        // The card in Settings is where a rule made from this menu turns up, so it has to say
        // which style the rule is and let it be changed. A card that always read "Blur" would
        // make a hidden rule look like a blurred one, with no control that could correct it.
        SegmentedControl<String> hideStyle = styleOf(
                RuleCard.forRedaction(hidingRule, quietListener()));
        assert RuleCard.HIDE.equals(hideStyle.getSelected())
                : "a hiding rule's card must show Hide, it shows " + hideStyle.getSelected();
        SegmentedControl<String> blurStyle = styleOf(
                RuleCard.forRedaction(blurringRule, quietListener()));
        assert RuleCard.BLUR.equals(blurStyle.getSelected())
                : "a blurring rule's card must show Blur, it shows " + blurStyle.getSelected();

        hideStyle.setSelected(RuleCard.BLUR);
        assert !hidingRule.isHide()
                : "the card's style control must reach the rule it belongs to";
        hideStyle.setSelected(RuleCard.HIDE);

        // And a way back out for each, since the menu is the only place a rule is made now.
        JPopupMenu removal = MessageRuleMenu.build(config, target(payload, TokenType.TEXT, null),
                () -> { });
        JMenuItem remove = findMenuItem(removal, "Remove");
        assert remove != null : "a hide must offer a way back out";
        click(remove);
        assert config.getRedactions().isEmpty()
                : "removing must drop both styles of the same value, got " + config.getRedactions();

        System.out.println("[PASS] menu offers Blur and Hide, each adding once and both removable");
    }

    /**
     * The Rules tab fits its controls, adds a rule of either kind, and refuses a duplicate.
     *
     * <p>The layout half is the bug this check was written for: a fourth button in the toolbar
     * was laid out past the right edge of the tab, so the only way to add a rule by hand was off
     * screen and the tab looked like it had no such button. The duplicate half is why a list of
     * rules stays searchable: the same pattern twice is two rows with nothing to tell them apart.
     */
    private static void checkRulesTab() throws Exception {
        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");

        int tabWidth = 600;
        int tabHeight = 470;
        RulesTab tab = new RulesTab(config, () -> { }, () -> { });
        BufferedImage shot = paint(tab, tabWidth, tabHeight);
        write(shot, "test_rules_tab.png");
        write(shot.getSubimage(0, 0, tabWidth, 110), "test_rules_toolbar.png");

        int overflowing = 0;
        String worst = "";
        for (Component c : descendants(tab)) {
            if (c.getWidth() == 0 || c instanceof JScrollPane) continue;
            if (c.getX() + c.getWidth() > tabWidth) {
                overflowing++;
                worst = c.getClass().getSimpleName() + " ends at " + (c.getX() + c.getWidth());
            }
        }
        assert overflowing == 0
                : "no control may run past the tab's edge, " + overflowing + " do, worst is " + worst;

        AbstractButton addCustom = button(tab, "Add custom rule");
        assert addCustom != null : "the tab must offer a way to add a rule by hand";
        assert addCustom.getX() + addCustom.getWidth() <= tabWidth
                : "and it must be inside the tab, it ends at "
                + (addCustom.getX() + addCustom.getWidth());
        AbstractButton addPreset = button(tab, "Add preset");
        assert addPreset != null : "the tab must offer the presets";

        // One button opening both kinds, rather than three buttons and a clipped fourth.
        JPopupMenu custom = tab.buildCustomMenu();
        assert findMenuItem(custom, "Highlight rule") != null
                && findMenuItem(custom, "Blur or hide rule") != null
                : "Add custom rule must offer both kinds of rule";

        int highlightsBefore = config.getHighlights().size();
        int redactionsBefore = config.getRedactions().size();

        // A preset the template already ships with must be refused, not doubled. This is the
        // common case: the Default template arrives carrying seven of the eight presets.
        JComboBox<?> presets = firstCombo(tab);
        assert presets != null : "the tab must offer the presets in a list";
        presets.setSelectedItem(RuleCard.Preset.AUTHORIZATION);
        click(addPreset);
        assert config.getRedactions().size() == redactionsBefore
                : "a preset already in the list must not be added again, it is now "
                + config.getRedactions().size() + " rules";
        assert tab.statusText().contains("Authorization header")
                : "and the tab must say which preset it refused, it says \"" + tab.statusText()
                + "\"";

        // A preset that is not there yet is added, and takes its listed name with it.
        presets.setSelectedItem(RuleCard.Preset.JWT);
        click(addPreset);
        assert config.getRedactions().size() == redactionsBefore + 1
                : "a preset the template does not carry must be added";
        assert "JWT".equals(config.getRedactions().get(redactionsBefore).getName())
                : "a preset must arrive with the name it was listed under, it arrived as \""
                + config.getRedactions().get(redactionsBefore).getName() + "\"";
        assert tab.statusText().contains("Presets apply")
                : "a successful add must put the hint back, it says \"" + tab.statusText() + "\"";

        // And the same refusal for a blank rule, which is the one a user adds twice by accident.
        click(findMenuItem(tab.buildCustomMenu(), "Highlight rule"));
        assert config.getHighlights().size() == highlightsBefore + 1
                : "a custom highlight must be added";
        click(findMenuItem(tab.buildCustomMenu(), "Highlight rule"));
        assert config.getHighlights().size() == highlightsBefore + 1
                : "a second blank highlight must be refused, there are now "
                + config.getHighlights().size();
        assert tab.statusText().contains("already in the list")
                : "and the refusal must be on screen, the tab says \"" + tab.statusText() + "\"";

        click(findMenuItem(tab.buildCustomMenu(), "Blur or hide rule"));
        assert config.getRedactions().size() == redactionsBefore + 2
                : "a custom redaction must be added";

        System.out.println("[PASS] rules tab: every control inside " + tabWidth + "px, both kinds "
                + "addable, a duplicate refused by name, capture build/test_rules_tab.png");
    }

    /**
     * A rule carries the name the user gave it, through the card, the copy and a reload.
     *
     * <p>The name exists to tell one rule from another in a list. It is only worth anything if it
     * survives the three places a rule is passed through, and the one that would silently drop it
     * is the copy the undo history keeps.
     */
    private static void checkRuleNames() {
        TemplateConfig config = new TemplateConfig();
        assert TemplateConfig.DEFAULT_DATE_NAME.equals(config.getHighlights().get(0).getName())
                : "the shipped Date rule must arrive named, it is \""
                + config.getHighlights().get(0).getName() + "\"";
        for (RedactionRule r : config.getRedactions()) {
            assert !r.getName().isEmpty()
                    : "every shipped redaction must arrive named: " + r.getPattern();
        }

        // The card writes the name through to the rule.
        RedactionRule rule = config.getRedactions().get(0);
        RuleCard card = RuleCard.forRedaction(rule, quietListener());
        JTextField nameField = null;
        for (Component c : descendants(card)) {
            // The card holds two fields, the name and the pattern. The name is the one whose
            // text is not the pattern, which is the only way to tell them apart without a seam.
            if (c instanceof JTextField field && field.getText().equals(rule.getName())
                    && !field.getText().equals(rule.getPattern())) {
                nameField = field;
                break;
            }
        }
        assert nameField != null : "the card must show the rule's name";
        nameField.setText("session cookie");
        assert "session cookie".equals(rule.getName())
                : "typing a name must reach the rule, it is \"" + rule.getName() + "\"";

        // And it survives a copy, which is what undo hands back and what loading a template does.
        TemplateConfig copy = config.copy();
        assert "session cookie".equals(copy.getRedactions().get(0).getName())
                : "a copy must carry the name";

        // A rule switched off must come back switched off. The copy used to rebuild every rule
        // with the constructor, which turns them all on: undo after disabling a rule turned it
        // back on and nothing said so.
        rule.setEnabled(false);
        assert !config.copy().getRedactions().get(0).isEnabled()
                : "a copy must carry the on/off state, not reset it";

        // A file written before rules had names: the shipped ones are named on load, and a name
        // the user typed is never overwritten.
        TemplateConfig old = new TemplateConfig();
        for (RedactionRule r : old.getRedactions()) r.setName("");
        old.getHighlights().get(0).setName("");
        old.getRedactions().get(0).setName("mine");
        TemplateManager.normalize(old);
        assert !old.getRedactions().get(0).getName().isEmpty() : "a shipped rule must be named";
        assert "mine".equals(old.getRedactions().get(0).getName())
                : "a name the user typed must never be overwritten, it is \""
                + old.getRedactions().get(0).getName() + "\"";
        assert TemplateConfig.DEFAULT_DATE_NAME.equals(old.getHighlights().get(0).getName())
                : "and the Date rule must be named too";

        // Gson round trip, since that is how the template is stored.
        TemplateConfig saved = new Gson().fromJson(new Gson().toJson(config), TemplateConfig.class);
        assert saved != null && "session cookie".equals(saved.getRedactions().get(0).getName())
                : "a name must survive the settings file";

        // A rename is a change worth undoing, so two rules differing only in name are not equal.
        UndoHistory history = new UndoHistory();
        TemplateConfig before = new TemplateConfig();
        history.remember(before);
        before.getRedactions().get(0).setName("renamed");
        assert history.undo(before) != null
                : "renaming a rule must count as a change, or Ctrl+Z would look broken";

        System.out.println("[PASS] rule names: shipped rules arrive named, survive the card, the "
                + "copy and the file, and a rename is undoable");
    }

    /**
     * The right-click menu is the only way to make a rule now, so it has to be reversible.
     *
     * <p>Two things are checked that nothing else would catch: that clicking the same colour
     * twice does not stack two identical rules, and that {@code Hide header} is offered only
     * where there is a header name. The hide list matches whole headers, so offering it on a
     * body token would collect entries that hide nothing.
     */
    private static void checkRuleMenu() {
        TemplateConfig config = new TemplateConfig();
        int base = config.getHighlights().size();
        assert base == 1 : "the default template starts with the Date rule";

        // The shipped redactions are counted, not assumed away: the menu's own rule has to be
        // told apart from the ones the template arrived with.
        int shipped = config.getRedactions().size();
        assert shipped == TemplateConfig.defaultRedactions().size()
                : "the default template must carry the shipped redactions, it has " + shipped;

        AtomicInteger applied = new AtomicInteger();
        JPopupMenu menu = MessageRuleMenu.build(config, target("hunter2", TokenType.TEXT, null),
                applied::incrementAndGet);

        JMenuItem yellow = findMenuItem(menu, "Yellow");
        assert yellow != null : "the highlight submenu must offer the default colour";
        click(yellow);
        assert config.getHighlights().size() == base + 1 : "a highlight must be added";
        click(yellow);
        assert config.getHighlights().size() == base + 1 : "the same highlight must not be added twice";
        assert applied.get() == 1 : "the view is told to redraw only for a change that happened";

        JMenuItem blur = findMenuItem(menu, "Blur");
        assert blur != null : "the menu must offer a blur redaction";
        click(blur);
        assert config.getRedactions().size() == shipped + 1
                && "hunter2".equals(config.getRedactions().get(shipped).getPattern())
                : "blur must add the redaction, got " + config.getRedactions();
        click(blur);
        assert config.getRedactions().size() == shipped + 1
                : "blurring the same value twice must not stack two rules, got "
                + config.getRedactions().size();

        // The solid blackout is gone from the extension, so the menu must not offer it. A leftover
        // entry would write a rule nothing can draw.
        assert findMenuItem(menu, "Black out") == null && findMenuItem(menu, "Solid") == null
                : "the menu must not offer a second redaction style";

        // A request-side redaction must not be offered as an undo for a response-side one.
        assert config.getRedactions().get(shipped).getTarget() == ScopeTarget.RESPONSE
                : "the rule took the side of the view it was made in";

        JPopupMenu onHeader = MessageRuleMenu.build(config,
                target("Date", TokenType.HEADER_NAME, "Date"), () -> { });
        JMenuItem hide = findMenuItem(onHeader, "Hide header");
        assert hide != null : "a header name must offer Hide header";
        click(hide);
        assert config.headerList().contains("Date") : "Hide header must reach the hide list";

        JPopupMenu onBody = MessageRuleMenu.build(config, target("body", TokenType.TEXT, null), () -> { });
        assert findMenuItem(onBody, "Hide header") == null
                : "a body token must not offer Hide header";

        JPopupMenu again = MessageRuleMenu.build(config, target("hunter2", TokenType.TEXT, null), () -> { });
        JMenuItem remove = findMenuItem(again, "Remove");
        assert remove != null : "a rule made from the menu must offer a way back out";
        click(remove);
        assert config.getHighlights().size() == base : "removing must drop the menu's highlight";
        assert config.getRedactions().size() == shipped
                : "removing must drop the menu's redaction and leave the shipped ones, got "
                + config.getRedactions().size();

        assert MessageRuleMenu.offeredColors().contains(TemplateConfig.DEFAULT_HIGHLIGHT)
                : "the default colour must be on offer";
        System.out.println("[PASS] rule menu: adds once, blurs once, Hide header only on a name");
    }

    private static MessageRuleMenu.Target target(String text, TokenType type, String headerName) {
        return new MessageRuleMenu.Target(text, type, headerName, false);
    }

    /**
     * Every preset has to be usable on the message it names, and the secret ones have to blur.
     *
     * <p>Each is run through the real path a preset takes: the pattern is put on a config, the
     * config is compiled, and the compiled rule is asked what it matches on a line that looks like
     * the thing the preset is for. A preset that matches nothing, or that matches the header name
     * instead of its value, is a button that does not do what its label says.
     *
     * <p>The header presets are held to the tighter claim: they must cover the value and only the
     * value. A reader still needs to see that the exchange carried an Authorization header; it is
     * the credential beside it that must not be in the screenshot.
     */
    private static void checkRedactionPresets() {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("AUTHORIZATION", "Authorization: Basic dXNlcjpwYXNzd29yZA==");
        lines.put("COOKIE", "Cookie: session_id=abc123; theme=dark");
        lines.put("SET_COOKIE", "Set-Cookie: sid=xyz789; HttpOnly; Secure");
        lines.put("PASSWORD", "username=alice&password=hunter2&submit=1");
        lines.put("API_KEY", "api_key=AKIAIOSFODNN7EXAMPLE");
        lines.put("SESSION", "session_id=abc123");
        lines.put("BEARER", "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def");
        lines.put("JWT", "token=eyJhbGciOiJIUzI1NiJ9.abc.def");

        for (RuleCard.Preset preset : RuleCard.Preset.values()) {
            if (preset.isHighlight()) continue;
            String line = lines.get(preset.name());
            assert line != null
                    : "every redaction preset needs a line to be tried on, " + preset.name()
                    + " has none";

            TemplateConfig config = new TemplateConfig();
            config.getHighlights().clear();
            config.getRedactions().clear();
            config.getRedactions().add(new RedactionRule(preset.pattern(), true,
                    ScopeTarget.BOTH, preset.captureGroup()));

            Rules.Result compiled = Rules.compile(config);
            assert compiled.redactions().size() == 1
                    : preset.label() + " must compile, it did not";

            List<String> hits = new ArrayList<>();
            compiled.redactions().get(0).find(line, (s, e) -> hits.add(line.substring(s, e)));
            assert hits.size() == 1
                    : preset.label() + " must match the line it is for, it matched " + hits;

            String matched = hits.get(0);
            String name = line.substring(0, Math.max(line.indexOf(':'), line.indexOf('=')));
            assert !matched.contains(name)
                    : preset.label() + " must leave the field name on screen, it matched \""
                    + matched + "\"";
            assert matched.length() >= 6
                    : preset.label() + " must cover a whole value, it matched \"" + matched + "\"";
        }

        // The highlight presets are the other half of the same list, and they are not redactions.
        for (RuleCard.Preset preset : RuleCard.Preset.values()) {
            if (!preset.isHighlight()) continue;
            assert preset.colorHex() != null
                    : preset.label() + " is a highlight and must name a colour";
        }

        System.out.println("[PASS] presets: " + lines.size()
                + " redaction presets each match their own message and blur the value alone, "
                + "the highlight presets all carry a colour");
    }

    /**
     * Dragging down the gutter and right-clicking has to remove the lines that were dragged over.
     *
     * <p>Driven through real mouse events on the gutter rather than by calling the menu with
     * numbers the test picked, so the y-to-line mapping is part of what is checked. That mapping
     * is the only thing standing between the numbers the user sees and the numbers the field
     * takes, and it is where the removed-line feature was wrong before.
     *
     * <p>The menu is read without being shown: a display-less JVM cannot open a popup, which is
     * why the view builds it separately from showing it.
     */
    private static void checkGutterMenuRemovesLines() throws Exception {
        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");

        HttpExchangeData data = new HttpExchangeData();
        data.setRawResponse(VIEW_RESPONSE);

        StyledMessageView view = new StyledMessageView(false);
        view.updateData(data, config);
        paint(view, VIEW_WIDTH, VIEW_HEIGHT);

        JTextPane pane = findPane(view);
        assert pane != null : "the view must hold a text pane";
        Component gutter = rowHeaderOf(pane);
        assert gutter != null : "the gutter must be the row header of the pane's scroll pane";

        // The y of the first row and of the third, taken from the layout the gutter itself reads.
        Element root = pane.getDocument().getDefaultRootElement();
        int from = gutterY(pane, gutter, root.getElement(0).getStartOffset()) + 2;
        int to = gutterY(pane, gutter, root.getElement(2).getStartOffset()) + 2;

        drag(gutter, from, to);
        JPopupMenu menu = view.buildGutterLineMenu();
        assert menu != null : "a drag down the gutter must leave a run of lines selected";

        // One entry, and it names the run that was dragged over. The hide entry that sat beside
        // it was dropped, so a second item here is a regression rather than a spare.
        assert menu.getComponentCount() == 1
                : "the line menu offers one action, it has " + describe(menu);
        JMenuItem remove = (JMenuItem) menu.getComponents()[0];
        assert remove.getText().startsWith("Remove lines 1-3")
                : "the menu must name the lines that were dragged over, it says " + remove.getText();
        assert remove.isEnabled() : "a span nothing has touched yet must be offered";
        assert findMenuItem(menu, "Hide") == null
                : "the hide entry must be gone, the menu has " + describe(menu);

        click(remove);
        assert "1-3".equals(config.getResponseLineRanges())
                : "the menu must write the span into the response field, it wrote "
                + config.getResponseLineRanges();
        assert view.getRemovedLineCount() == 3
                : "the three lines must be gone from the view, it removed "
                + view.getRemovedLineCount();
        assert view.buildGutterLineMenu() == null
                : "the selection must be dropped once the lines it named are gone";

        // A right-click has to act on the whole run, wherever on it the pointer lands. This is
        // the reported bug: on Windows the popup flag is set on the release, not the press, so
        // the press looked like a plain click and collapsed the run to the row under the pointer
        // before the menu was ever built.
        //
        // Driven as the two real events, press then release, because the whole fault lived in
        // the gap between them.
        TemplateConfig textConfig = new TemplateConfig();
        textConfig.setHeadersToHide("");
        StyledMessageView textView = new StyledMessageView(false);
        textView.updateData(data, textConfig);
        paint(textView, VIEW_WIDTH, VIEW_HEIGHT);

        JTextPane textPane = findPane(textView);
        assert textPane != null : "the second view must hold a text pane";
        Component textGutter = rowHeaderOf(textPane);
        assert textGutter != null : "the second view must have its gutter";

        Element textRoot = textPane.getDocument().getDefaultRootElement();
        drag(textGutter, gutterY(textPane, textGutter, textRoot.getElement(0).getStartOffset()) + 2,
                gutterY(textPane, textGutter, textRoot.getElement(2).getStartOffset()) + 2);

        // The release is the row under the pointer, which is inside the run, and the press is on
        // the last row of it as well, so a run that survives the press is the whole of 1-3.
        rightPress(textGutter,
                gutterY(textPane, textGutter, textRoot.getElement(2).getStartOffset()) + 2);
        JPopupMenu afterPress = textView.buildGutterLineMenu();
        assert afterPress != null : "the run must survive a right press";
        JMenuItem block = (JMenuItem) afterPress.getComponents()[0];
        assert block.getText().startsWith("Remove lines 1-3")
                : "a right press must not shrink the run to the row it landed on, the menu "
                + "says " + block.getText();

        JPopupMenu onText = textView.buildContextMenu(textRoot.getElement(0).getStartOffset());
        assert onText != null : "a right-click over the text must open a menu";
        JMenuItem removeFromText = findMenuItem(onText, "Remove lines 1-3 from the PoC");
        assert removeFromText != null
                : "a right-click over the text must offer the lines selected in the gutter, "
                + "the menu has " + describe(onText);
        assert findMenuItem(onText, "Blur") != null
                : "and the rule actions must still be on it, the menu has " + describe(onText);
        click(removeFromText);
        assert "1-3".equals(textConfig.getResponseLineRanges())
                : "the entry taken from the text must write the remove field, it wrote "
                + textConfig.getResponseLineRanges();
        assert textView.getRemovedLineCount() == 3
                : "the three lines must be gone, the view removed "
                + textView.getRemovedLineCount();

        // A scrolled message. The gutter scrolls with the text, so a press at a y is a press at
        // that y in the message and not in the window, and the two differ by the scroll offset.
        // Read the window coordinate against the message rows and every line comes out one
        // screen too early, which is what made removing lines wrong once the user scrolled.
        //
        // Long enough to really scroll. Asking a short message for a view position past its end
        // moves the view anyway, and a layout then puts it back at the top for the honest reason
        // that there is nowhere to scroll to, which is not the fault being checked here.
        StringBuilder tall = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 1; i <= 40; i++) tall.append("X-Filler-").append(i).append(": value\r\n");
        tall.append("\r\nbody");
        HttpExchangeData tallData = new HttpExchangeData();
        tallData.setRawResponse(tall.toString());

        TemplateConfig scrolledConfig = new TemplateConfig();
        scrolledConfig.setHeadersToHide("");
        StyledMessageView scrolledView = new StyledMessageView(false);
        scrolledView.updateData(tallData, scrolledConfig);
        paint(scrolledView, VIEW_WIDTH, 160);

        JTextPane scrolledPane = findPane(scrolledView);
        assert scrolledPane != null : "the scrolled view must hold a text pane";
        JScrollPane scrolledScroll = scrollPaneOf(scrolledPane);
        assert scrolledScroll != null : "the pane must be in a scroll pane";

        // Far down the message on purpose. The fault grew with the scroll offset: added to it
        // rather than converted, the error was the offset itself, so it has to be measured where
        // it is large and not where it is nearly invisible.
        int shift = 400;
        scrolledScroll.getViewport().setViewPosition(new Point(0, shift));
        assert scrolledPane.getVisibleRect().y == shift
                : "the probe view must really be scrolled before this check means anything, it "
                + "sits at " + scrolledPane.getVisibleRect().y;

        // And then a layout, which is what every rebuild runs. A row header whose view is shorter
        // than its viewport is pinned back to the top right here, and the numbers then begin at
        // line one while the text stays scrolled: the two disagree by the scroll offset, so the
        // run the user drags over is that far from the run the field is given.
        layoutTree(scrolledView);

        Component scrolledGutter = scrolledScroll.getRowHeader().getView();
        Element scrolledRoot = scrolledPane.getDocument().getDefaultRootElement();


        assert scrolledPane.getVisibleRect().y == shift
                : "the text must still be where it was scrolled to, it sits at "
                + scrolledPane.getVisibleRect().y;
        assert scrolledScroll.getRowHeader().getViewPosition().y == shift
                : "the numbers must scroll the same distance as the text, the strip sits at "
                + scrolledScroll.getRowHeader().getViewPosition().y
                + " and the text at " + scrolledPane.getVisibleRect().y;
        assert scrolledGutter.getY() == -shift
                : "the strip must be placed as far up as the text is scrolled, it sits at "
                + scrolledGutter.getY();
        assert scrolledGutter.getHeight() >= shift + scrolledPane.getVisibleRect().height
                : "the strip must be long enough to cover the text below the scroll, it is "
                + scrolledGutter.getHeight() + " tall";

        // Placed correctly is not the same as drawn correctly, so the strip is painted and the
        // numbers looked for beside the lines they belong to. A short strip can sit at the right
        // offset and still have no ink over most of the text. The last column is left out of
        // every band because it carries the strip's own border.
        BufferedImage strip = render(scrolledGutter, scrolledGutter.getWidth(),
                scrolledGutter.getHeight());
        Color gutterGround = Theme.tokens().bgGutter;
        int visibleBottom = shift + scrolledPane.getVisibleRect().height;
        int numbered = 0;
        for (int i = 0; i < scrolledRoot.getElementCount() - 1; i++) {
            int top = rowTop(scrolledPane, scrolledRoot.getElement(i).getStartOffset());
            if (top < shift) continue;
            if (top >= visibleBottom) break;
            int next = rowTop(scrolledPane, scrolledRoot.getElement(i + 1).getStartOffset());
            // Whole lines only. A line clipped by the edge of the window has its number drawn
            // outside the window too, and reading that as a missing number would blame the strip
            // for the edge of the screen.
            if (next > visibleBottom) continue;
            Rectangle band = new Rectangle(0, top, Math.max(1, scrolledGutter.getWidth() - 1),
                    next - top);
            assert countOtherThan(strip, band, gutterGround) > 0
                    : "line " + (i + 1) + " is on screen, so its number has to be drawn beside "
                    + "it at y " + top;
            numbered++;
        }
        assert numbered >= 3
                : "the strip has to be judged over several visible lines, it showed " + numbered;

        // Three rows that are on screen, named in the window coordinates a mouse event carries.
        // Found rather than fixed: which lines are under the pointer at this offset is exactly
        // what the check is about, so the test must not be the thing that decides it.
        int firstRow = -1;
        for (int i = 0; i < scrolledRoot.getElementCount() - 2; i++) {
            if (rowTop(scrolledPane, scrolledRoot.getElement(i).getStartOffset()) >= shift + 6) {
                firstRow = i;
                break;
            }
        }
        assert firstRow >= 0 : "the scrolled message must have rows below the scroll offset";

        int pressY = gutterY(scrolledPane, scrolledGutter,
                scrolledRoot.getElement(firstRow).getStartOffset()) + 2;
        int releaseY = gutterY(scrolledPane, scrolledGutter,
                scrolledRoot.getElement(firstRow + 2).getStartOffset()) + 2;
        drag(scrolledGutter, pressY, releaseY);

        String scrolledSpan = (firstRow + 1) + "-" + (firstRow + 3);
        // The same run has to be lit up in the text, at this distance down the message. This is
        // the reported fault read from the other end: the gutter run was right and the text it
        // named was a screenful away, because the offset between the two was added rather than
        // converted.
        String lit = scrolledPane.getSelectedText();
        assert lit != null && lit.startsWith("X-Filler-" + firstRow)
                : "the lines the numbers picked have to be the lines the text shows selected, "
                + "rows " + scrolledSpan + " are lit as \"" + lit + "\"";
        assert scrolledPane.getCaret().isSelectionVisible()
                : "a selection nobody can see is a selection that cannot be checked";

        JPopupMenu scrolledMenu = scrolledView.buildGutterLineMenu();
        assert scrolledMenu != null : "a drag down a scrolled gutter must select a run";
        JMenuItem scrolledItem = (JMenuItem) scrolledMenu.getComponents()[0];
        assert scrolledItem.getText().startsWith("Remove lines " + scrolledSpan)
                : "a scrolled gutter must name the lines under the pointer, rows " + scrolledSpan
                + ", it says " + scrolledItem.getText();
        click(scrolledItem);
        assert scrolledSpan.equals(scrolledConfig.getResponseLineRanges())
                : "the scrolled drag must write the lines it covered, it wrote "
                + scrolledConfig.getResponseLineRanges();

        // A span the field already covers is refused. Reached by building the menu for a span
        // directly: a view that has the lines removed cannot show them to be dragged over, so
        // this branch is a guard rather than something the UI can walk into.
        JPopupMenu reopened = MessageRuleMenu.buildForLines(config, false, 1, 3, () -> { });
        JMenuItem again = (JMenuItem) reopened.getComponents()[0];
        assert !again.isEnabled() && again.getText().startsWith("Lines 1-3 already removed")
                : "a span already removed must be refused, the menu says " + again.getText();

        System.out.println("[PASS] gutter menu: lines 1-3 dragged over, removed whole from either "
                + "pane, refused once the field already has them");
    }

    /**
     * A run of numbers picked in the gutter has to light up the same lines in the text.
     *
     * <p>Two views of one document. The numbers alone read as a selection that has not happened,
     * and the user is about to drop these lines from the PoC, so the text is where they check they
     * picked the ones they meant. Read from the pane's own selection rather than from a flag the
     * gutter keeps, because a flag can be right while nothing is lit up.
     */
    private static void checkGutterSelectionSelectsText() throws Exception {
        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");

        HttpExchangeData data = new HttpExchangeData();
        data.setRawResponse(VIEW_RESPONSE);

        StyledMessageView view = new StyledMessageView(false);
        view.updateData(data, config);
        paint(view, VIEW_WIDTH, VIEW_HEIGHT);

        JTextPane pane = findPane(view);
        assert pane != null : "the view must hold a text pane";
        Component gutter = rowHeaderOf(pane);
        assert gutter != null : "the gutter must be the row header of the pane's scroll pane";

        Element root = pane.getDocument().getDefaultRootElement();
        int start = root.getElement(2).getStartOffset();
        int end = root.getElement(4).getEndOffset() - 1;
        String expected = pane.getDocument().getText(start, end - start);
        assert expected.contains("hunter2")
                : "the sample run has to be worth checking, it reads " + expected;

        drag(gutter, gutterY(pane, gutter, start) + 2,
                gutterY(pane, gutter, root.getElement(4).getStartOffset()) + 2);

        assert pane.getSelectionStart() == start && pane.getSelectionEnd() == end
                : "lines 3 to 5 must be selected in the text, the selection is "
                + pane.getSelectionStart() + ".." + pane.getSelectionEnd()
                + " and the lines are " + start + ".." + end;
        assert expected.equals(pane.getSelectedText())
                : "the selected text must be the lines the numbers name, it is \""
                + pane.getSelectedText() + "\"";
        assert pane.getCaret().isSelectionVisible()
                : "the highlight has to be painted, not only set: a pane that has never held the "
                + "focus reports its own selection as hidden";

        // Dragging back up has to take the selection with it, and the last line of the message has
        // no line break to stop on, so a range ending there must not reach past the document.
        int lastIndex = root.getElementCount() - 1;
        int lastStart = root.getElement(lastIndex).getStartOffset();
        drag(gutter, gutterY(pane, gutter, start) + 2, gutterY(pane, gutter, lastStart) + 2);

        assert pane.getSelectionStart() == start && pane.getSelectionEnd() == pane.getDocument().getLength()
                : "a run to the last line must select up to the end of the document, it is "
                + pane.getSelectionStart() + ".." + pane.getSelectionEnd();
        assert pane.getSelectedText().endsWith("body")
                : "the last line must come along, the selection ends \""
                + pane.getSelectedText() + "\"";

        // A right press on a run that is already selected must leave the text selection as it
        // stands: the menu is about to act on the whole run, and a press that lit up one line
        // would say the run had been thrown away.
        rightPress(gutter, gutterY(pane, gutter, lastStart) + 2);
        assert pane.getSelectedText().endsWith("body")
                : "a right press must not shrink the text selection, it is now \""
                + pane.getSelectedText() + "\"";

        System.out.println("[PASS] gutter selection: the run of numbers lights up the same lines "
                + "in the text, to the end of the message, and survives a right press");
    }

    /**
     * The search box has to say which hit is on screen, and the arrows have to move between them.
     *
     * <p>A count alone is not enough on a message where the same word appears fifteen times: the
     * reader can see that there are fifteen and has no way to walk them. The position is read
     * from the caret, not from the label, so a label that ticks up while the view stays put is a
     * failure rather than a pass.
     *
     * <p>Wrapping at either end is checked because it is the difference between an arrow that
     * works and one that looks stuck on the last hit.
     */
    private static void checkSearchStepper() {
        StringBuilder raw = new StringBuilder("HTTP/1.1 200 OK\r\n");
        for (int i = 2; i <= 6; i++) raw.append("X-Token-").append(i).append(": abc\r\n");
        raw.append("\r\nbody");
        String message = raw.toString();

        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");
        HttpExchangeData data = new HttpExchangeData();
        data.setRawResponse(message);

        StyledMessageView view = new StyledMessageView(false);
        view.updateData(data, config);
        paint(view, VIEW_WIDTH, VIEW_HEIGHT);

        JTextPane pane = findPane(view);
        assert pane != null : "the view must hold a text pane";

        // Where each hit lands in the rendered document, so the caret can be held to it.
        String shown;
        try {
            shown = pane.getDocument().getText(0, pane.getDocument().getLength());
        } catch (BadLocationException e) {
            throw new AssertionError("the document must be readable", e);
        }
        List<Integer> hits = new ArrayList<>();
        int at = shown.indexOf("abc");
        while (at >= 0) {
            hits.add(at);
            at = shown.indexOf("abc", at + 1);
        }
        assert hits.size() == 5 : "the probe message must carry five hits, it has " + hits.size();

        view.getSearchField().setText("abc");
        assert "1 of 5".equals(view.getSearchMatchLabel())
                : "typing must land on the first hit of five, the box says "
                + view.getSearchMatchLabel();
        assert pane.getCaretPosition() == hits.get(0)
                : "the caret must be on the first hit at " + hits.get(0) + ", it is at "
                + pane.getCaretPosition();

        AbstractButton next = searchButton(view, "Next match (Enter)");
        AbstractButton previous = searchButton(view, "Previous match");

        click(next);
        assert "2 of 5".equals(view.getSearchMatchLabel())
                : "the down arrow must step forward, the box says " + view.getSearchMatchLabel();
        assert pane.getCaretPosition() == hits.get(1)
                : "stepping forward must move the view to the second hit at " + hits.get(1)
                + ", it is at " + pane.getCaretPosition();

        click(previous);
        click(previous);
        assert "5 of 5".equals(view.getSearchMatchLabel())
                : "stepping back past the first hit must wrap to the last, the box says "
                + view.getSearchMatchLabel();
        assert pane.getCaretPosition() == hits.get(4)
                : "the wrapped-to hit is the last one at " + hits.get(4) + ", the caret is at "
                + pane.getCaretPosition();

        click(next);
        assert "1 of 5".equals(view.getSearchMatchLabel())
                : "stepping forward past the last hit must wrap to the first, the box says "
                + view.getSearchMatchLabel();

        // A word the message does not carry says so, and the arrows do nothing rather than
        // stepping through a list that is not there.
        view.getSearchField().setText("zzz");
        assert "no matches".equals(view.getSearchMatchLabel())
                : "a word the message does not carry must be reported, the box says "
                + view.getSearchMatchLabel();
        click(next);
        assert "no matches".equals(view.getSearchMatchLabel())
                : "stepping with nothing to step through must change nothing, the box says "
                + view.getSearchMatchLabel();

        view.getSearchField().setText("");
        assert "".equals(view.getSearchMatchLabel())
                : "an empty box must say nothing at all, it says " + view.getSearchMatchLabel();

        System.out.println("[PASS] search stepper: 5 hits walked forward, wrapped at both ends, "
                + "and refused when there is nothing to step through");
    }

    /**
     * A new template must already be hiding the secrets, without anything being set up first.
     *
     * <p>The Date highlight and these rules are the two defaults a reader meets on the first
     * open, and they point opposite ways: the Date should be easy to find, a session cookie
     * should not be legible in a screenshot pasted into a report. A preset the user has to go and
     * add is a preset that is not there when the screenshot is taken.
     *
     * <p>The second half is the upgrade. A settings file written before these rules existed has
     * to pick them up on the next load, and a file already at this version has to keep whatever
     * the user did to them, deletions included. Both directions are checked, because a migration
     * that runs every load would bring a deleted Cookie rule back forever.
     */
    private static void checkDefaultRedactions() {
        TemplateConfig fresh = new TemplateConfig();
        assert fresh.getRedactions().size() == TemplateConfig.defaultRedactions().size()
                : "a new template must ship the secret rules, it has "
                + fresh.getRedactions().size();

        // Reconciling a config that is already current must change nothing. The version is what
        // says so, and a duplicate check catches the other failure: appending the shipped rules
        // to a config that already holds them.
        TemplateManager.normalize(fresh);
        assert fresh.getDefaultsVersion() == TemplateConfig.DEFAULTS_VERSION
                : "a reconciled template is at the current rule set, it says "
                + fresh.getDefaultsVersion();
        assert fresh.getRedactions().size() == TemplateConfig.defaultRedactions().size()
                : "reconciling must not duplicate the shipped rules, the count went to "
                + fresh.getRedactions().size();

        for (RedactionRule r : fresh.getRedactions()) {
            assert r.isEnabled() : "a shipped rule that is off hides nothing: " + r.getPattern();
            assert r.getTarget() == ScopeTarget.BOTH
                    : "a secret can be sent or returned: " + r.getPattern();
        }

        // Through the real renderer, on a response that carries the things these rules are for.
        String raw = "HTTP/1.1 200 OK\r\n"
                + "Authorization: Basic dXNlcjpwYXNzd29yZA==\r\n"
                + "Cookie: session_id=abc123; theme=dark\r\n"
                + "Set-Cookie: sid=xyz789; HttpOnly\r\n"
                + "X-Powered-By: PHP/8.1\r\n"
                + "\r\n"
                + "user=alice&password=hunter2";

        Rules.Result compiled = Rules.compile(fresh).forSide(false);
        List<String> blurred = new ArrayList<>();
        for (TextProcessor.LineItem item : TextProcessor.processResponse(raw, fresh).lines) {
            for (Rules.Rule rule : compiled.redactions()) {
                rule.find(item.text, (s, e) -> blurred.add(item.text.substring(s, e)));
            }
        }

        // Containment rather than equality, so the assertion is about the secret being covered
        // and not about where a capture group happens to start. A rule that widens to take in
        // the header name is still doing its job.
        for (String secret : List.of("dXNlcjpwYXNzd29yZA==", "abc123", "xyz789", "hunter2")) {
            boolean covered = false;
            for (String span : blurred) {
                if (span.contains(secret)) covered = true;
            }
            assert covered
                    : "the default rules must cover \"" + secret + "\", they covered " + blurred;
        }
        for (String plain : List.of("PHP", "X-Powered-By")) {
            for (String span : blurred) {
                assert !span.contains(plain)
                        : "a rule reached something that is not a secret: " + span;
            }
        }

        // A file from before these rules existed: version 0, no redactions of its own.
        TemplateConfig old = new Gson().fromJson("{\"name\":\"Default\",\"redactions\":[]}",
                TemplateConfig.class);
        assert old != null && old.getDefaultsVersion() == 0
                : "a file that does not mention the version reads as 0";
        TemplateManager.normalize(old);
        assert old.getRedactions().size() == TemplateConfig.defaultRedactions().size()
                : "loading an older file must bring it up to the shipped rules, it has "
                + old.getRedactions().size();

        // And a file already brought forward keeps its deletions. The stamp comes first, since a
        // config still at zero is one the migration has not run on yet.
        TemplateConfig current = TemplateManager.normalize(new TemplateConfig());
        current.getRedactions().remove(0);
        int afterDelete = current.getRedactions().size();
        TemplateManager.normalize(current);
        assert current.getRedactions().size() == afterDelete
                : "a deleted rule must stay deleted, the count went from " + afterDelete
                + " to " + current.getRedactions().size();

        System.out.println("[PASS] default redactions: "
                + TemplateConfig.defaultRedactions().size()
                + " shipped on, covering credentials, and added once to an older file");
    }

    /** The component a text pane scrolls as its row header, or null. */
    /** The scroll pane a text pane sits in, or null. */
    private static JScrollPane scrollPaneOf(JTextPane pane) {
        for (Container c = pane.getParent(); c != null; c = c.getParent()) {
            if (c instanceof JScrollPane scroll) return scroll;
        }
        return null;
    }

    private static Component rowHeaderOf(JTextPane pane) {
        for (Container c = pane.getParent(); c != null; c = c.getParent()) {
            if (c instanceof JScrollPane scroll && scroll.getRowHeader() != null) {
                return scroll.getRowHeader().getView();
            }
        }
        return null;
    }

    /** Presses at {@code from} and drags to {@code to}, the two events a selection is made of. */
    private static void drag(Component gutter, int from, int to) {
        long when = System.currentTimeMillis();
        int x = 4;
        // The dragged event carries the button still held down, as a real one does. Without the
        // mask a drag is indistinguishable from a move with no button, which the gutter now
        // refuses so that a right press on the way to the menu cannot extend the run.
        gutter.dispatchEvent(new MouseEvent(gutter, MouseEvent.MOUSE_PRESSED, when, 0,
                x, from, 1, false, MouseEvent.BUTTON1));
        gutter.dispatchEvent(new MouseEvent(gutter, MouseEvent.MOUSE_DRAGGED, when + 1,
                InputEvent.BUTTON1_DOWN_MASK, x, to, 1, false, MouseEvent.BUTTON1));
        gutter.dispatchEvent(new MouseEvent(gutter, MouseEvent.MOUSE_RELEASED, when + 2, 0,
                x, to, 1, false, MouseEvent.BUTTON1));
    }

    /**
     * A right press, as Windows delivers it.
     *
     * <p>No popup flag on the press, which is the whole point: the gutter has to learn a menu is
     * coming from the button alone. Sent without the release, because the release is what opens
     * the menu and a display-less JVM cannot open one.
     */
    private static void rightPress(Component gutter, int y) {
        gutter.dispatchEvent(new MouseEvent(gutter, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), InputEvent.BUTTON3_DOWN_MASK,
                4, y, 1, false, MouseEvent.BUTTON3));
    }

    /** First entry under {@code menu}, submenus included, whose label starts with {@code prefix}. */
    private static JMenuItem findMenuItem(JPopupMenu menu, String prefix) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenu sub) {
                JMenuItem nested = findMenuItem(sub.getPopupMenu(), prefix);
                if (nested != null) return nested;
            } else if (c instanceof JMenuItem item
                    && item.getText() != null && item.getText().startsWith(prefix)) {
                return item;
            }
        }
        return null;
    }

    /** What a menu holds, for an assertion that has to say what it found instead. */
    private static List<String> describe(JPopupMenu menu) {
        List<String> out = new ArrayList<>();
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem item) out.add(String.valueOf(item.getText()));
            else out.add(c.getClass().getSimpleName());
        }
        return out;
    }

    /**
     * Fires an entry directly instead of through {@code doClick}.
     *
     * <p>{@code AbstractButton.doClick} posts the press with a timer, which needs a running
     * event loop to land. Calling the listener is the same code path the click ends in, without
     * the wait.
     */
    private static void click(AbstractButton item) {
        for (ActionListener listener : item.getActionListeners()) {
            listener.actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, "test"));
        }
    }

    /** A button of the search strip, found by the tooltip that tells the user what it does. */
    private static AbstractButton searchButton(StyledMessageView view, String tooltip) {
        for (Component c : view.getSearchControls().getComponents()) {
            if (c instanceof AbstractButton button && tooltip.equals(button.getToolTipText())) {
                return button;
            }
        }
        throw new AssertionError("the search strip has no button labelled \"" + tooltip + "\"");
    }

    // ------------------------------------------------------------------ the view

    /**
     * A response with one header to black out, one to blur, and the Date the default marks.
     *
     * <p>Its own message rather than the sample exchange: the assertions below locate ranges by
     * the text in them, so every value has to appear exactly once.
     */
    private static final String VIEW_RESPONSE =
            "HTTP/1.1 200 OK\r\n"
            + "Date: Mon, 01 Sep 2026 10:00:00 GMT\r\n"
            + "Server: nginx\r\n"
            + "X-Api-Key: hunter2\r\n"
            + "Content-Length: 4\r\n"
            + "\r\n"
            + "body";

    private static final int VIEW_WIDTH = 900;
    private static final int VIEW_HEIGHT = 320;

    /** How far down the tall message the value worth hiding sits. */
    private static final int DEEP_LINE = 1500;
    private static final int DEEP_LINES = 2000;
    private static final String DEEP_VALUE = "deepsecretvalue";

    /** Narrow enough that the long request line cannot fit on one row. */
    private static final int NARROW_WIDTH = 380;

    private record ViewFixture(StyledMessageView view, JTextPane pane, String text) { }

    /**
     * The redaction marks are the part of this view that can be silently wrong.
     *
     * <p>A blackout the text shows through is worse than no blackout at all, because the author
     * believes the secret is covered. So the check is a pixel count over the marked rectangle:
     * the control image has to show glyphs there, and the marked image has to show nothing but
     * the block colour. Without the control the check would pass on an empty region.
     */
    private static void checkView() throws Exception {
        Theme.Mode previous = Theme.getMode();

        Theme.setMode(Theme.Mode.DARK);
        ViewFixture dark = fixture(true);
        BufferedImage darkShot = paint(dark.view(), VIEW_WIDTH, VIEW_HEIGHT);
        write(darkShot, "test_styled_dark.png");

        Theme.setMode(Theme.Mode.LIGHT);
        ViewFixture light = fixture(true);
        BufferedImage lightShot = paint(light.view(), VIEW_WIDTH, VIEW_HEIGHT);
        write(lightShot, "test_styled_light.png");

        double different = differingPixels(darkShot, lightShot);
        assert different > 0.50
                : "a theme switch must repaint the view, only " + pct(different) + " differs";
        System.out.println("[PASS] theme switch repaints " + pct(different) + " of the view");

        // The marks are checked in the dark theme, which is the one the extension opens in.
        Theme.setMode(Theme.Mode.DARK);
        checkHighlightKeepsGlyphs(dark);
        checkBlurHidesTheValue(dark);
        checkRedactionCoversLastGlyph(dark);
        checkMarksFollowTheViewport();
        checkWrap();
        checkWrapCoversEveryCharacter();

        Theme.setMode(previous);
    }

    /** A view over {@link #VIEW_RESPONSE}, with or without the two redactions. */
    private static ViewFixture fixture(boolean withRedactions) {
        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");
        // The shipped redactions are dropped here so the pixel measurements are of the two rules
        // this fixture is about. They match this message too, which is the point of them: the
        // default api_key rule covers the value of X-Api-Key, so a control that kept them would
        // not be a control.
        config.getRedactions().clear();
        if (withRedactions) {
            config.getRedactions().add(new RedactionRule("hunter2", false,
                    ScopeTarget.RESPONSE, 0));
            config.getRedactions().add(new RedactionRule("nginx", false,
                    ScopeTarget.RESPONSE, 0));
        }

        HttpExchangeData data = new HttpExchangeData();
        data.setRawResponse(VIEW_RESPONSE);

        StyledMessageView view = new StyledMessageView(false);
        view.updateData(data, config);

        JTextPane pane = findPane(view);
        assert pane != null : "the view must hold a text pane";
        return new ViewFixture(view, pane, documentText(pane));
    }

    /**
     * The highlight must tint the row without hiding what is written on it.
     *
     * <p>It is drawn as a translucent wash under the glyphs, so both have to be visible: a
     * highlight that swallowed the text would make the screenshot worthless.
     */
    private static void checkHighlightKeepsGlyphs(ViewFixture marked) throws Exception {
        BufferedImage shot = paint(marked.view(), VIEW_WIDTH, VIEW_HEIGHT);
        Rectangle rect = inset(rectFor(marked, "Date:"), 2);

        Color card = Theme.tokens().bgCard;
        Color glyph = paletteColor(TokenType.HEADER_NAME);
        int tinted = 0;
        int glyphPixels = 0;
        for (int y = rect.y; y < rect.y + rect.height; y++) {
            for (int x = rect.x; x < rect.x + rect.width; x++) {
                int rgb = shot.getRGB(x, y) & 0xFFFFFF;
                if (rgb != (card.getRGB() & 0xFFFFFF)) tinted++;
                if (close(rgb, glyph, 40)) glyphPixels++;
            }
        }

        assert tinted > rect.width
                : "the Date header must be tinted, only " + tinted + " pixel(s) moved in a "
                + rect.width + "x" + rect.height + " region";
        assert glyphPixels > 3
                : "the highlighted header must stay readable, only " + glyphPixels
                + " pixel(s) near " + Tokens.toHex(glyph) + " survived";

        // The band itself, taken as the most common colour that is neither the card nor a glyph
        // pixel: the antialiased edges of the letters would answer for the wash otherwise. A wash
        // that is technically there and invisible in a screenshot is the failure this catches.
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int y = rect.y; y < rect.y + rect.height; y++) {
            for (int x = rect.x; x < rect.x + rect.width; x++) {
                int rgb = shot.getRGB(x, y) & 0xFFFFFF;
                if (rgb == (card.getRGB() & 0xFFFFFF) || close(rgb, glyph, 40)) continue;
                counts.merge(rgb, 1, Integer::sum);
            }
        }
        int wash = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(-1);
        int distance = wash < 0 ? 0 : channelDistance(wash, card);
        assert distance >= WASH_MIN_DISTANCE
                : "the highlight wash has to be visible in a screenshot, the band is "
                + (wash < 0 ? "absent" : Tokens.toHex(new Color(wash)))
                + ", only " + distance + " away from the card colour " + Tokens.toHex(card);

        System.out.println("[PASS] highlight: " + tinted + " tinted pixel(s), "
                + glyphPixels + " glyph pixel(s) still drawn on top, band "
                + Tokens.toHex(new Color(wash)) + " is " + distance + " from the card colour");
    }

    /**
     * A blurred value has to be unreadable while still looking like a patch, not a blackout.
     *
     * <p>What is asserted is the contrast of the strokes themselves, measured in the marked image
     * and in a control of the same message with nothing redacted. The control is what proves the
     * measurement can see a glyph before it is used to say one is gone, and the noise in the
     * marked region is what the strokes are compared against: a stroke that does not rise above
     * the noise around it is not a shape, and a value whose strokes are not shapes cannot be read
     * off the screenshot.
     *
     * <p>The first two settings of the blur were chosen against a fraction of the original
     * contrast, and both passed while the value was still legible. This one is chosen against the
     * noise floor instead, which is the number that matches what a reader can do.
     */
    private static void checkBlurHidesTheValue(ViewFixture marked) throws Exception {
        BufferedImage shot = paint(marked.view(), VIEW_WIDTH, VIEW_HEIGHT);
        BufferedImage control = paint(fixture(false).view(), VIEW_WIDTH, VIEW_HEIGHT);
        Rectangle rect = inset(rectFor(marked, "nginx"), 2);

        Contrast contrast = strokeContrast(shot, control, rect);
        assert contrast.strokes() > 20
                : "the control must draw \"nginx\" as strokes in "
                + rect.width + "x" + rect.height + ", it has " + contrast.strokes();
        assert contrast.control() > 60
                : "in the control the strokes must stand out, they measure "
                + round(contrast.control());

        // The noise has to be there at all: a flat fill is the old blackout, not a blur.
        assert contrast.shades() > 3
                : "a blur must be noise, not a flat fill, it has " + contrast.shades() + " shade(s)";

        // Not readable, and this is the assertion that says so. The strokes have to come out
        // below the noise the blur lays over them: a stroke that rises above it is a shape the
        // eye can follow, and following the shapes is reading. An earlier setting passed on the
        // fraction test below and still showed every character of a 50-character boundary
        // string, which is why the floor is asserted directly rather than inferred.
        assert contrast.marked() < NOISE_FLOOR * contrast.noise()
                : "the blurred value still rises above its own noise, its strokes measure "
                + round(contrast.marked()) + " against " + round(contrast.noise())
                + " of noise in the same region, and "
                + round(contrast.control()) + " unredacted over " + contrast.strokes() + " strokes";

        // And it stays a fraction of what it was even so, so that a region measured against a
        // quiet background cannot pass by having loud noise and legible letters at once.
        assert contrast.marked() < READABLE_FRACTION * contrast.control()
                : "the blurred value is still too legible, its strokes measure "
                + round(contrast.marked()) + " against " + round(contrast.control())
                + " unredacted, which is " + round(contrast.marked() / contrast.control())
                + " of it, at or above the " + round(READABLE_FRACTION) + " a reader can use";

        System.out.println("[PASS] blur: strokes measure " + round(contrast.marked())
                + " against " + round(contrast.control()) + " unredacted, below the "
                + round(contrast.noise()) + " noise floor, " + contrast.shades() + " shades");
    }

    /**
     * A rule has to reach a line the reader scrolled to, not only the lines that were on screen.
     *
     * <p>The rule pass covers the visible run of lines and a margin, because running every
     * expression over every line of a long response is the whole cost of a rebuild on one. That
     * is only sound if the pass follows the viewport, and this is the check that says it does: a
     * value fifteen hundred lines down a two-thousand-line message, redacted and measured after
     * scrolling to it, against a control of the same message scrolled the same way.
     *
     * <p>Without the scroll the value is off screen and this check would pass on a view that
     * never marks anything below the fold, which is exactly the fault it exists to catch.
     */
    private static void checkMarksFollowTheViewport() throws Exception {
        ViewFixture marked = deepFixture(true);
        ViewFixture control = deepFixture(false);

        // Laid out once before anything is scrolled, so the position below is in a coordinate
        // system that exists.
        paint(marked.view(), VIEW_WIDTH, VIEW_HEIGHT);
        paint(control.view(), VIEW_WIDTH, VIEW_HEIGHT);

        Rectangle below = rectFor(marked, DEEP_VALUE);
        int shift = Math.max(0, below.y - 40);
        assert shift > 0 : "the value must start below the fold, it is already at " + below;

        place(marked, shift);
        place(control, shift);

        BufferedImage shot = paint(marked.view(), VIEW_WIDTH, VIEW_HEIGHT);
        BufferedImage plain = paint(control.view(), VIEW_WIDTH, VIEW_HEIGHT);

        Rectangle window = rectFor(marked, DEEP_VALUE);
        assert window.y >= 0 && window.y + window.height <= VIEW_HEIGHT
                : "the value must be inside the painted box for this to mean anything, it is at "
                + window + " of a " + VIEW_HEIGHT + " pixel box scrolled " + shift + " down";

        // The deep line is a form field in a body, so its value is drawn in the form value's
        // colour rather than a header value's. The measurement is otherwise the same one.
        Contrast contrast = strokeContrast(shot, plain, inset(window, 2),
                paletteColor(TokenType.FORM_VALUE));
        assert contrast.strokes() > 20
                : "the control must draw " + DEEP_VALUE + " as strokes in " + window.width + "x"
                + window.height + ", it has " + contrast.strokes()
;
        assert contrast.control() > 60
                : "in the control the strokes must stand out, they measure "
                + round(contrast.control());
        assert contrast.marked() < READABLE_FRACTION * contrast.control()
                : "a rule must still cover a line the reader scrolled to, the value measures "
                + round(contrast.marked()) + " against " + round(contrast.control()) + " unredacted";

        System.out.println("[PASS] marks follow the viewport: " + DEEP_VALUE + " at line "
                + DEEP_LINE + " is redacted after scrolling " + shift
                + "px down, its strokes measure " + round(contrast.marked()) + " against "
                + round(contrast.control()) + " unredacted");
    }

    /** Scrolls the view to a y and refuses to carry on if it did not go there. */
    private static void place(ViewFixture fixture, int y) {
        JScrollPane scroll = scrollPaneOf(fixture.pane());
        assert scroll != null : "the view must hold the text pane in a scroll pane";
        scroll.getViewport().setViewPosition(new Point(0, y));
        int at = scroll.getViewport().getViewPosition().y;
        assert at == y : "the view must really be scrolled to " + y + " before this means "
                + "anything, it sits at " + at;
    }

    /**
     * A message long enough to scroll, with one value worth hiding well below the first screen.
     *
     * <p>Long on purpose: a value that fits on the first screen is inside whatever window the
     * rule pass happens to cover, so it proves nothing about following the viewport.
     */
    private static ViewFixture deepFixture(boolean withRedaction) {
        StringBuilder raw = new StringBuilder("HTTP/1.1 200 OK\r\n\r\n");
        for (int i = 1; i <= DEEP_LINES; i++) {
            if (i == DEEP_LINE) {
                raw.append("token=").append(DEEP_VALUE).append("\r\n");
            } else {
                raw.append("body line ").append(i).append(" of the message\r\n");
            }
        }

        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");
        config.getRedactions().clear();
        if (withRedaction) {
            config.getRedactions().add(new RedactionRule(DEEP_VALUE, false,
                    ScopeTarget.RESPONSE, 0));
        }

        HttpExchangeData data = new HttpExchangeData();
        data.setRawResponse(raw.toString());

        StyledMessageView view = new StyledMessageView(false);
        // Painted while empty, and before the message arrives. A view that has never been laid
        // out has no viewport to ask which lines are on screen, and the rule pass falls back to
        // the whole message when it cannot tell, which would leave this check passing on a view
        // whose marks never move.
        paint(view, VIEW_WIDTH, VIEW_HEIGHT);
        view.updateData(data, config);

        JTextPane pane = findPane(view);
        assert pane != null : "the view must hold a text pane";
        return new ViewFixture(view, pane, documentText(pane));
    }

    /**
     * The last character of a redacted value has to be covered.
     *
     * <p>Checked on the final character's own cell, because that is the cell that used to be
     * missed. The patch was sized from the last character's box, which {@code modelToView2D}
     * answers with a zero-width caret box, so the patch stopped at that character's left edge and
     * the value leaked by exactly one glyph. The control image is what proves the cell is not
     * simply blank: without it this would pass on any region at all.
     *
     * <p>A cell that was missed shows its stroke at the contrast it has in the control, which is
     * the same measurement the blur check uses, so an off-by-one glyph cannot hide under the
     * noise.
     */
    private static void checkRedactionCoversLastGlyph(ViewFixture marked) throws Exception {
        BufferedImage shot = paint(marked.view(), VIEW_WIDTH, VIEW_HEIGHT);
        BufferedImage control = paint(fixture(false).view(), VIEW_WIDTH, VIEW_HEIGHT);

        for (String value : List.of("hunter2", "nginx")) {
            Rectangle cell = lastCharCell(marked, value);
            Contrast contrast = strokeContrast(shot, control, inset(cell, -1));
            assert contrast.strokes() > 0
                    : "the control must draw the last character of \"" + value + "\" in "
                    + cell.width + "x" + cell.height + " at " + cell;
            assert contrast.marked() < READABLE_FRACTION * contrast.control()
                    : "the last character of \"" + value + "\" must be covered, its cell measures "
                    + round(contrast.marked()) + " against " + round(contrast.control())
                    + " unredacted";
        }

        System.out.println("[PASS] redaction edge: the final cell of \"hunter2\" and \"nginx\" is "
                + "redacted to the same fraction as the rest of the value");
    }

    /**
     * How strongly the glyph strokes in a region stand out, in an image and in its control.
     *
     * @param strokes  pixels the control draws as a stroke
     * @param control  the mean contrast of those pixels in the control image
     * @param marked   the same measure in the marked image
     * @param noise    how far the marked image's own pixels spread in the region
     * @param shades   distinct colours in the marked region, so a flat fill can be told from noise
     */
    private record Contrast(int strokes, double control, double marked, double noise, int shades) { }

    /**
     * Measures the strokes of a region the same way in both images.
     *
     * <p>A pixel is a stroke when the control draws the glyph colour there, and its contrast is
     * how far its luminance sits above the median of a small window around it. The window is the
     * local background, which is what lets the measurement survive the noise: the noise moves
     * every pixel in the window, and the median of them does not.
     *
     * <p>Both images are measured against their own background, so the two numbers are directly
     * comparable: an untouched stroke measures what it always did, and a washed one measures what
     * is left of it.
     */
    private static Contrast strokeContrast(BufferedImage marked, BufferedImage control, Rectangle rect) {
        return strokeContrast(marked, control, rect, paletteColor(TokenType.HEADER_VALUE));
    }

    /**
     * The same measurement over text that is not a header value.
     *
     * <p>The stroke pixel is found by its colour in the control, and a body line is drawn in the
     * body colour rather than the header value's. Everything else is identical, so a body region
     * and a header region are still measured the same way.
     */
    private static Contrast strokeContrast(BufferedImage marked, BufferedImage control,
                                           Rectangle rect, Color glyph) {
        int strokes = 0;
        double controlSum = 0;
        double markedSum = 0;
        Set<Integer> shades = new HashSet<>();
        List<Double> lums = new ArrayList<>();

        for (int y = rect.y; y < rect.y + rect.height; y++) {
            for (int x = rect.x; x < rect.x + rect.width; x++) {
                if (!inside(marked, x, y)) continue;
                shades.add(marked.getRGB(x, y) & 0xFFFFFF);
                lums.add(luminance(marked.getRGB(x, y)));
                if (!close(control.getRGB(x, y) & 0xFFFFFF, glyph, 60)) continue;
                strokes++;
                controlSum += luminance(control.getRGB(x, y)) - windowMedian(control, x, y);
                markedSum += luminance(marked.getRGB(x, y)) - windowMedian(marked, x, y);
            }
        }

        double mean = lums.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = 0;
        for (double lum : lums) variance += (lum - mean) * (lum - mean);
        double noise = lums.isEmpty() ? 0 : Math.sqrt(variance / lums.size());

        return new Contrast(strokes,
                strokes == 0 ? 0 : controlSum / strokes,
                strokes == 0 ? 0 : markedSum / strokes,
                noise, shades.size());
    }

    /** Median luminance of the pixels within {@code radius} of (x, y), the pixel itself included. */
    private static double windowMedian(BufferedImage image, int x, int y) {
        int radius = 4;
        List<Double> values = new ArrayList<>();
        for (int yy = y - radius; yy <= y + radius; yy++) {
            for (int xx = x - radius; xx <= x + radius; xx++) {
                if (inside(image, xx, yy)) values.add(luminance(image.getRGB(xx, yy)));
            }
        }
        values.sort(Double::compare);
        return values.get(values.size() / 2);
    }

    private static boolean inside(BufferedImage image, int x, int y) {
        return x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight();
    }

    private static double luminance(int rgb) {
        return 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /**
     * Wrapping is what keeps a long URL on screen, and it is a property of the pane's width.
     *
     * <p>With wrapping on the pane must be exactly the viewport width, which is what leaves the
     * paragraph view a span to wrap into and leaves no horizontal scrollbar. With wrapping off
     * the pane must grow to its longest line, which is what produces one.
     */
    private static void checkWrap() throws Exception {
        HttpExchangeData data = new HttpExchangeData();
        data.setRawRequest("GET /api/v1/report/export?from=2026-01-01&to=2026-09-12&format=csv"
                + "&include_metadata=true&filter_status=all&sort=created_at HTTP/1.1\r\n"
                + "Host: demo.example.com\r\n\r\n");

        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");

        StyledMessageView view = new StyledMessageView(true);
        view.updateData(data, config);
        JTextPane pane = findPane(view);
        assert pane != null : "the view must hold a text pane";
        assert view.isWrap() : "wrapping is on by default";

        write(paint(view, NARROW_WIDTH, 300), "test_wrap_on.png");

        String text = documentText(pane);
        int start = text.indexOf("GET /api/v1/report");
        int end = text.indexOf('\n', start);
        assert start >= 0 && end > start : "the long request line must be on screen";

        Component viewport = pane.getParent();
        assert viewport != null : "the pane must sit in a viewport";

        assert pane.getScrollableTracksViewportWidth()
                : "with wrapping on the pane must track the viewport width";
        assert pane.getWidth() == viewport.getWidth()
                : "with wrapping on the pane must be the viewport width, it is " + pane.getWidth()
                + " in a " + viewport.getWidth() + "px viewport";

        int firstRow = rowTop(pane, start);
        int lastRow = rowTop(pane, end - 1);
        assert lastRow > firstRow
                : "a " + (end - start) + " character line must take more than one row at "
                + NARROW_WIDTH + "px, both ends are on row y=" + firstRow;

        view.setWrap(false);
        write(paint(view, NARROW_WIDTH, 300), "test_wrap_off.png");

        assert !pane.getScrollableTracksViewportWidth()
                : "with wrapping off the pane must grow to its longest line";
        assert pane.getWidth() > viewport.getWidth()
                : "with wrapping off the pane must be wider than the viewport, it is "
                + pane.getWidth() + " in a " + viewport.getWidth() + "px viewport";
        assert rowTop(pane, end - 1) == rowTop(pane, start)
                : "with wrapping off the whole line must sit on one row";
        System.out.println("[PASS] wrap: " + (end - start) + " chars take "
                + (lastRow - firstRow) + "px of rows at " + NARROW_WIDTH
                + "px wide, and one row with a scrollbar when off");
    }

    /**
     * Wrapping has to put every character of a long line on screen, not only the first rows of it.
     *
     * <p>The check above proves the rows exist and the pane tracks the viewport. This one proves
     * nothing was dropped on the way: a row that is clipped rather than wrapped still reports
     * rows, but the offsets past the clip sit at an x beyond the pane's right edge. Every
     * character is asked where it is, at three widths, and again after wrapping off and on, which
     * is where a pane left at the width of its longest line would still be laying text out.
     */
    private static void checkWrapCoversEveryCharacter() throws Exception {
        HttpExchangeData data = new HttpExchangeData();
        data.setRawRequest("POST /api/v1/report/export?from=2026-01-01&to=2026-09-12&format=csv"
                + "&include_metadata=true&filter_status=all&sort=created_at&token="
                + "abcdef0123456789abcdef0123456789 HTTP/1.1\r\n"
                + "Host: demo.example.com\r\n"
                + "X-Api-Key: hunter2hunter2hunter2hunter2hunter2hunter2hunter2hunter2\r\n\r\nbody");

        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("");

        StyledMessageView view = new StyledMessageView(true);
        view.updateData(data, config);
        assert view.isWrap() : "wrapping is on by default";

        paint(view, 1200, 300);
        for (int width : new int[]{1200, 640, 420}) {
            paint(view, width, 300);
            int outside = charactersOutsideThePane(findPane(view));
            assert outside == 0
                    : "with wrapping on every character must be inside the pane, " + outside
                    + " are past its right edge at " + width + "px";
        }

        view.setWrap(false);
        paint(view, NARROW_WIDTH, 300);
        view.setWrap(true);
        paint(view, NARROW_WIDTH, 300);

        JTextPane pane = findPane(view);
        assert pane != null : "the view must hold a text pane";
        Component viewport = pane.getParent();
        assert pane.getWidth() == viewport.getWidth()
                : "the pane must come back to the viewport width, it is " + pane.getWidth()
                + " in a " + viewport.getWidth() + "px viewport";
        assert charactersOutsideThePane(pane) == 0
                : "after wrapping off and on again every character must still be inside the pane, "
                + charactersOutsideThePane(pane) + " are outside it";

        System.out.println("[PASS] wrap coverage: every character of a long line is inside the "
                + "pane at 1200, 640 and " + NARROW_WIDTH + "px, and again after off and on");
    }

    /** How many characters of the document are drawn to the right of the pane's inner edge. */
    private static int charactersOutsideThePane(JTextPane pane) throws Exception {
        assert pane != null : "the view must hold a text pane";
        String text = documentText(pane);
        int innerRight = pane.getWidth() - pane.getInsets().right;
        int outside = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') continue;
            Rectangle2D r = pane.modelToView2D(i);
            if (r == null) continue;
            if ((int) Math.ceil(r.getX() + r.getWidth()) > innerRight) outside++;
        }
        return outside;
    }

    // ------------------------------------------------------------------ undo

    /**
     * The toolbar has to be one short row, and everything in it has to fit.
     *
     * <p>Measured rather than looked at. Every pixel of this strip is a pixel of message that is
     * not in the screenshot, and a control that overflows it is either clipped or pushing the row
     * taller than it claims to be. The children are checked against their own bounds, so a
     * control that drifted outside the strip fails here instead of quietly disappearing.
     */
    private static void checkToolbarIsOneRow() throws Exception {
        TemplateConfig config = new TemplateConfig();
        PocEditorPanel panel = new PocEditorPanel(false, config, () -> { });
        BufferedImage shot = paint(panel, VIEW_WIDTH, VIEW_HEIGHT);
        write(shot, "test_toolbar.png");

        BorderLayout layout = (BorderLayout) panel.getLayout();
        Component header = layout.getLayoutComponent(BorderLayout.NORTH);
        assert header instanceof JComponent : "the toolbar must be the north component";

        // Two rows of text would be about 40, and the old three-row header measured 56.
        assert header.getHeight() <= 34
                : "the toolbar must stay a single short row, it is " + header.getHeight()
                + " pixels tall";

        int overflowing = 0;
        for (Component child : ((Container) header).getComponents()) {
            if (child instanceof Container row) {
                for (Component inner : row.getComponents()) {
                    if (inner.getWidth() == 0) continue;
                    if (inner.getX() + inner.getWidth() > header.getWidth()) overflowing++;
                }
            }
        }
        assert overflowing == 0
                : "nothing in the toolbar may run past its edge, " + overflowing + " control(s) do";

        System.out.println("[PASS] toolbar: one row of " + header.getHeight()
                + " pixels, every control inside it, capture build/test_toolbar.png");
    }

    /**
     * The Headers tab has to give its room to the list, not to the words around it.
     *
     * <p>Measured, because this is the tab the user asked to be tightened and the failure is
     * silent: a caption that wraps to three lines and a control that runs off the right edge both
     * look fine to the compiler. The dialog is 620 wide and cannot be built without a display,
     * which is why the tab is a class of its own that can be laid out here.
     */
    private static void checkHeadersTabLayout() throws Exception {
        TemplateConfig config = new TemplateConfig();
        config.setHeadersToHide("X-Powered-By\nServer");

        HttpExchangeData data = new HttpExchangeData();
        data.setRawResponse(VIEW_RESPONSE);
        data.setRawRequest("GET /a HTTP/1.1\r\nHost: x\r\nCookie: a=b\r\n\r\n");

        // The width the dialog gives a tab: its own size less the tabbed pane's insets.
        int tabWidth = 600;
        int tabHeight = 470;
        HeadersTab tab = new HeadersTab(config, data, () -> { }, () -> { });
        BufferedImage shot = paint(tab, tabWidth, tabHeight);
        write(shot, "test_headers_tab.png");
        // The control strip on its own, at its own scale: a downscaled dialog hides a clipped
        // row, and a clipped row is exactly what this check is for.
        write(shot.getSubimage(0, tabHeight - 130, tabWidth, 130), "test_headers_controls.png");

        JTextArea area = findArea(tab);
        assert area != null : "the tab must hold the header list";

        // Two thirds of the tab is the list. Before the compaction the controls took the tab's
        // whole preferred height twice over and the list was capped at 260 pixels.
        assert area.getHeight() >= tabHeight * 2 / 3
                : "the list must take the bulk of the tab, it is " + area.getHeight()
                + " of " + tabHeight;

        int overflowing = 0;
        String worst = "";
        for (Component c : descendants(tab)) {
            if (c.getWidth() == 0 || c instanceof JScrollPane) continue;
            if (c.getX() + c.getWidth() > tabWidth) {
                overflowing++;
                worst = c.getClass().getSimpleName() + " ends at " + (c.getX() + c.getWidth());
            }
        }
        assert overflowing == 0
                : "no control may run past the tab's edge, " + overflowing + " do, worst is " + worst;

        // The controls below the list are three single-line rows, with the gaps between them.
        int controls = tabHeight - area.getHeight() - tab.getInsets().top - tab.getInsets().bottom;
        assert controls <= 130
                : "the controls under the list must stay compact, they take " + controls + " pixels";

        // Nothing may hang off the bottom either. The strip is the last thing in the tab, so a
        // row that needs more height than it was given is cut in half and reads as a rendering
        // fault rather than a layout one.
        int lowest = 0;
        for (Component c : descendants(tab)) {
            if (c.getWidth() == 0 || c instanceof JScrollPane || c instanceof JViewport) continue;
            lowest = Math.max(lowest, c.getY() + c.getHeight());
        }
        assert lowest <= tabHeight
                : "no row may hang off the bottom of the tab, the lowest ends at " + lowest;

        System.out.println("[PASS] headers tab: list takes " + area.getHeight() + " of " + tabHeight
                + ", controls take " + controls + ", nothing past the edge, capture "
                + "build/test_headers_tab.png");
    }

    /** A button of a tab, found by its label. The label is what the user reads and clicks. */
    private static AbstractButton button(Container root, String label) {
        for (Component c : descendants(root)) {
            if (c instanceof AbstractButton b && label.equals(b.getText())) return b;
        }
        return null;
    }

    /** The first combo box under {@code root}, for a tab whose only list is the preset picker. */
    private static JComboBox<?> firstCombo(Container root) {
        for (Component c : descendants(root)) {
            if (c instanceof JComboBox<?> combo) return combo;
        }
        return null;
    }

    /** The header list, found by walking the tab rather than by exposing it. */
    private static JTextArea findArea(Container root) {
        for (Component c : descendants(root)) {
            if (c instanceof JTextArea area) return area;
        }
        return null;
    }

    /** Every component under {@code root}, depth first. */
    private static List<Component> descendants(Container root) {
        List<Component> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            out.add(c);
            if (c instanceof Container container) out.addAll(descendants(container));
        }
        return out;
    }

    /**
     * Ctrl+Z has to put the settings back the way they were.
     *
     * <p>Driven through the panel, which is where the history lives and where the shortcut is
     * bound: an undo stack nothing calls is not an undo. The history is checked on its own as
     * well, for the case the panel cannot show, a state remembered by a menu that was opened and
     * dismissed without changing anything.
     */
    private static void checkUndo() {
        TemplateConfig config = new TemplateConfig();
        PocEditorPanel panel = new PocEditorPanel(false, config, () -> { });

        KeyStroke ctrlZ = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
        assert panel.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(ctrlZ) != null
                : "Ctrl+Z must be bound on the panel";

        int base = config.getHighlights().size();
        int shipped = config.getRedactions().size();
        int hiddenBefore = config.headerList().size();

        panel.rememberForUndo();
        config.getHighlights().add(new HighlightRule("hunter2", false, ScopeTarget.RESPONSE, "#ff0000"));
        config.getRedactions().add(new RedactionRule("nginx", false, ScopeTarget.RESPONSE, 0));
        config.addHeaderToHide("X-Powered-By");
        config.setWrapText(false);
        config.getSyntaxColors().put("TEXT", "#123456");

        panel.undoLastChange();

        assert config.getHighlights().size() == base : "undo must drop the highlight that was added";
        assert config.getRedactions().size() == shipped
                : "undo must drop the redaction that was added and keep the shipped ones, got "
                + config.getRedactions().size();
        assert config.headerList().size() == hiddenBefore : "undo must drop the hidden header";
        assert config.isWrapText() : "undo must put the wrap toggle back";
        assert config.getSyntaxColors().isEmpty() : "undo must put the colors back";
        assert !config.headerList().contains("X-Powered-By") : "the header name must be gone";

        // Nothing was remembered before this one, so there is nothing left to walk back to.
        panel.undoLastChange();
        assert config.getHighlights().size() == base : "an empty history must change nothing";

        UndoHistory history = new UndoHistory();
        TemplateConfig state = new TemplateConfig();
        history.remember(state);
        assert history.undo(state) == null
                : "a remembered state that says the same thing is not a step back";
        assert history.size() == 0 : "and it must not be left on the stack";

        history.remember(state);
        state.setWrapText(false);
        TemplateConfig previous = history.undo(state);
        assert previous != null && previous.isWrapText()
                : "undo must hand back the state from before the change";
        assert history.undo(state) == null : "the stack must be empty once it has been walked";

        UndoHistory small = new UndoHistory(2);
        for (int i = 0; i < 5; i++) small.remember(new TemplateConfig("state " + i));
        assert small.size() == 2 : "the history must not grow past its limit, it holds "
                + small.size();

        System.out.println("[PASS] undo: Ctrl+Z restores rules, headers, colors and wrap, "
                + "and a menu that changed nothing is not a step");
    }

    // ------------------------------------------------------------------ view helpers

    /**
     * Paints a component that has no window into an image.
     *
     * <p>Twice. The layout is walked by hand because a container with no native peer skips
     * validation entirely, so every child would stay at zero size and the image would be blank;
     * and the second pass is what the redaction rectangles need, since they are computed from
     * positions the first pass is what establishes.
     */
    private static BufferedImage paint(Component component, int width, int height) {
        component.setSize(width, height);
        if (component instanceof Container container) layoutTree(container);

        render(component, width, height);
        return render(component, width, height);
    }

    private static BufferedImage render(Component component, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Theme.tokens().bgApp);
            g.fillRect(0, 0, width, height);
            component.paint(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container inner) layoutTree(inner);
        }
    }

    /**
     * Where a document range sits in the painted image, as the pane itself would compute it.
     *
     * <p>The right edge comes from {@code modelToView2D(end)}, the offset one past the range, and
     * not from the last character's own box. That box is a caret box, zero pixels wide, so
     * {@code x + width} on it is the last character's LEFT edge. Measuring the sample region that
     * way is what let a redaction one character short pass this suite.
     */
    private static Rectangle rectFor(ViewFixture fixture, String needle) {
        int start = fixture.text().indexOf(needle);
        assert start >= 0 : "\"" + needle + "\" must be on screen";
        int end = start + needle.length();
        return boxOf(fixture, start, end);
    }

    /** The box of the final character of {@code needle}, exactly one character wide. */
    private static Rectangle lastCharCell(ViewFixture fixture, String needle) {
        int start = fixture.text().indexOf(needle);
        assert start >= 0 : "\"" + needle + "\" must be on screen";
        int end = start + needle.length();
        return boxOf(fixture, end - 1, end);
    }

    private static Rectangle boxOf(ViewFixture fixture, int start, int end) {
        Point origin = originIn(fixture.pane(), fixture.view());
        try {
            Rectangle2D first = fixture.pane().modelToView2D(start);
            Rectangle2D last = fixture.pane().modelToView2D(end - 1);
            Rectangle2D after = fixture.pane().modelToView2D(end);
            assert first != null && last != null && after != null
                    : "the text view must be laid out before its pixels are sampled";
            assert Math.abs(after.getY() - first.getY()) < 1.0
                    : "the sampled range must stay on one row, it ends on the next one";

            int x = (int) Math.floor(first.getX());
            int y = (int) Math.floor(first.getY());
            int right = (int) Math.ceil(after.getX());
            int bottom = (int) Math.ceil(last.getY() + last.getHeight());
            return new Rectangle(origin.x + x, origin.y + y,
                    Math.max(1, right - x), Math.max(1, bottom - y));
        } catch (BadLocationException e) {
            throw new AssertionError("the range came from the live document", e);
        }
    }

    private static Point originIn(Component child, Component root) {
        int x = 0;
        int y = 0;
        for (Component c = child; c != null && c != root; c = c.getParent()) {
            x += c.getX();
            y += c.getY();
        }
        return new Point(x, y);
    }

    /**
     * The y in {@code gutter} where the line at {@code offset} is drawn.
     *
     * <p>Converted rather than computed, because the strip and the text are two components in two
     * viewports and a test that does its own arithmetic can be calibrated to the same mistake the
     * code makes. Converted, the two have to agree by construction.
     */
    private static int gutterY(JTextPane pane, Component gutter, int offset) throws Exception {
        return javax.swing.SwingUtilities.convertPoint(pane, 0, rowTop(pane, offset), gutter).y;
    }

    private static int rowTop(JTextPane pane, int offset) throws Exception {
        Rectangle2D r = pane.modelToView2D(offset);
        assert r != null : "offset " + offset + " must be laid out";
        return (int) Math.round(r.getY());
    }

    /** Pixels in {@code rect} that are not exactly {@code color}, clamped to the image. */
    private static int countOtherThan(BufferedImage image, Rectangle rect, Color color) {
        int wanted = color.getRGB() & 0xFFFFFF;
        int count = 0;
        for (int y = Math.max(0, rect.y); y < Math.min(image.getHeight(), rect.y + rect.height); y++) {
            for (int x = Math.max(0, rect.x); x < Math.min(image.getWidth(), rect.x + rect.width); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != wanted) count++;
            }
        }
        return count;
    }

    private static Rectangle inset(Rectangle rect, int by) {
        return new Rectangle(rect.x + by, rect.y + by,
                Math.max(1, rect.width - 2 * by), Math.max(1, rect.height - 2 * by));
    }

    /** True when every channel is within {@code tolerance} of the colour. */
    /**
     * The style picker of a redaction card.
     *
     * <p>Found by what it selects rather than by position: a card carries two segmented controls,
     * the scope one and this, and a test that took the first would pass while reading the wrong
     * control.
     */
    @SuppressWarnings("unchecked")
    private static SegmentedControl<String> styleOf(RuleCard card) {
        for (Component c : descendants(card)) {
            if (c instanceof SegmentedControl<?> seg && seg.getSelected() instanceof String) {
                return (SegmentedControl<String>) seg;
            }
        }
        throw new AssertionError("a redaction card must carry a style picker, it has none");
    }

    /** A rule-card listener that does nothing, for a card built only to be read. */
    private static RuleCard.Listener quietListener() {
        return new RuleCard.Listener() {
            @Override public void changed() { }
            @Override public void deleted() { }
        };
    }

    private static boolean close(int rgb, Color color, int tolerance) {
        return Math.abs(((rgb >> 16) & 0xFF) - color.getRed()) <= tolerance
                && Math.abs(((rgb >> 8) & 0xFF) - color.getGreen()) <= tolerance
                && Math.abs((rgb & 0xFF) - color.getBlue()) <= tolerance;
    }

    /** The colour a token is drawn in, after the template's overrides. */
    private static Color paletteColor(TokenType type) {
        return (Theme.isDark() ? SyntaxPalette.DARK : SyntaxPalette.LIGHT).color(type);
    }

    /** How far the highlight band has to sit from the card colour to be worth marking anything. */
    private static final int WASH_MIN_DISTANCE = 70;

    /**
     * The fraction of its original contrast a stroke may keep and still be unreadable.
     *
     * <p>Measured against the control, which keeps the threshold from drifting with the palette:
     * a quarter of the contrast, with the noise of the same order laid over it, is a smudge. The
     * blur itself is tuned to sit well under this, not on it.
     */
    private static final double READABLE_FRACTION = 0.25;

    /**
     * How far above the noise a blurred stroke may still sit.
     *
     * <p>The floor itself, not a multiple of it. A stroke that rises above the noise around it is
     * a shape a reader can follow, and an earlier blur that passed the fraction test above still
     * left every character of a 50-character value legible, so the floor is asserted at one to
     * one rather than at some comfortable multiple.
     */
    private static final double NOISE_FLOOR = 1.0;

    /** The largest difference between the channels of a colour and those of another. */
    private static int channelDistance(int rgb, Color color) {
        return Math.max(Math.abs(((rgb >> 16) & 0xFF) - color.getRed()),
                Math.max(Math.abs(((rgb >> 8) & 0xFF) - color.getGreen()),
                        Math.abs((rgb & 0xFF) - color.getBlue())));
    }

    private static String documentText(JTextPane pane) {
        StyledDocument doc = pane.getStyledDocument();
        try {
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            throw new AssertionError("the document must be readable", e);
        }
    }

    /** First JTextPane anywhere under {@code root}, or null. */
    private static JTextPane findPane(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextPane pane) return pane;
            if (c instanceof Container inner) {
                JTextPane found = findPane(inner);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The hints the text is painted with, so measuring and painting agree. */
    private static void applyHints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static File write(BufferedImage image, String name) throws Exception {
        if (!OUT.isDirectory() && !OUT.mkdirs()) {
            throw new IllegalStateException("cannot create " + OUT.getAbsolutePath());
        }
        File file = new File(OUT, name);
        ImageIO.write(image, "png", file);
        assert file.isFile() && file.length() > 0 : "wrote " + file;
        return file;
    }

    /** Fraction of pixels whose ARGB values differ between two same-sized images. */
    private static double differingPixels(BufferedImage a, BufferedImage b) {
        int width = Math.min(a.getWidth(), b.getWidth());
        int height = Math.min(a.getHeight(), b.getHeight());
        long different = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) different++;
            }
        }
        return (double) different / ((long) width * height);
    }

    private static String pct(double fraction) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", fraction * 100);
    }

    private static void close(AutoCloseable handle) {
        try {
            handle.close();
        } catch (Exception e) {
            throw new AssertionError("closing a listener handle must not throw", e);
        }
    }
}
