package burp.screenshot.design;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * Vector icons painted with Java 2D.
 *
 * <p>Unicode emoji are deliberately avoided: Burp's bundled JRE on Windows has no emoji
 * font, so glyphs such as the camera or floppy disk render as empty boxes.
 *
 * <p>Passing {@code null} as the color makes an icon follow its component's foreground,
 * which lets a single icon instance re-theme on repaint.
 */
public final class Icons {

    private Icons() {}

    private abstract static class Base implements Icon {
        final Color color;
        final int size;

        Base(Color color, int size) {
            this.color = color;
            this.size = Math.max(8, size);
        }

        Color resolve(Component c) {
            return color != null ? color : (c != null ? c.getForeground() : Color.GRAY);
        }

        Graphics2D prepare(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            g2.setColor(resolve(c));
            return g2;
        }

        void stroke(Graphics2D g2, float width) {
            g2.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    // ------------------------------------------------------------------ shapes

    public static Icon camera(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                int s = size;
                Path2D body = new Path2D.Float();
                body.moveTo(1, s / 4);
                body.lineTo(s / 4, s / 4);
                body.lineTo(s / 3, 1);
                body.lineTo(s * 2 / 3, 1);
                body.lineTo(s * 3 / 4, s / 4);
                body.lineTo(s - 1, s / 4);
                body.lineTo(s - 1, s - 1);
                body.lineTo(1, s - 1);
                body.closePath();
                stroke(g2, Math.max(1.2f, s / 12f));
                g2.draw(body);
                g2.fillOval(s / 4, s * 5 / 12, s / 2, s / 2);
                g2.dispose();
            }
        };
    }

