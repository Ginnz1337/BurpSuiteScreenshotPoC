package burp.screenshot.ui.components;

import burp.screenshot.design.Theme;
import burp.screenshot.design.Tokens;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.KeyEvent;

/**
 * Modal prompt that paints itself with the extension's own tokens.
 *
 * <p>{@code JOptionPane} would be shorter, but it renders with whatever look and feel Burp
 * installed: a grey Metal dialog in the middle of a dark studio. It also ignores the studio's
 * fonts, so the one dialog in the extension would be the one place the design does not reach.
 */
public final class PromptDialog {

    private PromptDialog() {}

    /**
     * Asks for a single line of text.
     *
     * @return the trimmed input, or null when the user cancels or enters nothing
     */
    public static String show(Component parent, String title, String label, String initial) {
        // Nothing can be shown without a display; the caller treats null as "cancelled".
        if (GraphicsEnvironment.isHeadless()) return null;

        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
        final String[] result = {null};

        Tokens t = Theme.tokens();

        JPanel content = new JPanel(new BorderLayout(0, Tokens.MD)) {
            @Override public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }
        };
        content.setOpaque(true);
        content.setBorder(BorderFactory.createEmptyBorder(Tokens.LG, Tokens.LG, Tokens.MD, Tokens.LG));

        JComponent labelView = Fields.secondary(label);
        content.add(labelView, BorderLayout.NORTH);

        final JTextField field = Fields.text(initial == null ? "" : initial);
        content.add(field, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SM, 0)) {
            @Override public java.awt.Color getBackground() { return Theme.tokens().bgPanel; }
        };
        buttons.setOpaque(true);

        javax.swing.JButton cancel = Buttons.secondary("Cancel", null);
        cancel.addActionListener(e -> dialog.dispose());
        javax.swing.JButton ok = Buttons.primary("Save", null);
        ok.addActionListener(e -> {
            result[0] = field.getText();
            dialog.dispose();
        });
        buttons.add(cancel);
        buttons.add(ok);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.setResizable(false);
        dialog.pack();
        Dimension size = dialog.getSize();
        dialog.setSize(Math.max(380, size.width), size.height);
        dialog.setLocationRelativeTo(owner);

        field.selectAll();
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        dialog.setVisible(true);

        String text = result[0];
        if (text == null) return null;
        text = text.trim();
        return text.isEmpty() ? null : text;
    }
}
