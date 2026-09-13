package burp.screenshot;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;
import burp.screenshot.design.Theme;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.PocEditorPanel;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Font;

/**
 * Extension entry point: one ScreenshotPoC tab in the message editors, one shared theme source.
 *
 * <p>The tab is registered for requests and for responses. A response is where the interesting
 * headers are, so a view that could only show requests would be missing the half of the
 * exchange a PoC screenshot is usually about.
 */
public class BurpExtender implements BurpExtension {

    /**
     * What the tab is called in the editor.
     *
     * <p>Spelled out rather than "PoC": the editor strip already carries Pretty, Raw, Hex and
     * Unicode, and a two-letter tab beside them does not say which extension put it there. The
     * name has to match {@code extension().setName} so the tab and the loaded-extensions list
     * are recognisably the same thing.
     */
    public static final String TAB_CAPTION = "ScreenshotPoC";

    private MontoyaApi api;
    private TemplateManager templateManager;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        this.templateManager = new TemplateManager();

        api.extension().setName("Screenshot PoC");

        installThemeSource();

        api.userInterface().registerHttpRequestEditorProvider(new PocRequestEditorProvider());
        api.userInterface().registerHttpResponseEditorProvider(new PocResponseEditorProvider());

        api.logging().logToOutput("Screenshot PoC loaded.");
        api.logging().logToOutput("  A \"" + TAB_CAPTION + "\" tab is available in Repeater, "
                + "Logger and Proxy, on the request and on the response.");
    }

    // ------------------------------------------------------------------ providers

    /**
     * Adds the PoC tab to Repeater and Logger, and nowhere else.
     *
     * <p>Burp calls this for every place it could show a message editor, including Proxy
     * intercept and Intruder, so anything not in scope has to come back as {@code null}
     * rather than as a disabled editor. A fresh editor is returned each time: one instance
     * shared between two Repeater tabs would show whichever message was pushed last.
     */
    private final class PocRequestEditorProvider implements HttpRequestEditorProvider {
        @Override
        public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext context) {
            if (!showsPocTab(context.toolSource())) return null;
            return new PocRequestEditor();
        }
    }

    private final class PocResponseEditorProvider implements HttpResponseEditorProvider {
        @Override
        public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext context) {
            if (!showsPocTab(context.toolSource())) return null;
            return new PocResponseEditor();
        }
    }

    /**
     * Whether the ScreenshotPoC tab belongs in this tool.
     *
     * <p>Repeater, Logger and Proxy. Proxy is where HTTP history lives, and a message read there
     * is the one a report is most often written from.
     *
     * <p>The decision is a static function of the tool source so it can be checked without a
     * live Burp: getting it wrong shows the tab where it was not asked for, which the user would
     * only notice by looking.
     *
     * <p>{@code PROXY} is the whole of the Proxy tool. Montoya reports the tool and nothing
     * finer, so the tab also appears on Proxy intercept and on the WebSockets history viewer.
     * There is no way to ask for HTTP history alone.
     */
    static boolean showsPocTab(ToolSource source) {
        return source != null
                && source.isFromTool(ToolType.REPEATER, ToolType.LOGGER, ToolType.PROXY);
    }

    // ------------------------------------------------------------------ editors

    /** The request half of the tab. Read-only by design, so {@code isModified} is always false. */
    private final class PocRequestEditor implements ExtensionProvidedHttpRequestEditor {

        private final PocEditorPanel panel = new PocEditorPanel(true, template(), BurpExtender.this::save);

        private HttpRequestResponse current;

        @Override
        public HttpRequest getRequest() {
            return current != null ? current.request() : null;
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.current = requestResponse;
            if (requestResponse == null) return;
            push(panel, requestResponse);
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            return true;
        }

        @Override
        public String caption() {
            return TAB_CAPTION;
        }

        @Override
        public Component uiComponent() {
            return panel;
        }

        @Override
        public Selection selectedData() {
            return selectionOf(panel.getSelectedText());
        }

        @Override
        public boolean isModified() {
            return false;
        }
    }

    /** The response half of the tab. */
    private final class PocResponseEditor implements ExtensionProvidedHttpResponseEditor {

        private final PocEditorPanel panel = new PocEditorPanel(false, template(), BurpExtender.this::save);

        private HttpRequestResponse current;

        @Override
        public HttpResponse getResponse() {
            return current != null ? current.response() : null;
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.current = requestResponse;
            if (requestResponse == null) return;
            push(panel, requestResponse);
        }

        /**
         * Hidden until there is a response to show.
         *
         * <p>A response editor with no response would draw an empty pane, which reads as a
         * broken tab rather than as a request that has not been sent yet.
         */
        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            return requestResponse != null && requestResponse.response() != null;
        }

        @Override
        public String caption() {
            return TAB_CAPTION;
        }

        @Override
        public Component uiComponent() {
            return panel;
        }

        @Override
        public Selection selectedData() {
            return selectionOf(panel.getSelectedText());
        }

        @Override
        public boolean isModified() {
            return false;
        }
    }

    /**
     * Hands the message to the panel, unread.
     *
     * <p>The message is not converted here. Burp announces a message to the tab of every editor
     * it holds, and in Proxy history that is once per row the user clicks through, so converting
     * first would render both sides of every message the user scrolls past to text nobody asked
     * for. The panel is given the supplier and decides when to call it: never while the tab is
     * off screen, and once when it is looked at.
     *
     * <p>Still posted to the event thread. The panel may draw, and a styled document must not be
     * rebuilt while Burp is holding a lock.
     */
    private static void push(PocEditorPanel panel, HttpRequestResponse requestResponse) {
        SwingUtilities.invokeLater(
                () -> panel.setSource(requestResponse, () -> convertExchangeData(requestResponse)));
    }

    /**
     * The selection as text, which is what the tab can report honestly.
     *
     * <p>No offsets: the tab hides header lines, so a range here would not name the same
     * characters in the message Burp holds. Reporting a range that lands elsewhere is worse
     * than reporting none.
     */
    private static Selection selectionOf(String text) {
        return Selection.selection(ByteArray.byteArray(text == null ? "" : text));
    }

    // ------------------------------------------------------------------ state

    private TemplateConfig template() {
        return templateManager.getTemplate("Default");
    }

    private void save() {
        templateManager.saveTemplate(templateManager.getTemplate("Default"));
    }

    /**
     * Wires Burp's theme and fonts into the design system.
     *
     * <p>Montoya reports the current theme but fires no event when it changes, so
     * {@link Theme} polls. Everything the extension paints resolves its colors from the
     * tokens on each repaint, so no restart is needed after a theme switch.
     */
    private void installThemeSource() {
        Theme.setSource(new Theme.Source() {
            @Override
            public boolean isDark() {
                return api.userInterface().currentTheme() == burp.api.montoya.ui.Theme.DARK;
            }

            @Override
            public Font editorFont() {
                return api.userInterface().currentEditorFont();
            }

            @Override
            public Font displayFont() {
                return api.userInterface().currentDisplayFont();
            }
        });
    }

    /**
     * The two raw messages, which is all the view draws.
     *
     * <p>The URL, the timing and the response size are no longer collected: the card that
     * showed them is gone, and a field nothing reads only invites a later reader to trust it.
     */
    private static HttpExchangeData convertExchangeData(HttpRequestResponse reqRes) {
        HttpExchangeData data = new HttpExchangeData();

        if (reqRes.request() != null) {
            data.setRawRequest(reqRes.request().toString());
        }
        if (reqRes.hasResponse() && reqRes.response() != null) {
            data.setRawResponse(reqRes.response().toString());
        }
        return data;
    }
}
