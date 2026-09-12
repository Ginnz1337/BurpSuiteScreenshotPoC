package burp.screenshot.ui.components;

import burp.screenshot.design.Icons;
import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Collapsible titled block for the inspector.
 *
 * <p>The header is a plain {@link JComponent} rather than a {@code JButton}: a button would
 * inherit Swing's pressed/focus painting, which fights the flat look.
 */
public class AccordionSection extends JPanel {

    private final String title;
    private final String hint;
    private final JPanel content = new JPanel();
    private final Header header;
    private boolean expanded;

    public AccordionSection(String title, String hint, boolean expanded) {
        super(new BorderLayout());
        this.title = title;
        this.hint = hint;
        this.expanded = expanded;

        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);

        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, Tokens.MD, Tokens.MD, Tokens.MD));
        content.setVisible(expanded);

        header = new Header();
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    public AccordionSection(String title, boolean expanded) {
        this(title, null, expanded);
    }

    /** Body panel laid out top to bottom; add rows here. */
    public JPanel body() { return content; }

    public boolean isExpanded() { return expanded; }

    public void setExpanded(boolean value) {
        if (expanded == value) return;
        expanded = value;
        content.setVisible(value);
        header.repaint();
        revalidate();
        repaint();
    }

    /** Adds a row, then a spacer so rows keep the same rhythm. */
    public AccordionSection addRow(JComponent row) {
        Fields.capHeight(row);
        content.add(row);
        content.add(javax.swing.Box.createVerticalStrut(Tokens.SM));
        return this;
    }

    public AccordionSection addLabel(String text) {
        JLabel l = Fields.capHeight(Fields.secondary(text));
        content.add(l);
        content.add(javax.swing.Box.createVerticalStrut(Tokens.XS));
        return this;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private final class Header extends JComponent {

        private boolean hover;

        Header() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(10, 32));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) { setExpanded(!expanded); }
            });
        }

        @Override
        public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, 32); }

        @Override
        protected void paintComponent(Graphics g) {
            Tokens t = Theme.tokens();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            if (hover) {
                g2.setColor(t.bgHover);
                g2.fillRoundRect(0, 0, w, h, Tokens.R_SM, Tokens.R_SM);
            }

            Color fg = hover ? t.textPrimary : t.textSecondary;
            var chevron = expanded ? Icons.chevronDown(fg, 12) : Icons.chevronRight(fg, 12);
            chevron.paintIcon(this, g2, Tokens.MD, (h - 12) / 2);

            g2.setFont(Theme.tokens().uiBold);
            g2.setColor(fg);
            var fm = g2.getFontMetrics();
            int baseline = (h - fm.getHeight()) / 2 + fm.getAscent();
            int textX = Tokens.MD + 12 + Tokens.SM;
            g2.drawString(title, textX, baseline);

            if (hint != null && !hint.isBlank()) {
                g2.setFont(Theme.tokens().uiSmall);
                g2.setColor(t.textMuted);
                var hfm = g2.getFontMetrics();
                int hx = textX + fm.stringWidth(title) + Tokens.SM;
                if (hx + hfm.stringWidth(hint) < w - Tokens.MD) {
                    g2.drawString(hint, hx, (h - hfm.getHeight()) / 2 + hfm.getAscent());
                }
            }

            g2.setColor(t.border);
            g2.fillRect(0, h - 1, w, 1);

            g2.dispose();
        }
    }

    /** Right-aligned caption for the trailing edge of a row. */
    public static JLabel caption(String text) {
        JLabel l = Fields.muted(text);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        return l;
    }
}