    public static Icon save(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                int s = size;
                stroke(g2, Math.max(1.2f, s / 12f));
                Path2D p = new Path2D.Float();
                p.moveTo(1, 1);
                p.lineTo(s - 4, 1);
                p.lineTo(s - 1, 4);
                p.lineTo(s - 1, s - 1);
                p.lineTo(1, s - 1);
                p.closePath();
                g2.draw(p);
                g2.drawLine(s / 3, 1, s / 3, s / 3);
                g2.drawLine(s * 2 / 3, 1, s * 2 / 3, s / 3);
                g2.drawRect(s / 4, s / 2, s / 2, s / 2 - 2);
                g2.dispose();
            }
        };
    }

    public static Icon copy(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                int s = size;
                stroke(g2, 1.4f);
                int w = s * 9 / 16;
                int h = s * 11 / 16;
                g2.drawRoundRect(s - w - 1, 1, w, h, 2, 2);
                g2.setColor(resolve(c));
                g2.fillRoundRect(1, s - h - 1, w, h, 2, 2);
                g2.setColor(Tokens.alpha(resolve(c), 200));
                g2.drawRoundRect(1, s - h - 1, w, h, 2, 2);
                g2.dispose();
            }
        };
    }

    public static Icon trash(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                int s = size;
                g2.fillRect(1, s / 4, s - 2, Math.max(2, s / 8));
                g2.fillRect(s / 3, s / 8, s / 3, Math.max(2, s / 8));
                stroke(g2, 1.4f);
                g2.drawRoundRect(s / 5, s * 3 / 8, s * 3 / 5, s * 7 / 12, 2, 2);
                g2.dispose();
            }
        };
    }

    public static Icon refresh(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                int s = size;
                stroke(g2, 1.6f);
                g2.drawArc(2, 2, s - 5, s - 5, 60, 260);
                int ax = s - 3;
                int ay = s / 2;
                g2.drawLine(ax, ay, ax - 4, ay - 2);
                g2.drawLine(ax, ay, ax - 2, ay + 4);
                g2.dispose();
            }
        };
    }

    public static Icon chevronDown(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.6f);
                int s = size;
                g2.drawLine(s / 4, s * 2 / 5, s / 2, s * 3 / 5);
                g2.drawLine(s / 2, s * 3 / 5, s * 3 / 4, s * 2 / 5);
                g2.dispose();
            }
        };
    }

    public static Icon chevronUp(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.6f);
                int s = size;
                g2.drawLine(s / 4, s * 3 / 5, s / 2, s * 2 / 5);
                g2.drawLine(s / 2, s * 2 / 5, s * 3 / 4, s * 3 / 5);
                g2.dispose();
            }
        };
    }

    public static Icon chevronRight(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.6f);
                int s = size;
                g2.drawLine(s * 2 / 5, s / 4, s * 3 / 5, s / 2);
                g2.drawLine(s * 3 / 5, s / 2, s * 2 / 5, s * 3 / 4);
                g2.dispose();
            }
        };
    }

    public static Icon plus(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.6f);
                int s = size;
                g2.drawLine(s / 2, s / 5, s / 2, s * 4 / 5);
                g2.drawLine(s / 5, s / 2, s * 4 / 5, s / 2);
                g2.dispose();
            }
        };
    }

    public static Icon close(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.6f);
                int s = size;
                int p = s / 4;
                g2.drawLine(p, p, s - p, s - p);
                g2.drawLine(s - p, p, p, s - p);
                g2.dispose();
            }
        };
    }

    public static Icon search(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.5f);
                int s = size;
                int d = s * 3 / 5;
                g2.drawOval(1, 1, d, d);
                g2.drawLine(1 + d * 3 / 4, 1 + d * 3 / 4, s - 2, s - 2);
                g2.dispose();
            }
        };
    }

    public static Icon zoomIn(Color color, int size) { return zoom(color, size, true); }
    public static Icon zoomOut(Color color, int size) { return zoom(color, size, false); }

    private static Icon zoom(Color color, int size, boolean plus) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.5f);
                int s = size;
                int d = s * 3 / 5;
                g2.drawOval(1, 1, d, d);
                g2.drawLine(1 + d * 3 / 4, 1 + d * 3 / 4, s - 2, s - 2);
                g2.drawLine(1 + d / 4, 1 + d / 2, 1 + d * 3 / 4, 1 + d / 2);
                if (plus) g2.drawLine(1 + d / 2, 1 + d / 4, 1 + d / 2, 1 + d * 3 / 4);
                g2.dispose();
            }
        };
    }

    public static Icon fit(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.5f);
                int s = size;
                g2.drawRect(1, 1, s - 3, s - 3);
                g2.drawLine(s / 3, s / 3, s * 2 / 3, s * 2 / 3);
                g2.drawLine(s * 2 / 3, s / 3, s / 3, s * 2 / 3);
                g2.dispose();
            }
        };
    }

    public static Icon palette(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                int s = size;
                g2.drawOval(1, 1, s - 3, s - 3);
                int dot = Math.max(2, s / 5);
                g2.fillOval(s / 4, s / 4, dot, dot);
                g2.fillOval(s / 2, s / 5, dot, dot);
                g2.fillOval(s * 9 / 16, s / 2, dot, dot);
                g2.dispose();
            }
        };
    }

    public static Icon image(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.4f);
                int s = size;
                g2.drawRoundRect(1, 1, s - 3, s - 3, 2, 2);
                int dot = Math.max(2, s / 6);
                g2.fillOval(s / 4, s / 4, dot, dot);
                Path2D p = new Path2D.Float();
                p.moveTo(2, s - 3);
                p.lineTo(s / 2, s / 2);
                p.lineTo(s - 2, s - 3);
                g2.draw(p);
                g2.dispose();
            }
        };
    }

    public static Icon code(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 1.5f);
                int s = size;
                g2.drawLine(s / 4, s / 3, 1, s / 2);
                g2.drawLine(1, s / 2, s / 4, s * 2 / 3);
                g2.drawLine(s * 3 / 4, s / 3, s - 1, s / 2);
                g2.drawLine(s - 1, s / 2, s * 3 / 4, s * 2 / 3);
                g2.dispose();
            }
        };
    }

    public static Icon check(Color color, int size) {
        return new Base(color, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                stroke(g2, 2.0f);
                int s = size;
                g2.drawLine(s / 5, s / 2, s * 2 / 5, s * 3 / 4);
                g2.drawLine(s * 2 / 5, s * 3 / 4, s * 4 / 5, s / 4);
                g2.dispose();
            }
        };
    }

    /** A solid rounded swatch, used for color pickers. */
    public static Icon swatch(Color fill, int size) {
        return new Base(fill, size) {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = prepare(c, g, x, y);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, size, size, 4, 4);
                g2.setColor(Tokens.alpha(Color.BLACK, 60));
                g2.drawRoundRect(0, 0, size - 1, size - 1, 4, 4);
                g2.dispose();
            }
        };
    }
}
