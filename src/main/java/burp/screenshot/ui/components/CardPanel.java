package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.RenderingHints;

/** Rounded container with a hairline border, drawn from theme tokens at paint time. */
public class CardPanel extends JPanel {

    private final int radius;
    private boolean bordered = true;

    public CardPanel(LayoutManager layout) {
        this(layout, Tokens.R_MD);
    }

    public CardPanel(LayoutManager layout, int radius) {
        super(layout);
        this.radius = radius;
        setOpaque(false);
    }

    public CardPanel bordered(boolean bordered) {
        this.bordered = bordered;
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Tokens t = Theme.tokens();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(t.bgCard);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);

        if (bordered) {
            g2.setColor(t.border);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
        }

        g2.dispose();
        super.paintComponent(g);
    }

    /** Vertical hairline used between toolbar groups. */
    public static JPanel separator() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.tokens().border);
                g.fillRect(getWidth() / 2, 3, 1, Math.max(0, getHeight() - 6));
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new java.awt.Dimension(9, 24));
        return p;
    }

    /** Horizontal hairline used between stacked blocks. */
    public static JPanel hairline() {
        JPanel p = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.tokens().border);
                g.fillRect(0, getHeight() / 2, getWidth(), 1);
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new java.awt.Dimension(1, 1));
        p.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 1));
        return p;
    }

    static Color transparent() { return new Color(0, 0, 0, 0); }
}
