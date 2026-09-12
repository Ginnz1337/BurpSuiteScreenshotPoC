package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.ClipboardHelper;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.model.EditedExchange;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SegmentedControl;
import burp.screenshot.ui.components.SlimScrollBarUI;
import burp.screenshot.ui.components.Toast;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The whole studio UI, host-agnostic.
 *
 * <p>Both the standalone window and the Burp suite tab embed this same panel. Keeping one
 * implementation is the point: a tab that renders differently from the window would make
 * "what you see is what you save" untrue.
 *
 * <p>Keyboard shortcuts are the one thing the two hosts cannot share. Inside the suite tab the
 * root pane belongs to Burp's main window, and a {@code WHEN_IN_FOCUSED_WINDOW} binding on it
 * fires while the user is working in Repeater, stealing Ctrl+S from Burp. So the embedded host
 * binds against itself and the window host binds globally. Escape follows the same rule, since
 * in the tab it would otherwise mean "close Burp".
 */
public class StudioPanel extends JPanel {

    /** Inspector width, and the floor it may be dragged to. */
    private static final int INSPECTOR_WIDTH = 360;
    private static final int INSPECTOR_MIN_WIDTH = 320;

    /** Share of the height the image gets when the workspace opens, and its floor. */
    private static final double PREVIEW_SHARE = 0.62;
    private static final int PREVIEW_MIN_HEIGHT = 160;
    private static final int TEXT_MIN_HEIGHT = 120;

    private final TemplateManager templateManager;
    private final boolean globalShortcuts;

    private final PreviewPanel preview = new PreviewPanel();
    private final TextStylePanel textView = new TextStylePanel();
    private final InspectorPanel inspector;
    private final JLabel statusLabel;
    private final JLabel sizeLabel;
    private final JLabel zoomLabel;
    private final JButton zoomOut;
    private final JButton zoomIn;
    private final JButton zoomReset;
    private final JButton saveButton;
    private final JButton copyButton;

    private HttpExchangeData exchangeData;

    /**
     * The exchange plus whatever the user has typed into the text pane.
     *
     * <p>Both the preview and the text pane read through this, so an edit shows up in the card
     * without Burp's own bytes ever being overwritten.
     */
    private EditedExchange edited;

    private TemplateConfig config;

    private AutoCloseable themeHandle;
    private boolean disposed;

