package burp.screenshot;

import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.screenshot.design.SyntaxPalette;
import burp.screenshot.design.Theme;
import burp.screenshot.design.TokenType;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.CardChrome;
import burp.screenshot.engine.RenderContext;
import burp.screenshot.engine.ScreenshotRenderer;
import burp.screenshot.engine.SectionPainter;
import burp.screenshot.engine.SyntaxHighlighter;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.engine.TextProcessor;
import burp.screenshot.engine.TokenWrap;
import burp.screenshot.model.EditedExchange;
import burp.screenshot.model.HighlightRule;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.RedactionRule;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.InspectorPanel;
import burp.screenshot.ui.PreviewPanel;
import burp.screenshot.ui.StudioFrame;
import burp.screenshot.ui.StudioPanel;
import burp.screenshot.ui.SuiteTabPanel;
import burp.screenshot.ui.TextStylePanel;
import burp.screenshot.ui.components.PromptDialog;
import burp.screenshot.ui.components.Toast;

import javax.imageio.ImageIO;
import javax.swing.JRootPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless checks over the renderer and the panels.
 *
 * <p>Run with {@code -ea}: every check below is an {@code assert}, which the JVM silently skips
 * without it. Both build scripts pass the flag.
 *
 * <p>The images written to {@code build/} are the artefact a human is meant to inspect. The
 * assertions cover what a machine can decide: that the two themes differ, that a doubled scale
 * doubles the geometry, and that the advance of a line is summed from each token's own font.
 */
public class ScreenshotVerificationTest {

    private static final File OUT = new File("build");

    /**
     * Runs the checks and then exits, always.
     *
     * <p>The exit is not decoration. Touching the event thread, which the edit checks do,
     * starts a real {@code EventQueue} in an otherwise display-less JVM, and the AWT shutdown
     * thread then keeps the process alive indefinitely. Without an explicit exit the build
     * would hang after a green run, and would hang just as hard after a failed one, since an
     * {@code AssertionError} leaving {@code main} does not stop those threads either.
     */
    public static void main(String[] args) {
        try {
            runChecks();
            System.out.println("==================================================");
            System.out.println(" ALL CHECKS PASSED");
            System.out.println("==================================================");
            System.exit(0);
        } catch (Throwable failure) {
            System.out.println("==================================================");
            System.out.println(" VERIFICATION FAILED");
            failure.printStackTrace(System.out);
            System.out.println("==================================================");
            System.exit(1);
        }
    }

