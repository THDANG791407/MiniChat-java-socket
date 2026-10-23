package minichat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** Bảng màu dịu mắt và các thành phần giao diện bo tròn góc. */
public final class Ui {
    private Ui() {
    }

    public static final Color BG = new Color(0xF3F6FB);
    public static final Color SIDEBAR = new Color(0xE6ECF7);
    public static final Color CARD = Color.WHITE;
    public static final Color PRIMARY = new Color(0x6C8EF5);
    public static final Color PRIMARY_DARK = new Color(0x5878DB);
    public static final Color SOFT = new Color(0xD7E1FB);
    public static final Color TEXT = new Color(0x2D3748);
    public static final Color MUTED = new Color(0x8A94A6);
    public static final Color BORDER = new Color(0xD5DDEB);
    public static final Color MINE_PRIV = new Color(0x9A8CF0);
    public static final Color OTHER_PRIV = new Color(0xFFF1D6);
    public static final Color DANGER = new Color(0xE8788A);

    public static final Font FONT = new Font("Segoe UI", Font.PLAIN, 14);
    public static final Font FONT_B = new Font("Segoe UI", Font.BOLD, 14);
    public static final Font FONT_S = new Font("Segoe UI", Font.PLAIN, 12);
    public static final Font FONT_T = new Font("Segoe UI", Font.BOLD, 22);

    static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    /** Panel nền bo tròn. */
    public static class RoundedPanel extends JPanel {
        private final Color fill;
        private final Color line;
        private final int arc;

        public RoundedPanel(Color fill, Color line, int arc) {
            this.fill = fill;
            this.line = line;
            this.arc = arc;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            if (line != null) {
                g2.setColor(line);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Nút bo tròn, đổi màu khi rê chuột. */
    public static class RoundedButton extends JButton {
        private final Color base;
        private final Color hover;
        private final int arc;
        private boolean over;

        public RoundedButton(String text, Color base, Color hover, Color fg, int arc) {
            super(text);
            this.base = base;
            this.hover = hover;
            this.arc = arc;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setForeground(fg);
            setFont(FONT_B);
            setHorizontalAlignment(SwingConstants.CENTER);
            setMargin(new Insets(8, 16, 8, 16));
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    over = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    over = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(over ? hover : base);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Ô nhập bo tròn. */
    public static class RoundedField extends RoundedPanel {
        public final JTextField field = new JTextField();

        public RoundedField(int arc) {
            super(CARD, BORDER, arc);
            setLayout(new BorderLayout());
            field.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            field.setOpaque(false);
            field.setFont(FONT);
            field.setForeground(TEXT);
            field.setCaretColor(TEXT);
            add(field, BorderLayout.CENTER);
        }
    }

    /** Panel cuộn dọc, luôn rộng bằng khung nhìn. */
    public static class VerticalPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
            return r.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Thanh cuộn mảnh, bo tròn. */
    public static void styleScroll(JScrollPane sp, Color bg) {
        sp.setBorder(null);
        sp.setBackground(bg);
        sp.getViewport().setBackground(bg);
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.getVerticalScrollBar().setBackground(bg);
    }

    private static class SlimScrollBarUI extends BasicScrollBarUI {
        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zero();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zero();
        }

        private JButton zero() {
            JButton b = new JButton();
            Dimension d = new Dimension(0, 0);
            b.setPreferredSize(d);
            b.setMinimumSize(d);
            b.setMaximumSize(d);
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            // không vẽ nền track
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(new Color(0xC3CFEA));
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g2.dispose();
        }
    }
}
