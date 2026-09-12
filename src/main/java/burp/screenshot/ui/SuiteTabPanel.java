package burp.screenshot.ui;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;
import burp.screenshot.engine.TemplateManager;
import burp.screenshot.model.HttpExchangeData;
import burp.screenshot.ui.components.Fields;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.FlowLayout;

/**
 * The "Screenshot PoC" tab in Burp's suite tab strip.
 *
 * <p>It embeds the same {@link StudioPanel} as the standalone window, so the two cannot drift
 * apart. The one thing the tab adds is an honest empty state: on load it has no exchange, and
 * showing sample data without saying so invites the user to save a screenshot of traffic they
 * never sent.
 *
 * <p>Shortcuts are bound to this panel rather than to the window. The tab's root pane belongs
 * to Burp's main window, so a global binding would take Ctrl+S away from Repeater.
 */
public class SuiteTabPanel extends JPanel {

    private final StudioPanel studio;
    private final NoticeBar notice;

    public SuiteTabPanel(TemplateManager templateManager) {
        super(new BorderLayout());
        setOpaque(true);

        studio = new StudioPanel(templateManager, HttpExchangeData.createSampleData(), false);
        notice = new NoticeBar();

        add(notice, BorderLayout.NORTH);
        add(studio, BorderLayout.CENTER);

        showSampleDataNotice();
    }

    /**
     * Shows the exchange the user just selected.
     *
     * <p>Called by {@code BurpExtender} on every studio open. Until then the tab renders
     * sample traffic, and says so.
     */
    public void setExchangeData(HttpExchangeData data) {
        studio.setData(data);
        if (data == null) {
            showSampleDataNotice();
        } else {
            notice.setState(true, describe(data));
        }
    }

    public StudioPanel getStudio() { return studio; }

    /**
     * The tab's own ground, resolved on every call.
     *
     * <p>Without it the tab paints Burp's panel colour rather than one of the tokens, so the
     * strip above the studio would not match {@link NoticeBar}, which does use a token.
     */
    @Override
    public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }

    private void showSampleDataNotice() {
        notice.setState(false, "Right-click a request in Proxy, Repeater or Target, "
                + "then choose \"Open PoC Screenshot Studio\"");
    }

    private static String describe(HttpExchangeData data) {
        String method = data.getHttpMethod() != null ? data.getHttpMethod() : "";
        String url = data.getUrl() != null ? data.getUrl() : "";
        String text = (method + " " + url).trim();
        if (text.length() > 90) text = text.substring(0, 87) + "...";
        return text;
    }

    /**
     * One-line bar above the studio.
     *
     * <p>Carries the "Sample data" badge while the tab holds sample traffic. A badge is used
     * rather than a modal or a colour change, because the studio underneath stays fully
     * usable: building a template from the sample data is a legitimate thing to do.
     */
    private static final class NoticeBar extends JPanel {

        private final Badge badge = new Badge();
        private final JLabel message = Fields.muted("");

        NoticeBar() {
            super(new BorderLayout(Tokens.SM, 0));
            setOpaque(true);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.tokens().border),
                    BorderFactory.createEmptyBorder(Tokens.SM, Tokens.MD, Tokens.SM, Tokens.MD)));

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SM, 0));
            left.setOpaque(false);
            left.add(badge);
            left.add(message);

            add(left, BorderLayout.WEST);
        }

        /** @param real true once an actual exchange has been pushed in */
        void setState(boolean real, String text) {
            badge.setText(real ? "Live traffic" : "Sample data");
            badge.setReal(real);
            message.setText(text);
            revalidate();
            repaint();
        }

        @Override
        public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }
    }

    /** Small pill that says where the current data came from. */
    private static final class Badge extends JComponent {

        private String text = "";
        private boolean real;

        void setText(String value) {
            text = value == null ? "" : value;
            revalidate();
        }

        void setReal(boolean value) {
            real = value;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(Theme.tokens().uiSmall);
            return new Dimension(fm.stringWidth(text) + Tokens.MD * 2, 20);
        }

        @Override
        public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Tokens t = Theme.tokens();
            java.awt.Color accent = real ? t.success : t.warn;
            int w = getWidth();
            int h = getHeight();
            int arc = h;

            g2.setColor(Tokens.alpha(accent, real ? 46 : 38));
            g2.fillRoundRect(0, 0, w, h, arc, arc);
            g2.setColor(Tokens.alpha(accent, 150));
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

            g2.setFont(t.uiSmall);
            g2.setColor(accent);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, (w - fm.stringWidth(text)) / 2,
                    (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }
}
