package burp.screenshot;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.TimingData;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.engine.ClipboardHelper;
import burp.screenshot.engine.ScreenshotRenderer;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.PocEditorPanel;
import burp.screenshot.ui.StudioFrame;
import burp.screenshot.ui.SuiteTabPanel;
import burp.screenshot.ui.components.Toast;

import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Extension entry point: one context-menu provider, one suite tab, one shared theme source.
 */
public class BurpExtender implements BurpExtension {

    private MontoyaApi api;
    private TemplateManager templateManager;
    private SuiteTabPanel suiteTab;

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        this.templateManager = new TemplateManager();

        api.extension().setName("PoC Screenshot Studio (Caido-style)");

        installThemeSource();

        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<Component> items = new ArrayList<>();

                HttpRequestResponse reqRes = null;
                if (event.messageEditorRequestResponse().isPresent()) {
                    reqRes = event.messageEditorRequestResponse().get().requestResponse();
                } else if (!event.selectedRequestResponses().isEmpty()) {
                    reqRes = event.selectedRequestResponses().get(0);
                }

                if (reqRes != null) {
                    final HttpRequestResponse target = reqRes;
                    // A null colour makes each icon inherit its menu item's foreground, so it
                    // is legible in both of Burp's themes without querying the theme here.
                    JMenuItem openStudio = new JMenuItem("Open PoC Screenshot Studio",
                            Icons.camera(null, 16));
                    openStudio.setAccelerator(ctrlShift(KeyEvent.VK_S));
                    openStudio.setMnemonic(KeyEvent.VK_O);
                    openStudio.addActionListener(e ->
                            SwingUtilities.invokeLater(() -> openStudio(target)));
                    items.add(openStudio);

                    JMenuItem quickCopy = new JMenuItem("Quick Copy PoC Screenshot (Default)",
                            Icons.copy(null, 16));
                    quickCopy.setAccelerator(ctrlShift(KeyEvent.VK_C));
                    quickCopy.setMnemonic(KeyEvent.VK_Q);
                    quickCopy.addActionListener(e ->
                            SwingUtilities.invokeLater(() -> quickCopy(target)));
                    items.add(quickCopy);
                }