    private static void runChecks() throws Exception {
        System.out.println(">>> Screenshot PoC verification");

        HttpExchangeData data = HttpExchangeData.createSampleData();
        assert data.getUrl() != null : "sample URL";
        assert data.getRawRequest() != null : "sample request";
        assert data.getRawResponse() != null : "sample response";
        System.out.println("[PASS] sample exchange");

        TemplateManager templates = new TemplateManager();
        TemplateConfig config = templates.getTemplate("Bug Bounty PoC");
        assert config != null : "the Bug Bounty PoC template must exist";
        System.out.println("[PASS] templates: " + templates.getTemplateNames());

        TextProcessor.ProcessedHttp request = TextProcessor.processRequest(data.getRawRequest(), config);
        TextProcessor.ProcessedHttp response = TextProcessor.processResponse(data.getRawResponse(), config);
        assert request.lines.size() > 1 : "the request must keep more than one line";
        assert response.lines.size() > 1 : "the response must keep more than one line";
        System.out.println("[PASS] text processing: " + request.lines.size() + " request lines, "
                + response.lines.size() + " response lines");

        checkAdvancesFollowEachTokenFont(data, config);

        BufferedImage dark = render(data, config, 2.0, true);
        BufferedImage light = render(data, config, 2.0, false);
        write(dark, "test_poc_dark.png");
        write(light, "test_poc_light.png");
        System.out.println("[PASS] rendered " + dark.getWidth() + "x" + dark.getHeight()
                + " dark and light");

        assert dark.getWidth() == light.getWidth() && dark.getHeight() == light.getHeight()
                : "the two themes must produce the same geometry";
        double different = differingPixels(dark, light);
        assert different > 0.30
                : "a theme switch must repaint the card, only " + pct(different) + " differs";
        System.out.println("[PASS] themes differ on " + pct(different) + " of pixels");

        checkScaleConsistency(data, config);
        checkThemeListenerRemoval();
        checkConfigRepair();

        checkTokenSeparation();
        writePaletteSheet(true);
        writePaletteSheet(false);

        checkUrlWrap(config);

        checkPanelsConstruct(data);
        checkPreviewGrip(data);
        checkTextEditing(data);
        checkPocTabScope();
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

        for (ToolType other : new ToolType[]{ToolType.PROXY, ToolType.INTRUDER, ToolType.SCANNER,
                ToolType.TARGET, ToolType.SUITE, ToolType.SEQUENCER, ToolType.COMPARER}) {
            assert !BurpExtender.showsPocTab(tool(other)) : other + " must not get the tab";
        }
        assert !BurpExtender.showsPocTab(null) : "a missing tool source must not get the tab";
        System.out.println("[PASS] PoC tab scope: Repeater and Logger only");
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

    // ------------------------------------------------------------------ checks

    /**
     * The advance across a line must be summed from the font each token is drawn with.
     *
     * <p>This is the defect that moved every highlight rectangle on a line containing a bold
     * token: the painter charged the regular width for a bold run, so the rectangles and the
     * text disagreed by a few pixels per bold token, and the error accumulated to the end of
     * the line. The request line is used because the method is bold and everything after it
     * is not, which is exactly the shape that drifts.
     */
    private static void checkAdvancesFollowEachTokenFont(HttpExchangeData data, TemplateConfig config) {
        RenderContext ctx = context(data, config);

        String line = "GET /api/v1/users?id=1337&role=admin HTTP/1.1";
        List<SyntaxHighlighter.Token> tokens =
                SyntaxHighlighter.tokenize(line, SyntaxHighlighter.LineKind.REQUEST_LINE);
        assert !tokens.isEmpty() : "a request line must tokenize";

        int characters = 0;
        int perTokenFont = 0;
        int oneFontForAll = 0;
        boolean sawBold = false;
        for (SyntaxHighlighter.Token token : tokens) {
            characters += token.text.length();
            sawBold |= SyntaxPalette.isBold(token.type);
            perTokenFont += ctx.metricsFor(token.type).stringWidth(token.text);
            oneFontForAll += ctx.fmCode.stringWidth(token.text);
        }

        assert sawBold : "the sample line must contain a bold token, or this check has no teeth";

        int actual = SectionPainter.widthAt(ctx, tokens, characters);
        assert actual == perTokenFont
                : "line advance must use each token's own font: expected " + perTokenFont
                + " but the painter reports " + actual;

        // The two sums agree exactly when the active family gives bold and regular the same
        // advance, which every monospaced font does. In that case charging one font for the
        // whole line is arithmetically identical and no test can tell the difference; the
        // check above only has teeth once the code font becomes proportional. Recorded here
        // so nobody reads a pass as stronger evidence than it is.
        if (perTokenFont == oneFontForAll) {
            System.out.println("[PASS] advance follows each token's font (" + actual
                    + "px, " + burp.screenshot.design.Tokens.MONO_FAMILY
                    + " gives bold the same advance, so per-token accounting is not observable)");
        } else {
            System.out.println("[PASS] advance follows each token's font (" + actual
                    + "px, a single-font sum would be " + oneFontForAll + "px)");
        }
    }

    /**
     * The scale factor must be applied exactly once.
     *
     * <p>The canvas is device pixels, so the whole image doubles while the units the layout is
     * computed in do not. If the metrics also carried the scale, the text would be laid out at
     * double width inside an already doubled canvas and run off the card. This checks the
     * canvas doubles and the layout units stay put.
     *
     * <p>It does not check that the measuring pass and the painting pass agree about rendering
     * hints, which is the other way drift appears; that was verified by rendering and
     * inspecting the images rather than by an assertion.
     */
    private static void checkScaleConsistency(HttpExchangeData data, TemplateConfig config) {
        BufferedImage one = render(data, config, 1.0, true);
        BufferedImage two = render(data, config, 2.0, true);

        // A one-pixel slack per dimension absorbs the odd line height rounding.
        assert Math.abs(two.getWidth() - one.getWidth() * 2) <= 2
                : "the 2x canvas must be twice as wide: " + two.getWidth()
                + " vs " + (one.getWidth() * 2);
        assert Math.abs(two.getHeight() - one.getHeight() * 2) <= 2
                : "the 2x canvas must be twice as tall: " + two.getHeight()
                + " vs " + (one.getHeight() * 2);

        RenderContext ctx1 = context(data, config);
        RenderContext ctx2 = context(data, config);

        String sample = "GET /api/v1/users?id=1337&role=admin HTTP/1.1";
        assert ctx1.fmCode.stringWidth(sample) == ctx2.fmCode.stringWidth(sample)
                : "layout units must not carry the scale: " + ctx2.fmCode.stringWidth(sample)
                + " vs " + ctx1.fmCode.stringWidth(sample);
        assert ctx1.lineHeight == ctx2.lineHeight
                : "line height must be the same at both scales";

        List<SyntaxHighlighter.Token> tokens =
                SyntaxHighlighter.tokenize(sample, SyntaxHighlighter.LineKind.REQUEST_LINE);
        int characters = 0;
        for (SyntaxHighlighter.Token token : tokens) characters += token.text.length();
        assert SectionPainter.widthAt(ctx1, tokens, characters)
                == SectionPainter.widthAt(ctx2, tokens, characters)
                : "the advance of a line must not depend on the scale factor";

        System.out.println("[PASS] scale consistency: 1x " + one.getWidth() + "x"
                + one.getHeight() + ", 2x " + two.getWidth() + "x" + two.getHeight()
                + ", layout units held");
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
     * throws in the inspector's for loop, a null {@code layoutMode} throws in the renderer's
     * comparison, and a null {@code name} throws inside the template combo box.
     */
    private static void checkConfigRepair() {
        TemplateConfig broken = new TemplateConfig();
        broken.setName(null);
        broken.setLayoutMode(null);
        broken.setHeaderScope(null);
        broken.setContentWidth("");
        broken.setHighlights(null);
        broken.setRedactions(Arrays.asList((RedactionRule) null));
        broken.setScaleFactor(0);
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("METHOD", null);
        colors.put("TARGET", "#1a5fb4");
        broken.setSyntaxColors(colors);

        TemplateConfig repaired = TemplateManager.normalize(broken);
        assert repaired == broken : "normalize repairs the config in place";
        assert "Default".equals(repaired.getName()) : "a blank name must fall back to Default";
        assert repaired.getLayoutMode() != null : "a null layout mode must fall back";
        assert repaired.getHeaderScope() != null : "a null scope must fall back";
        assert "Medium (1000px)".equals(repaired.getContentWidth()) : "a blank width must fall back";
        assert repaired.getHighlights().isEmpty() : "a null highlight list must become empty";
        assert repaired.getRedactions().isEmpty() : "a null entry in the list must be dropped";
        assert repaired.getScaleFactor() == 2.0 : "a zero scale must fall back to 2x";
        assert !repaired.getSyntaxColors().containsKey("METHOD") : "a null colour must be dropped";
        assert "#1a5fb4".equals(repaired.getSyntaxColors().get("TARGET")) : "a real colour must survive";

        TemplateConfig rules = new TemplateConfig();
        rules.getHighlights().add(new HighlightRule(null, true, null, "not-a-colour"));
        rules.getRedactions().add(new RedactionRule(null, true, null, null, -3));
        TemplateManager.normalize(rules);

        HighlightRule h = rules.getHighlights().get(0);
        assert "".equals(h.getPattern()) && h.getTarget() != null && Tokens.hex(h.getColorHex()) != null
                : "a highlight with a null pattern and a broken colour must become usable";
        RedactionRule r = rules.getRedactions().get(0);
        assert "".equals(r.getPattern()) && r.getTarget() != null
                && r.getMode() != null && r.getCaptureGroup() == 0
                : "a redaction with nulls must become usable";

        // copy() reads the lists directly, so a config that survived normalize must also survive
        // a save and reload cycle.
        TemplateConfig copy = rules.copy();
        assert copy.getHighlights().size() == 1 && copy.getRedactions().size() == 1
                : "a repaired config must round-trip through copy()";
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

            // Header, URL and body must not collapse into one hue.
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

    /**
     * A long URL must wrap inside the card instead of painting over itself.
     *
     * <p>The old single-line path placed the tail from the right edge without subtracting the
     * ellipsis or the head, and drew with no clip, so on a long URL the three runs landed on
     * top of each other. The address now wraps to a few lines, and only the last line is
     * shortened.
     */
    private static void checkUrlWrap(TemplateConfig config) throws Exception {
        HttpExchangeData data = HttpExchangeData.createSampleData();
        String longUrl = "https://demo.example.com/api/v1/report/export"
                + "?from=2026-01-01&to=2026-09-12&format=csv&include_metadata=true"
                + "&filter_status=all&sort=created_at&order=desc&page=1&per_page=500";
        data.setUrl(longUrl);

        RenderContext ctx = context(data, config);
        CardChrome.UrlLayout layout = CardChrome.urlLayout(ctx, 1000);

        assert layout.lines().size() > 1
                : "a " + longUrl.length() + " character URL must wrap, got " + layout.lines().size()
                + " line(s)";
        assert layout.lines().size() <= CardChrome.MAX_URL_LINES
                : "wrapped to " + layout.lines().size() + " lines, over the "
                + CardChrome.MAX_URL_LINES + " line cap";
        assert layout.height() > CardChrome.URL_BAR_HEIGHT
                : "a wrapped URL must make the bar taller than " + CardChrome.URL_BAR_HEIGHT;

        // Every line but the last must fit; the last may overflow because it is shortened.
        for (int i = 0; i < layout.lines().size() - 1; i++) {
            int w = TokenWrap.width(ctx, layout.lines().get(i));
            assert w <= layout.innerWidth()
                    : "line " + i + " is " + w + "px wide, over the " + layout.innerWidth() + "px box";
        }

        // And it must still render: a wrap that throws at paint time would pass everything above.
        BufferedImage image = render(data, config, 1.0, true);
        write(image, "test_url_wrap_dark.png");
        write(render(data, config, 1.0, false), "test_url_wrap_light.png");
        System.out.println("[PASS] URL wrap: " + layout.lines().size() + " lines, bar "
                + layout.height() + "px, " + longUrl.length() + " character URL");

        // Past the cap the last line is shortened rather than dropped or overrun.
        StringBuilder huge = new StringBuilder("https://demo.example.com/");
        while (huge.length() < 600) huge.append("segment/");
        huge.append("end?id=1");
        data.setUrl(huge.toString());
        CardChrome.UrlLayout capped = CardChrome.urlLayout(context(data, config), 1000);
        assert capped.lines().size() == CardChrome.MAX_URL_LINES
                : "a 600 character URL must fill all " + CardChrome.MAX_URL_LINES + " lines, got "
                + capped.lines().size();
        int lastWidth = TokenWrap.width(context(data, config),
                capped.lines().get(capped.lines().size() - 1));
        assert lastWidth > capped.innerWidth()
                : "the capped last line must overflow so it gets an ellipsis, but it is "
                + lastWidth + "px inside a " + capped.innerWidth() + "px box";

        write(render(data, config, 1.0, true), "test_url_capped_dark.png");
        System.out.println("[PASS] URL cap: " + CardChrome.MAX_URL_LINES + " lines, last line "
                + lastWidth + "px over a " + capped.innerWidth() + "px box, so it is shortened");
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
     * Where it disagrees with Repeater, the user corrects it in the inspector, which is why the
     * palette is editable in the app rather than compiled in.
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
        RenderContext.applyHints(g);
        g.scale(2, 2);

        g.setColor(t.bgCard);
        g.fillRect(0, 0, width, height);

        g.setFont(t.uiSmallBold);
        g.setColor(t.textPrimary);
        g.drawString("Bảng màu cú pháp: nền " + (dark ? "tối" : "sáng"), pad, pad + 14);

        g.setFont(t.uiSmall);
        g.setColor(t.textMuted);
        g.drawString("Đối chiếu với ảnh Burp Repeater. Chỉnh trong Cấu hình > Màu cú pháp.", pad, pad + 34);

        int y = pad + 56;
        for (TokenType type : TokenType.values()) {
            String sample = SAMPLES.get(type);
            assert sample != null : "every token needs a sample line, missing: " + type;

            Font font = SyntaxPalette.isBold(type) ? t.codeBold : t.code;
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

    /** Every panel and the window must build without a display. */
    private static void checkPanelsConstruct(HttpExchangeData data) throws Exception {
        TemplateManager templates = new TemplateManager();
        TemplateConfig config = templates.getTemplate("Default");

        StudioPanel studio = new StudioPanel(templates, data, true);
        assert studio.getData() == data : "the studio must keep the exchange it was given";
        studio.getInspector().setConfig(config);

        InspectorPanel inspector = studio.getInspector();
        assert inspector.getConfig() != null : "the inspector must hold a live config";

        SuiteTabPanel tab = new SuiteTabPanel(templates);
        tab.setExchangeData(data);
        assert tab.getStudio().getData() == data : "the tab must take the pushed exchange";

        // Toast is a no-op without a root pane, which is the case in a headless run.
        Toast.showFor(studio, "headless");
        JRootPane none = null;
        Toast.success(none, "ignored");

        // A modal dialog cannot be shown without a display. Reporting "cancelled" is what keeps
        // the suite runnable on a build machine; a dialog that threw here would fail every build.
        assert PromptDialog.show(null, "title", "label", "value") == null
                : "the prompt must report cancelled when there is no display";
        System.out.println("[PASS] panels constructed");

        checkMergedWorkspace(studio, data);

        try {
            StudioFrame frame = new StudioFrame(null, templates, data);
            assert frame.getTitle() != null && !frame.getTitle().isEmpty() : "window title";
            frame.dispose();
            System.out.println("[PASS] studio window");
        } catch (java.awt.HeadlessException headless) {
            System.out.println("[SKIP] no display; the studio window was not created");
        }
    }

    /**
     * The image and the text must share one workspace, and an edit must reach the card.
     *
     * <p>Both halves read the same {@link EditedExchange}, so the observable that matters is
     * that typing into the text pane changes what the preview is being rendered from while
     * Burp's own bytes stay as they were.
     */
    private static void checkMergedWorkspace(StudioPanel studio, HttpExchangeData data) throws Exception {
        javax.swing.JSplitPane workspace = findSplit(studio, javax.swing.JSplitPane.VERTICAL_SPLIT);
        assert workspace != null : "the image and the text must share one vertical split";
        assert workspace.getDividerSize() >= 4
                : "the workspace divider must be thick enough to grab, got "
                + workspace.getDividerSize();

        assert findChild(workspace.getTopComponent(), PreviewPanel.class) != null
                : "the image must be the top half";
        java.awt.Component bottom = workspace.getBottomComponent();
        assert findChild(bottom, TextStylePanel.class) != null : "the text must be the bottom half";

        // The inspector keeps its own horizontal split beside the workspace.
        assert findSplit(studio, javax.swing.JSplitPane.HORIZONTAL_SPLIT) != null
                : "the inspector split must survive the merge";

        TextStylePanel text = (TextStylePanel) findChild(bottom, TextStylePanel.class);
        JTextPane pane = findPane(text);
        assert pane != null && pane.isEditable()
                : "the merged text pane must be editable, that is the point of the merge";

        assert !studio.isEdited() : "a freshly opened studio is not edited";

        String edited = "GET /merged HTTP/1.1\nHost: demo.example.com\n\n";
        SwingUtilities.invokeAndWait(() -> pane.setText(edited));
        settle(400);

        assert studio.isEdited() : "typing in the merged text pane must mark the studio edited";
        assert studio.getEffectiveData().getRawRequest().contains("/merged")
                : "the card must render the edited text, got: "
                + studio.getEffectiveData().getRawRequest();
        assert data.getRawRequest().contains("/api/v1/user/profile")
                : "Burp's own bytes must not be overwritten by an edit";

        // A second, untouched studio, painted to a file. The structure above can be right while
        // the layout is wrong, and the only way to see that is to look at it. Both themes are
        // painted: a panel that resolves its ground from the wrong token only shows up in one.
        StudioPanel fresh = new StudioPanel(new TemplateManager(), data, true);
        File shot = writeComponent(fresh, 1400, 900, "test_studio_merged.png");
        assert shot.length() > 0 : "the studio must paint";
        checkNoUnthemedGround(shot, true, cardBounds(fresh));

        Theme.Mode previous = Theme.getMode();
        Theme.setMode(Theme.Mode.LIGHT);
        StudioPanel light = new StudioPanel(new TemplateManager(), data, true);
        File lightShot = writeComponent(light, 1400, 900, "test_studio_merged_light.png");
        assert lightShot.length() > 0 : "the studio must paint in light theme";
        checkNoUnthemedGround(lightShot, false, cardBounds(light));
        Theme.setMode(previous);

        System.out.println("[PASS] merged workspace: image over text, edits reach the card");
        System.out.println("       laid out at 1400x900 into " + shot.getName()
                + " and " + lightShot.getName());
    }

    /**
     * Every panel in the studio has to paint a token, not whatever the look and feel supplies.
     *
     * <p>Burp's own panel colour happens to be close to the tokens, so a panel that never
     * overrides {@code getBackground()} looks right in Burp and only shows up here, as the
     * Metal default. This is the check that caught the toolbar, the status bar, the text
     * viewport and the inspector all painting {@code #eeeeee} in a dark theme.
     *
     * <p>The rendered card is excluded, because it is an image rather than a Swing surface:
     * its glyph antialiasing runs through every grey, the look and feel's included, and it is
     * covered by its own checks.
     *
     * @param dark which theme the render was painted in, for the failure message
     * @param card the card's bounds, in the render's coordinates
     */
    private static void checkNoUnthemedGround(File shot, boolean dark, java.awt.Rectangle card)
            throws Exception {
        java.awt.Color laf = javax.swing.UIManager.getColor("Panel.background");
        assert laf != null : "the default look and feel must define Panel.background";

        BufferedImage rendered = ImageIO.read(shot);
        assert rendered != null : "the studio render must be readable";

        // The bounding box and a few samples are reported rather than just the count, because
        // which band of the window is unthemed is what says which panel is at fault.
        int rgb = laf.getRGB() & 0xFFFFFF;
        int found = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        StringBuilder samples = new StringBuilder();
        for (int y = 0; y < rendered.getHeight(); y++) {
            for (int x = 0; x < rendered.getWidth(); x++) {
                if (card != null && card.contains(x, y)) continue;
                if ((rendered.getRGB(x, y) & 0xFFFFFF) != rgb) continue;
                found++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                if (found <= 6) samples.append(" (").append(x).append(",").append(y).append(")");
            }
        }
        assert found == 0 : "the " + (dark ? "dark" : "light") + " studio paints " + found
                + " px of the look and feel's panel colour (#"
                + Integer.toHexString(rgb) + ") at" + samples
                + " within x" + minX + ".." + maxX + " y" + minY + ".." + maxY
                + "; a panel is missing getBackground()";
    }

    /** Where the rendered card sits in the studio, in the render's coordinates. */
    private static java.awt.Rectangle cardBounds(java.awt.Container root) {
        java.awt.Component preview = findChild(root, PreviewPanel.class);
        if (preview == null) return null;

        int x = 0;
        int y = 0;
        for (java.awt.Component c = preview; c != null; c = c.getParent()) {
            x += c.getX();
            y += c.getY();
            if (c == root) break;
        }
        return new java.awt.Rectangle(x, y, preview.getWidth(), preview.getHeight());
    }

    /**
     * Paints a component that has no window into an image.
     *
     * <p>The layout is walked by hand rather than with {@code validate()}: a container with no
     * native peer skips validation entirely, so every child would stay at zero size and the
     * image would be blank. Walking parent before child gives each container its real bounds
     * before its children are laid out inside it.
     */
    private static File writeComponent(java.awt.Component component, int width, int height,
                                       String name) throws Exception {
        component.setSize(width, height);
        if (component instanceof java.awt.Container container) layoutTree(container);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Theme.tokens().bgApp);
            g.fillRect(0, 0, width, height);
            component.paint(g);
        } finally {
            g.dispose();
        }
        return write(image, name);
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) layoutTree(inner);
        }
    }

    /** First {@code JSplitPane} of the given orientation under {@code root}, or null. */
    private static javax.swing.JSplitPane findSplit(java.awt.Container root, int orientation) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof javax.swing.JSplitPane split && split.getOrientation() == orientation) {
                return split;
            }
            if (c instanceof java.awt.Container inner) {
                javax.swing.JSplitPane found = findSplit(inner, orientation);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** First child of the given type anywhere under {@code root}, or null. */
    private static java.awt.Component findChild(java.awt.Component root, Class<?> type) {
        if (root == null) return null;
        if (type.isInstance(root)) return root;
        if (!(root instanceof java.awt.Container container)) return null;
        for (java.awt.Component c : container.getComponents()) {
            java.awt.Component found = findChild(c, type);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The card edges must show a drag handle before the pointer arrives.
     *
     * <p>The dashed edge line only appears on hover, so it can never advertise that the edge is
     * draggable. This paints the preview offscreen and counts accent pixels on both edges: a
     * grip that stops being drawn would otherwise pass every other check in this suite.
     */
    private static void checkPreviewGrip(HttpExchangeData data) throws Exception {
        TemplateManager templates = new TemplateManager();
        TemplateConfig config = templates.getTemplate("Default");

        PreviewPanel preview = new PreviewPanel();
        preview.setData(data, config);
        preview.setSize(1200, 500);

        BufferedImage shot = new BufferedImage(1200, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = shot.createGraphics();
        preview.paint(g);
        g.dispose();
        write(shot, "test_preview_grip_dark.png");

        int left = preview.getImageBounds().x;
        int right = left + preview.getImageBounds().width;
        assert right < 1200 : "the card must fit the test canvas, right edge at " + right;

        int onLeft = accentPixels(shot, left - 3, 6);
        int onRight = accentPixels(shot, right - 3, 6);
        assert onLeft > 20 : "no drag grip on the left edge at x=" + left + ", only " + onLeft
                + " accent pixels";
        assert onRight > 20 : "no drag grip on the right edge at x=" + right + ", only " + onRight
                + " accent pixels";
        System.out.println("[PASS] preview grip: " + onLeft + " px left at x=" + left + ", "
                + onRight + " px right at x=" + right);
    }

    /**
     * Accent-coloured pixels in a narrow column, which is what a grip is made of.
     *
     * <p>The whole column is scanned rather than a band around the middle: the grip is centred
     * on the card, and the card is not centred in the panel at every zoom.
     */
    private static int accentPixels(BufferedImage image, int x0, int width) {
        Color accent = Theme.tokens().accent;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = Math.max(0, x0); x < Math.min(x0 + width, image.getWidth()); x++) {
                Color p = new Color(image.getRGB(x, y), true);
                // Red-dominant rather than an exact match: the grip is drawn with an alpha and
                // antialiased, so almost no pixel carries the accent value itself.
                if (p.getRed() - p.getGreen() > 40 && p.getRed() - p.getBlue() > 20
                        && Math.abs(p.getRed() - accent.getRed()) < 90) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Editing the text must restyle the edited line, reach the listener and be reversible.
     *
     * <p>The restyle is checked by token boundaries rather than by a hard-coded colour: a line
     * left with the plain attribute set is one colour from end to end, while a line that was
     * re-tokenized as a header carries at least two. That holds whichever theme is active and
     * whatever the template has overridden.
     */
    private static void checkTextEditing(HttpExchangeData data) throws Exception {
        TemplateManager templates = new TemplateManager();
        TemplateConfig config = templates.getTemplate("Default");

        TextStylePanel text = new TextStylePanel();
        text.updateData(data, config);

        final String[] captured = new String[1];
        text.setEditListener(edited -> captured[0] = edited);

        JTextPane pane = findPane(text);
        assert pane != null : "the text panel must hold a JTextPane";
        assert pane.isEditable() : "the text must be editable, that is the whole point";

        // Line 1 in the document, because the Host header is line 0 of the headers.
        String edited = "GET /a HTTP/1.1\nHost: demo.example.com\nX-Test: 1\n\nbody";
        SwingUtilities.invokeAndWait(() -> pane.setText(edited));
        settle(400);

        assert captured[0] != null : "an edit must reach the editor listener";
        assert captured[0].contains("X-Test: 1") : "the listener got: " + captured[0];
        assert text.isEdited() : "an edited document must report itself as edited";

        StyledDocument doc = pane.getStyledDocument();
        Element root = doc.getDefaultRootElement();
        assert root.getElementCount() >= 5 : "the edited document must keep its lines";

        Element headerLine = root.getElement(2);
        assert doc.getText(headerLine.getStartOffset(),
                headerLine.getEndOffset() - headerLine.getStartOffset()).startsWith("X-Test")
                : "line 2 of the edited document must be the header";

        Set<Color> colours = new HashSet<>();
        for (int i = headerLine.getStartOffset(); i < headerLine.getEndOffset(); i++) {
            AttributeSet attrs = doc.getCharacterElement(i).getAttributes();
            colours.add(StyleConstants.getForeground(attrs));
        }
        assert colours.size() >= 2 : "the edited header line was not re-tokenized, it has "
                + colours.size() + " colour(s)";

        assert StyleConstants.isBold(doc.getCharacterElement(0).getAttributes())
                : "the request method on line 0 must stay bold after an edit";

        text.resetEdits();
        settle(200);
        assert !text.isEdited() : "reset must put Burp's own bytes back";
        assert text.getEditedText().contains("demo.example.com")
                : "reset must restore the original request";
        // The template hides Accept by default, so a pristine document is the processed one.
        // Comparing against the raw message instead would call this text edited forever.
        assert !text.getEditedText().contains("Accept:")
                : "reset must restore the processed view, not the raw message";

        checkEditedExchange(data, edited);
        System.out.println("[PASS] text editing: restyled, reported and reversible");
    }

    /** The card fields must follow the edited start line, not the ones Burp reported. */
    private static void checkEditedExchange(HttpExchangeData data, String editedRequest) {
        EditedExchange edited = new EditedExchange(data);
        assert !edited.isModified() : "an untouched exchange is not modified";

        edited.setRequestText(editedRequest);
        assert edited.isModified() : "an edited request must report itself as modified";

        HttpExchangeData effective = edited.effective();
        assert "GET".equals(effective.getHttpMethod())
                : "method came back as " + effective.getHttpMethod();
        assert effective.getUrl().endsWith("/a")
                : "URL must be rebuilt from the Host header, got " + effective.getUrl();
        assert effective.getUrl().startsWith("https://")
                : "the original scheme must survive an edit, got " + effective.getUrl();
        assert data.getUrl().endsWith("/api/v1/user/profile?id=1337")
                : "the original exchange must not be touched, got " + data.getUrl();

        edited.setRequestText("POST /admin/delete?id=1 HTTP/2\nHost: demo.example.com\n");
        HttpExchangeData next = edited.effective();
        assert "POST".equals(next.getHttpMethod()) : "method came back as " + next.getHttpMethod();
        assert "HTTP/2".equals(next.getHttpVersion())
                : "version came back as " + next.getHttpVersion();
        assert next.getUrl().endsWith("/admin/delete?id=1") : "URL came back as " + next.getUrl();

        edited.setResponseText("HTTP/1.1 403 Forbidden\r\n\r\nno");
        HttpExchangeData withResponse = edited.effective();
        assert withResponse.getStatusCode() == 403
                : "status came back as " + withResponse.getStatusCode();
        assert "Forbidden".equals(withResponse.getStatusReason())
                : "reason came back as " + withResponse.getStatusReason();
        assert withResponse.getResponseSizeBytes() == "HTTP/1.1 403 Forbidden\r\n\r\nno".length()
                : "the response size must follow the edited body";

        edited.clear();
        assert !edited.isModified() : "clear must drop every edit";
        System.out.println("[PASS] edited exchange: method, version, URL and status follow the text");
    }

    /** First JTextPane anywhere under {@code root}, or null. */
    private static JTextPane findPane(java.awt.Container root) {
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof JTextPane pane) return pane;
            if (c instanceof java.awt.Container inner) {
                JTextPane found = findPane(inner);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Lets the event queue run for {@code ms}.
     *
     * <p>The panel restyles on a Swing timer, and a timer only fires while the EDT is free.
     * Sleeping on this thread is what gives it that time.
     */
    private static void settle(long ms) throws Exception {
        Thread.sleep(ms);
        SwingUtilities.invokeAndWait(() -> { });
    }

    // ------------------------------------------------------------------ helpers
    private static BufferedImage render(HttpExchangeData data, TemplateConfig config,
                                        double scale, boolean dark) {
        return ScreenshotRenderer.render(data, config, 1000, scale, dark);
    }

    /**
     * A context measured through a scratch surface carrying the painter's own hints.
     *
     * <p>The hints matter: measuring with the defaults gives different advances from the ones
     * the painting pass produces, which is the drift this suite exists to catch.
     */
    private static RenderContext context(HttpExchangeData data, TemplateConfig config) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        RenderContext.applyHints(g);
        try {
            return RenderContext.create(data, config, true, g);
        } finally {
            g.dispose();
        }
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