    public StudioPanel(TemplateManager templateManager, HttpExchangeData data, boolean globalShortcuts) {
        super(new BorderLayout());
        this.templateManager = templateManager;
        this.exchangeData = data;
        this.edited = new EditedExchange(data);
        this.globalShortcuts = globalShortcuts;
        this.config = templateManager.getTemplate("Default");

        setOpaque(true);

        inspector = new InspectorPanel(templateManager, config, (next, switched) -> {
            this.config = next;
            applyConfig(switched);
        });

        // Both directions of the merge: an edit re-renders the card, and Reset drops the edit
        // for whichever side is showing.
        textView.setEditListener(text -> {
            if (textView.getSide() == TextStylePanel.Side.REQUEST) edited.setRequestText(text);
            else edited.setResponseText(text);
            preview.setData(edited.effective(), config);
        });
        textView.setResetListener(() -> {
            if (textView.getSide() == TextStylePanel.Side.REQUEST) edited.clearRequest();
            else edited.clearResponse();
            preview.setData(edited.effective(), config);
        });

        statusLabel = Fields.secondary("Ready");
        sizeLabel = Fields.muted("");
        zoomLabel = Fields.secondary("100%");
        zoomLabel.setHorizontalAlignment(JLabel.CENTER);
        zoomLabel.setPreferredSize(new Dimension(46, 24));

        zoomOut = Buttons.iconOnly(Icons.zoomOut(null, 14), "Zoom out (Ctrl+-)");
        zoomIn = Buttons.iconOnly(Icons.zoomIn(null, 14), "Zoom in (Ctrl+=)");
        zoomReset = Buttons.iconOnly(Icons.fit(null, 14), "Fit to window (Ctrl+0)");
        // A null colour makes each icon follow its component's foreground, which a primary
        // button resolves to textInverse. Hard-coding white would be wrong in light theme.
        saveButton = Buttons.primary("Save PNG", Icons.save(null, 14));
        copyButton = Buttons.primary("Copy image", Icons.copy(null, 14));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCentre(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        wireActions();
        preview.setStatusListener(text -> {
            statusLabel.setText(text);
            sizeLabel.setText(text);
        });

        setData(data);
    }

    // ------------------------------------------------------------------ chrome

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(Tokens.MD, 0));
        // Not opaque, so the window ground shows through: the toolbar is separated from the
        // workspace by its own border, and painting a second ground here would only be a
        // chance for the two to disagree after a theme switch.
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.tokens().border),
                BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD)));

        JLabel title = Fields.primary("Screenshot Mode");
        title.setFont(Theme.tokens().title);
        title.setIcon(Icons.camera(null, 18));
        title.setIconTextGap(Tokens.SM);

        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, Tokens.MD, 0));
        left.setOpaque(false);
        left.add(title);

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, Tokens.SM, 0));
        right.setOpaque(false);
        right.add(themeControl());
        right.add(separator());
        right.add(zoomOut);
        right.add(zoomLabel);
        right.add(zoomIn);
        right.add(zoomReset);
        right.add(separator());
        right.add(saveButton);
        right.add(copyButton);
        right.add(copyTextButton());

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /**
     * Theme mode picker.
     *
     * <p>"Match Burp" is the default and the only option that tracks a live Burp theme change;
     * the other two exist so a screenshot can be taken in the opposite theme from the one Burp
     * is currently using.
     */
    private JComponent themeControl() {
        SegmentedControl<Theme.Mode> control = new SegmentedControl<>(
                List.of(Theme.Mode.AUTO, Theme.Mode.DARK, Theme.Mode.LIGHT),
                m -> {
                    switch (m) {
                        case DARK: return "Dark";
                        case LIGHT: return "Light";
                        default: return "Match Burp";
                    }
                },
                Theme.getMode(),
                Theme::setMode);
        control.setToolTipText("Theme of the rendered image. \"Match Burp\" follows Burp's own theme.");
        return control;
    }

    private JComponent separator() {
        JComponent line = new JComponent() {
            @Override protected void paintComponent(java.awt.Graphics g) {
                g.setColor(Theme.tokens().border);
                g.fillRect(getWidth() / 2, 3, 1, getHeight() - 6);
            }
        };
        line.setPreferredSize(new Dimension(Tokens.SM, 24));
        return line;
    }

    private JComponent buildCentre() {
        // One pixel, and a colour resolved at paint time: the default divider is a bevel that
        // reads as a groove against a flat background.
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildWorkspace(), inspector);
        split.setDividerSize(1);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        split.setOpaque(false);
        split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override
            public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override public void paint(java.awt.Graphics g) {
                        g.setColor(Theme.tokens().border);
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
            }
        });
        // After setUI: see buildWorkspace. The LAF border would stroke the inspector's edge in
        // the look and feel's panel colour.
        split.setBorder(null);

        inspector.setPreferredSize(new Dimension(INSPECTOR_WIDTH, 700));
        inspector.setMinimumSize(new Dimension(INSPECTOR_MIN_WIDTH, 400));

        // The divider is placed from the real width once the split has been laid out. A
        // fraction would drift below the inspector's minimum on a narrow window, and a fixed
        // pixel location set in the constructor would be measured against a width of zero.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean placed;

            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (placed || split.getWidth() < 300) return;
                placed = true;
                split.setDividerLocation(Math.max(INSPECTOR_MIN_WIDTH,
                        split.getWidth() - INSPECTOR_WIDTH));
            }
        });

        return split;
    }

    /**
     * The image above the editable text, one draggable divider between them.
     *
     * <p>Both halves read the same {@link EditedExchange}, so text can be copied or corrected
     * while the card it will be rendered into stays on screen. The divider is drawn thicker
     * here than the inspector's one pixel, because unlike that one it is meant to be found and
     * dragged.
     */
    private JComponent buildWorkspace() {
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setBorder(null);
        previewScroll.setOpaque(false);
        previewScroll.getViewport().setOpaque(false);
        previewScroll.getVerticalScrollBar().setUnitIncrement(24);
        previewScroll.getHorizontalScrollBar().setUnitIncrement(24);
        SlimScrollBarUI.install(previewScroll);

        JScrollPane textScroll = new JScrollPane(textView);
        textScroll.setBorder(null);
        textScroll.setOpaque(false);
        textScroll.getViewport().setOpaque(false);
        textScroll.getVerticalScrollBar().setUnitIncrement(16);

        previewScroll.setMinimumSize(new Dimension(200, PREVIEW_MIN_HEIGHT));
        textScroll.setMinimumSize(new Dimension(200, TEXT_MIN_HEIGHT));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewScroll, textScroll);
        split.setDividerSize(6);
        split.setResizeWeight(PREVIEW_SHARE);
        split.setContinuousLayout(true);
        split.setOpaque(false);
        split.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI() {
            @Override
            public javax.swing.plaf.basic.BasicSplitPaneDivider createDefaultDivider() {
                return new javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    @Override public void paint(java.awt.Graphics g) {
                        Tokens t = Theme.tokens();
                        g.setColor(t.bgPanel);
                        g.fillRect(0, 0, getWidth(), getHeight());

                        // A short bar in the middle, so the divider reads as a handle rather
                        // than as a gap between two panels.
                        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(Tokens.alpha(t.textMuted, 150));
                        int w = Math.min(48, Math.max(16, getWidth() / 6));
                        g2.fillRoundRect((getWidth() - w) / 2, getHeight() / 2 - 1, w, 3, 3, 3);
                        g2.dispose();
                    }
                };
            }
        });
        // After setUI, never before: installing the UI runs installDefaults, which puts the
        // look and feel's SplitPaneBorder back. That border strokes its lines in the child
        // components' own background colour, which is the look and feel's panel colour rather
        // than a token, so it shows as a light outline around both halves.
        split.setBorder(null);

        // Same reason as the inspector divider: set from the real height, because the
        // constructor runs at zero height and a fraction would sink below the text minimum.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean placed;

            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (placed || split.getHeight() < 200) return;
                placed = true;
                split.setDividerLocation(Math.max(PREVIEW_MIN_HEIGHT,
                        Math.min((int) (split.getHeight() * PREVIEW_SHARE),
                                split.getHeight() - TEXT_MIN_HEIGHT - split.getDividerSize())));
            }
        });

        return split;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(Tokens.MD, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.tokens().border),
                BorderFactory.createEmptyBorder(Tokens.XS, Tokens.MD, Tokens.XS, Tokens.MD)));
        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(sizeLabel, BorderLayout.EAST);
        return bar;
    }

    private JButton copyTextButton() {
        JButton b = Buttons.secondary("Text", Icons.code(null, 14));
        b.setToolTipText("Copy the request or the response as text");

        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Copy request", () -> copyText(rawRequest(), "request")));
        menu.add(item("Copy response", () -> copyText(rawResponse(), "response")));
        menu.addSeparator();
        menu.add(item("Copy edited request", () -> copyText(edited.effective().getRawRequest(),
                "edited request")));
        menu.add(item("Copy edited response", () -> copyText(edited.effective().getRawResponse(),
                "edited response")));
        menu.addSeparator();
        menu.add(item("Copy both", () -> copyText(rawRequest() + "\n\n" + rawResponse(),
                "request and response")));

        b.addActionListener(e -> menu.show(b, 0, b.getHeight()));
        return b;
    }

    private JMenuItem item(String label, Runnable action) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> action.run());
        return i;
    }

    // ------------------------------------------------------------------ wiring

    private void wireActions() {
        saveButton.setToolTipText("Save the image as PNG (Ctrl+S)");
        copyButton.setToolTipText("Copy the image to the clipboard (Ctrl+Shift+C)");

        saveButton.addActionListener(e -> saveImage());
        copyButton.addActionListener(e -> copyImage());
        zoomIn.addActionListener(e -> zoomBy(1.25));
        zoomOut.addActionListener(e -> zoomBy(1 / 1.25));
        zoomReset.addActionListener(e -> fitToWindow());
    }

    private void zoomBy(double factor) {
        preview.zoomBy(factor);
        updateZoomLabel();
    }

    private void fitToWindow() {
        preview.fitToWindow();
        updateZoomLabel();
    }

    private void updateZoomLabel() {
        zoomLabel.setText(Math.round(preview.getZoom() * 100) + "%");
    }

    /** Pushes the current config into both halves of the workspace. */
    private void applyConfig(boolean templateSwitched) {
        preview.setData(edited.effective(), config);
        textView.updateData(exchangeData, config);
        updateZoomLabel();
        if (templateSwitched) {
            statusLabel.setText("Loaded template " + config.getName());
        }
    }

    // ------------------------------------------------------------------ data

    /** Replaces the exchange on show; the studio always opens on the current selection. */
    public void setData(HttpExchangeData data) {
        this.exchangeData = data;
        this.edited = new EditedExchange(data);
        preview.setData(data, config);
        // The text pane always shows Burp's own message, so a fresh exchange starts unedited.
        textView.updateData(data, config);
        updateZoomLabel();
    }

    public HttpExchangeData getData() { return exchangeData; }

    /** The exchange as edited, which is what the preview and any export are built from. */
    public HttpExchangeData getEffectiveData() { return edited.effective(); }

    public boolean isEdited() { return edited.isModified(); }

    public InspectorPanel getInspector() { return inspector; }

    public PreviewPanel getPreview() { return preview; }

    /**
     * The window ground, resolved on every call.
     *
     * <p>Not {@code setBackground} in the constructor: Montoya fires no event on a theme
     * change, so a colour captured once would still be the old theme's after a switch. The
     * toolbar and the status bar are left unpainted so this is the only ground behind them.
     */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgApp; }

    // ------------------------------------------------------------------ actions

    private void saveImage() {
        BufferedImage image = preview.imageForExport();
        if (image == null) {
            Toast.error(getRootPane(), "Nothing to save yet");
            return;
        }

        Window owner = SwingUtilities.getWindowAncestor(this);
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save PoC image");
        chooser.setFileFilter(new FileNameExtensionFilter("PNG (*.png)", "png"));
        chooser.setSelectedFile(new File(defaultFileName()));

        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;

        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
            target = new File(target.getParentFile(), target.getName() + ".png");
        }

        try {
            ImageIO.write(image, "png", target);
            statusLabel.setText("Saved " + target.getName());
            Toast.success(getRootPane(), "Saved " + target.getName());
        } catch (Exception ex) {
            statusLabel.setText("Save failed: " + ex.getMessage());
            Toast.error(getRootPane(), "Could not save the image: " + ex.getMessage());
        }
    }

    private void copyImage() {
        BufferedImage image = preview.imageForExport();
        if (image == null) {
            Toast.error(getRootPane(), "Nothing to copy yet");
            return;
        }
        try {
            ClipboardHelper.copyImage(image);
            statusLabel.setText("Copied " + image.getWidth() + " x " + image.getHeight());
            Toast.success(getRootPane(), "Image copied to the clipboard");
        } catch (Exception ex) {
            statusLabel.setText("Copy failed: " + ex.getMessage());
            Toast.error(getRootPane(), "Could not copy the image: " + ex.getMessage());
        }
    }

    private void copyText(String text, String label) {
        if (text == null || text.isEmpty()) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            Toast.success(getRootPane(), "Copied the " + label);
        } catch (Exception ex) {
            Toast.error(getRootPane(), "Could not copy: " + ex.getMessage());
        }
    }

    private String rawRequest() {
        return exchangeData != null ? exchangeData.getRawRequest() : null;
    }

    private String rawResponse() {
        return exchangeData != null ? exchangeData.getRawResponse() : null;
    }

    private String defaultFileName() {
        String host = "target";
        String url = exchangeData != null ? exchangeData.getUrl() : null;
        if (url != null && !url.isEmpty()) {
            try {
                String parsed = new URI(url).getHost();
                if (parsed != null) host = parsed.replaceAll("[^a-zA-Z0-9.-]", "_");
            } catch (Exception ignored) {
                // A malformed URL is not worth failing a save over.
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        return "PoC_" + host + "_" + stamp + ".png";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeHandle == null) {
            // Every control resolves its colours per paint, but the layout still needs a
            // repaint to show them, and the preview needs a full re-render.
            themeHandle = Theme.addListener(() -> SwingUtilities.invokeLater(() -> {
                updateZoomLabel();
                repaint();
            }));
        }
        installShortcuts();
    }

    @Override
    public void removeNotify() {
        dispose();
        super.removeNotify();
    }

    /** Releases the theme listener. Idempotent, so a window close may also call it. */
    public void dispose() {
        if (disposed) return;
        disposed = true;
        if (themeHandle != null) {
            try {
                themeHandle.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            themeHandle = null;
        }
    }

    private void installShortcuts() {
        int scope = globalShortcuts
                ? JComponent.WHEN_IN_FOCUSED_WINDOW
                : JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, menu), scope, this::saveImage);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu | InputEvent.SHIFT_DOWN_MASK), scope, this::copyImage);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), scope, () -> preview.refreshNow());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_0, menu), scope, this::fitToWindow);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menu), scope, () -> zoomBy(1.25));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menu), scope, () -> zoomBy(1.25));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menu), scope, () -> zoomBy(1 / 1.25));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), scope, textView::focusSearch);

        if (globalShortcuts) {
            // Only in the window: in the suite tab Escape must not reach Burp.
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), scope, this::closeWindow);
        }
    }

    private void bind(KeyStroke key, int scope, Runnable action) {
        getRootPane().registerKeyboardAction(e -> action.run(), key, scope);
    }

    private void closeWindow() {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null) w.dispose();
    }
}
