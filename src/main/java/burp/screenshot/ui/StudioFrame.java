package burp.screenshot.ui;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.model.HttpExchangeData;

import javax.swing.JFrame;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

/**
 * The standalone studio window, opened from the context menu.
 *
 * <p>Every open creates a new frame, so anything the frame holds must be released on close.
 * The theme listener lives in {@link StudioPanel} and is released from {@code windowClosed}
 * as well as {@code removeNotify}, because {@code dispose} is not guaranteed to walk the
 * child hierarchy.
 */
public class StudioFrame extends JFrame {

    private final StudioPanel studio;

    public StudioFrame(Frame parent, TemplateManager templateManager, HttpExchangeData data) {
        super("PoC Screenshot Studio");

        studio = new StudioPanel(templateManager, data, true);
        setContentPane(studio);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1420, 900);
        setMinimumSize(new Dimension(1060, 680));
        setLocationRelativeTo(parent);
        applyIcon();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                studio.dispose();
            }
        });
    }

    /** The panel that owns the state, so the host can push a new exchange into an open frame. */
    public StudioPanel getStudio() { return studio; }

    /**
     * Window icon drawn from the same vector set as the UI.
     *
     * <p>Painted into an ARGB image rather than shipped as a resource: the extension is a
     * single JAR loaded by Burp, and a missing resource path would be a silent failure on
     * a user's machine only.
     */
    private void applyIcon() {
        try {
            BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = icon.createGraphics();
            Icons.camera(Theme.tokens().accent, 28).paintIcon(this, g, 2, 2);
            g.dispose();
            setIconImage(icon);
        } catch (Exception ignored) {
            // An icon is decoration; failing to set it must not stop the studio opening.
        }
    }
}
