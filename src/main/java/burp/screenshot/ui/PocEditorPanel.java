package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.ClipboardHelper;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.model.TemplateConfig;
import burp.screenshot.ui.components.Buttons;
import burp.screenshot.ui.components.Fields;
import burp.screenshot.ui.components.SlimScrollBarUI;
import burp.screenshot.ui.components.Toast;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;

/**
 * The PoC view Burp shows as a tab beside the request editor.
 *
 * <p>It draws the same card the studio draws, so a screenshot can be taken from Repeater or
 * Logger without leaving them. Nothing here edits the request: the tab shows what Burp is
 * about to send and never writes back, which is why the host editor reports
 * {@code isModified()} as false.
 *
 * <p>Shortcuts are bound to this panel rather than to Burp's root pane. A
 * {@code WHEN_IN_FOCUSED_WINDOW} binding on the root pane would fire while the user is typing
 * in Burp's own message editor, taking Ctrl+S with it.
 */
public class PocEditorPanel extends JPanel {

    private final PreviewPanel preview = new PreviewPanel();
    private final JLabel statsLabel;
    private final TemplateConfig config;
    private final Consumer<HttpExchangeData> openStudio;

    private HttpExchangeData data;
    private AutoCloseable themeHandle;

    public PocEditorPanel(TemplateConfig config, Consumer<HttpExchangeData> openStudio) {
        super(new BorderLayout());
        this.config = config;
        this.openStudio = openStudio;
        setOpaque(true);

        statsLabel = Fields.muted("Waiting for a request");

        JScrollPane scroll = new JScrollPane(preview);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getHorizontalScrollBar().setUnitIncrement(24);
        SlimScrollBarUI.install(scroll);

        // The preview already reports its size and zoom; the header just shows what it says.
        preview.setStatusListener(statsLabel::setText);

        add(buildHeader(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        installShortcuts();
    }

    private JComponent buildHeader() {
        JPanel bar = new JPanel(new BorderLayout(Tokens.MD, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.tokens().border),
                BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD)));

        JLabel title = Fields.primary("PoC screenshot");
        title.setFont(Theme.tokens().title);
        title.setIcon(Icons.camera(null, 16));
        title.setIconTextGap(Tokens.SM);

        JPanel left = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, Tokens.SM, 0));
        left.setOpaque(false);
        left.add(title);
        left.add(statsLabel);

        JButton fit = Buttons.iconOnly(Icons.fit(null, 14), "Fit to the tab (Ctrl+0)");
        JButton open = Buttons.secondary("Open studio", Icons.code(null, 14));
        JButton save = Buttons.secondary("Save PNG", Icons.save(null, 14));
        JButton copy = Buttons.primary("Copy image", Icons.copy(null, 14));

        fit.addActionListener(e -> preview.fitToWindow());
        open.setToolTipText("Open the full studio on this exchange");
        open.addActionListener(e -> {
            if (data != null && openStudio != null) openStudio.accept(data);
        });
        save.setToolTipText("Write the rendered card to a PNG file (Ctrl+S)");
        save.addActionListener(e -> saveImage());
        copy.setToolTipText("Copy the rendered card to the clipboard (Ctrl+Shift+C)");
        copy.addActionListener(e -> copyImage());

        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, Tokens.SM, 0));
        right.setOpaque(false);
        right.add(fit);
        right.add(open);
        right.add(save);
        right.add(copy);

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ------------------------------------------------------------------ data

    /** Pushes the request Burp is showing. Called again on every selection change. */
    public void setData(HttpExchangeData exchange) {
        this.data = exchange;
        if (exchange == null) statsLabel.setText("Waiting for a request");
        preview.setData(exchange, config);
    }

    public PreviewPanel getPreview() { return preview; }

    /**
     * The tab's ground, resolved on every call.
     *
     * <p>The tab is embedded in Burp's own window, so the alternative is Burp's panel colour,
     * which the card's tokens were not picked against.
     */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }

    // ------------------------------------------------------------------ actions

    private void copyImage() {
        BufferedImage image = preview.imageForExport();
        if (image == null) {
            Toast.error(getRootPane(), "Nothing to copy yet");
            return;
        }
        try {
            ClipboardHelper.copyImage(image);
            Toast.success(getRootPane(), "Image copied to the clipboard");
        } catch (Exception ex) {
            Toast.error(getRootPane(), "Could not copy the image: " + ex.getMessage());
        }
    }

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
            Toast.success(getRootPane(), "Saved " + target.getName());
        } catch (Exception ex) {
            Toast.error(getRootPane(), "Could not save the image: " + ex.getMessage());
        }
    }

    private String defaultFileName() {
        String host = "target";
        String url = data != null ? data.getUrl() : null;
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

    /**
     * Only {@link JComponent#WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}.
     *
     * <p>The panel lives inside Burp's own window, so any wider scope would take these keys
     * from Burp whenever the Repeater tab happened to be open.
     */
    private void installShortcuts() {
        int scope = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, menu), scope, this::saveImage);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu | InputEvent.SHIFT_DOWN_MASK),
                scope, this::copyImage);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_0, menu), scope, preview::fitToWindow);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, menu), scope, () -> preview.zoomBy(1.25));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, menu), scope, () -> preview.zoomBy(1.25));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, menu), scope, () -> preview.zoomBy(1 / 1.25));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), scope, preview::refreshNow);
    }

    private void bind(KeyStroke key, int scope, Runnable action) {
        registerKeyboardAction(e -> action.run(), key, scope);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (themeHandle == null) {
            themeHandle = Theme.addListener(() -> SwingUtilities.invokeLater(() -> {
                preview.refresh();
                repaint();
            }));
        }
    }

    @Override
    public void removeNotify() {
        if (themeHandle != null) {
            try {
                themeHandle.close();
            } catch (Exception ignored) {
                // Already closed.
            }
            themeHandle = null;
        }
        super.removeNotify();
    }
}