                return items;
            }
        });

        suiteTab = new SuiteTabPanel(templateManager);
        api.userInterface().registerSuiteTab("Screenshot PoC", suiteTab);

        api.userInterface().registerHttpRequestEditorProvider(new PocRequestEditorProvider());

        api.logging().logToOutput("PoC Screenshot Studio loaded.");
        api.logging().logToOutput("  Right-click a request: Open PoC Screenshot Studio, "
                + "or Quick Copy PoC Screenshot.");
        api.logging().logToOutput("  A \"PoC\" tab is available in Repeater and Logger.");
    }

    /**
     * Adds the PoC tab to Repeater and Logger, and nowhere else.
     *
     * <p>Burp calls this for every place it could show a request editor, including Proxy
     * intercept and Intruder, so anything not in scope has to come back as {@code null}
     * rather than as a disabled editor. A fresh editor is returned each time: one instance
     * shared between two Repeater tabs would show whichever request was pushed last.
     */
    private final class PocRequestEditorProvider implements HttpRequestEditorProvider {
        @Override
        public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext context) {
            if (!showsPocTab(context.toolSource())) return null;
            return new PocRequestEditor();
        }
    }

    /**
     * Whether the PoC tab belongs in this tool.
     *
     * <p>Repeater and Logger only. The decision is a static function of the tool source so it
     * can be checked without a live Burp: getting it wrong shows the tab in Proxy intercept or
     * Intruder, which the user would only notice by looking.
     */
    static boolean showsPocTab(ToolSource source) {
        return source != null && source.isFromTool(ToolType.REPEATER, ToolType.LOGGER);
    }

    /** The PoC tab itself. Read-only by design, so {@code isModified} is always false. */
    private final class PocRequestEditor implements ExtensionProvidedHttpRequestEditor {

        private final PocEditorPanel panel =
                new PocEditorPanel(templateManager.getTemplate("Default"), BurpExtender.this::openStudio);

        private HttpRequestResponse current;

        @Override
        public HttpRequest getRequest() {
            return current != null ? current.request() : null;
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            this.current = requestResponse;
            if (requestResponse == null) return;

            // Converted here, on whatever thread Burp called from, then handed to the panel on
            // the event thread: a card render must not run while Burp is holding a lock.
            HttpExchangeData data = convertExchangeData(requestResponse);
            SwingUtilities.invokeLater(() -> panel.setData(data));
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            return true;
        }

        @Override
        public String caption() {
            return "PoC";
        }

        @Override
        public Component uiComponent() {
            return panel;
        }

        @Override
        public Selection selectedData() {
            // The tab shows a rendered image, so there is no message text to report back.
            return Selection.selection(ByteArray.byteArray(""));
        }

        @Override
        public boolean isModified() {
            return false;
        }
    }

    /** Opens the full studio on an exchange that is already converted. */
    private void openStudio(HttpExchangeData data) {
        try {
            if (suiteTab != null) suiteTab.setExchangeData(data);
            StudioFrame frame = new StudioFrame(api.userInterface().swingUtils().suiteFrame(),
                    templateManager, data);
            frame.setVisible(true);
        } catch (Exception ex) {
            api.logging().logToError("Could not open the PoC studio: " + ex);
            Toast.error(suiteRootPane(), "Could not open the studio: " + ex.getMessage());
        }
    }

    /**
     * Wires Burp's theme and fonts into the design system.
     *
     * <p>Montoya reports the current theme but fires no event when it changes, so
     * {@link Theme} polls. Everything the extension paints resolves its colours from the
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

    private void openStudio(HttpRequestResponse reqRes) {
        // The tab is the only view that survives the window, so it is kept in step by
        // openStudio(HttpExchangeData).
        openStudio(convertExchangeData(reqRes));
    }

    private void quickCopy(HttpRequestResponse reqRes) {
        try {
            HttpExchangeData data = convertExchangeData(reqRes);
            TemplateConfig config = templateManager.getTemplate("Default");
            BufferedImage image = ScreenshotRenderer.render(data, config, 1000, 2.0, Theme.isDark());
            ClipboardHelper.copyImage(image);
            api.logging().logToOutput("PoC screenshot copied to the clipboard (2x).");
            Toast.success(suiteRootPane(), "PoC image copied to the clipboard (2x)");
        } catch (Exception ex) {
            api.logging().logToError("Quick copy failed: " + ex);
            Toast.error(suiteRootPane(), "Could not copy the image: " + ex.getMessage());
        }
    }

    private static javax.swing.KeyStroke ctrlShift(int keyCode) {
        return javax.swing.KeyStroke.getKeyStroke(keyCode,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    }

    /**
     * Root pane of Burp's main window.
     *
     * <p>Montoya types {@code suiteFrame()} as {@code java.awt.Frame}, so the cast is checked
     * rather than assumed. Returns null when there is no Swing root pane, which makes
     * {@link Toast} a no-op; the outcome is always logged as well, so nothing is lost silently.
     */
    private javax.swing.JRootPane suiteRootPane() {
        java.awt.Frame frame = api.userInterface().swingUtils().suiteFrame();
        return frame instanceof javax.swing.RootPaneContainer
                ? ((javax.swing.RootPaneContainer) frame).getRootPane()
                : null;
    }

    private HttpExchangeData convertExchangeData(HttpRequestResponse reqRes) {
        HttpExchangeData data = new HttpExchangeData();

        HttpRequest req = reqRes.request();
        if (req != null) {
            data.setUrl(req.url() != null ? req.url() : "");
            data.setHttpMethod(req.method() != null ? req.method() : "GET");
            data.setHttpVersion(req.httpVersion() != null ? req.httpVersion() : "HTTP/1.1");
            data.setRawRequest(req.toString());
        }

        if (reqRes.hasResponse()) {
            HttpResponse res = reqRes.response();
            if (res != null) {
                data.setStatusCode(res.statusCode());
                data.setStatusReason(res.reasonPhrase() != null ? res.reasonPhrase() : "");
                data.setRawResponse(res.toString());
                data.setResponseSizeBytes(res.toByteArray().length());
            }
        }

        if (reqRes.timingData().isPresent()) {
            TimingData td = reqRes.timingData().get();
            try {
                data.setResponseDurationMs(td.timeBetweenRequestSentAndEndOfResponse().toMillis());
                data.setTimestampFormatted(td.timeRequestSent()
                        .format(DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a")));
            } catch (Exception ignored) {
                // Partial timing data is common; fall through to the wall clock.
            }
        }
        if (data.getTimestampFormatted() == null || data.getTimestampFormatted().isEmpty()) {
            data.setTimestampFormatted(new SimpleDateFormat("M/d/yyyy, h:mm:ss a").format(new Date()));
        }

        return data;
    }
}
