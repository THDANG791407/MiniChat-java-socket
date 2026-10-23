package minichat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * Cửa sổ chat chính. Ba kiểu hội thoại:
 *  - "ALL"      : chat chung (mọi user đang kết nối)
 *  - "G:<nhóm>" : chat nhóm (chỉ khi là thành viên)
 *  - "P:<user>" : chat riêng
 *
 * Vào nhóm: được trưởng/phó nhóm mời (mục LỜI MỜI) hoặc bấm vào nhóm để gửi yêu cầu rồi chờ duyệt.
 * Trưởng nhóm phân công phó nhóm, chuyển quyền, mời ra khỏi nhóm trong cửa sổ "Quản lý nhóm".
 */
public class ChatFrame extends JFrame {
    private static final long serialVersionUID = 1L;

    private static final String ALL = "ALL";
    private static final Color HOVER_SOFT = new Color(0xC5D3F7);

    private static class Msg {
        static final int CHAT = 0, SYSTEM = 1, ERROR = 2;
        final int kind;
        final String from, text, time;
        final boolean mine;

        Msg(int kind, String from, String text, String time, boolean mine) {
            this.kind = kind;
            this.from = from;
            this.text = text;
            this.time = time;
            this.mine = mine;
        }
    }

    // ---- mạng ----
    private final transient Socket socket;
    private final transient BufferedReader in;
    private final transient PrintWriter out;
    private final String me;
    private boolean closed;

    // ---- trạng thái ----
    private String myGroup;                 // nhóm đang là thành viên (null nếu chưa)
    private String myRole = "M";            // L trưởng nhóm, D phó nhóm, M thành viên
    private String current = ALL;           // hội thoại đang mở
    private final transient Map<String, List<Msg>> history = new HashMap<>();
    private final transient Set<String> unread = new HashSet<>();
    private transient List<String[]> groups = new ArrayList<>();   // {tên, số thành viên, trưởng nhóm}
    private transient List<String> users = new ArrayList<>();
    private transient List<String[]> members = new ArrayList<>();  // {tên, vai trò}
    private transient List<String> requests = new ArrayList<>();   // người đang xin vào nhóm của mình
    private transient List<String[]> invites = new ArrayList<>();  // {nhóm, người mời}
    private JDialog manageDialog;

    // ---- giao diện ----
    private final JPanel sidebarList = new Ui.VerticalPanel();
    private final JLabel title = new JLabel();
    private final JLabel subtitle = new JLabel();
    private final Ui.RoundedButton leaveBtn =
            new Ui.RoundedButton("Rời nhóm", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK, 14);
    private final Ui.RoundedButton manageBtn =
            new Ui.RoundedButton("Thành viên", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK, 14);
    private final Ui.VerticalPanel msgList = new Ui.VerticalPanel();
    private JScrollPane scroll;
    private final Ui.RoundedField input = new Ui.RoundedField(22);

    public ChatFrame(Socket socket, BufferedReader in, PrintWriter out, String me) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.me = me;
        buildUi();
        rebuildSidebar();
        updateHeader();
        addSystem(ALL, "Chào " + me + "! Đây là kênh chat chung cho tất cả mọi người.");
        render();
        startReader();
    }

    // =================================================================
    // Xây dựng giao diện
    // =================================================================
    private void buildUi() {
        setTitle("MiniChat - " + me);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setSize(1000, 660);
        setMinimumSize(new Dimension(780, 520));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Ui.BG);
        setLayout(new BorderLayout());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closed = true;
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // đã đóng
                }
                dispose();
                System.exit(0);
            }
        });

        // ----- thanh bên -----
        JPanel side = new JPanel(new BorderLayout(0, 10));
        side.setBackground(Ui.SIDEBAR);
        side.setPreferredSize(new Dimension(270, 0));
        side.setBorder(BorderFactory.createEmptyBorder(18, 14, 16, 14));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        JLabel brand = new JLabel("MiniChat");
        brand.setFont(Ui.FONT_T);
        brand.setForeground(Ui.PRIMARY_DARK);
        brand.setAlignmentX(0f);
        JLabel who = new JLabel("● " + me);
        who.setFont(Ui.FONT);
        who.setForeground(Ui.TEXT);
        who.setAlignmentX(0f);
        top.add(brand);
        top.add(Box.createVerticalStrut(2));
        top.add(who);
        side.add(top, BorderLayout.NORTH);

        sidebarList.setLayout(new BoxLayout(sidebarList, BoxLayout.Y_AXIS));
        sidebarList.setBackground(Ui.SIDEBAR);
        JScrollPane sp = new JScrollPane(sidebarList);
        Ui.styleScroll(sp, Ui.SIDEBAR);
        side.add(sp, BorderLayout.CENTER);

        Ui.RoundedButton create =
                new Ui.RoundedButton("+  Tạo nhóm mới", Ui.PRIMARY, Ui.PRIMARY_DARK, Color.WHITE, 18);
        create.setPreferredSize(new Dimension(0, 44));
        create.addActionListener(e -> {
            String g = prompt("Tạo nhóm", "Tên nhóm mới (bạn sẽ là trưởng nhóm)");
            if (g != null && !g.isEmpty()) send("CREATE\t" + g);
        });
        side.add(create, BorderLayout.SOUTH);

        // ----- khu vực chính -----
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Ui.BG);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(18, 24, 6, 24));
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        title.setFont(Ui.FONT_T.deriveFont(20f));
        title.setForeground(Ui.TEXT);
        subtitle.setFont(Ui.FONT_S);
        subtitle.setForeground(Ui.MUTED);
        titles.add(title);
        titles.add(subtitle);
        header.add(titles, BorderLayout.CENTER);
        leaveBtn.addActionListener(e -> {
            if (confirm("Rời nhóm", "Bạn có chắc muốn rời nhóm #" + myGroup + "?"
                    + (isLeader() ? " Quyền trưởng nhóm sẽ chuyển cho người khác." : ""), "Rời nhóm")) {
                send("LEAVE");
            }
        });
        manageBtn.addActionListener(e -> showManage());
        JPanel btnWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        btnWrap.setOpaque(false);
        btnWrap.add(manageBtn);
        btnWrap.add(leaveBtn);
        header.add(btnWrap, BorderLayout.EAST);
        main.add(header, BorderLayout.NORTH);

        msgList.setLayout(new BoxLayout(msgList, BoxLayout.Y_AXIS));
        msgList.setBackground(Ui.BG);
        msgList.setBorder(BorderFactory.createEmptyBorder(6, 20, 6, 20));
        Ui.VerticalPanel holder = new Ui.VerticalPanel();
        holder.setLayout(new BorderLayout());
        holder.setBackground(Ui.BG);
        holder.add(msgList, BorderLayout.NORTH);
        scroll = new JScrollPane(holder);
        Ui.styleScroll(scroll, Ui.BG);
        Timer resizeTimer = new Timer(150, e -> render());
        resizeTimer.setRepeats(false);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeTimer.restart();
            }
        });
        main.add(scroll, BorderLayout.CENTER);

        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 24, 20, 24));
        Ui.RoundedButton sendBtn = new Ui.RoundedButton("Gửi", Ui.PRIMARY, Ui.PRIMARY_DARK, Color.WHITE, 22);
        sendBtn.setPreferredSize(new Dimension(88, 46));
        input.setPreferredSize(new Dimension(0, 46));
        sendBtn.addActionListener(e -> sendMessage());
        input.field.addActionListener(e -> sendMessage());
        bar.add(input, BorderLayout.CENTER);
        bar.add(sendBtn, BorderLayout.EAST);
        main.add(bar, BorderLayout.SOUTH);

        add(side, BorderLayout.WEST);
        add(main, BorderLayout.CENTER);
    }

    // ----- hộp thoại bo tròn -----
    private JDialog baseDialog(String dialogTitle, int w, int h) {
        JDialog d = new JDialog(this, dialogTitle, true);
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Ui.BG);
        p.setBorder(BorderFactory.createEmptyBorder(22, 26, 22, 26));
        p.setPreferredSize(new Dimension(w, h));
        d.setContentPane(p);
        return d;
    }

    private JPanel buttonRow(Ui.RoundedButton... btns) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(0f);
        for (Ui.RoundedButton b : btns) row.add(b);
        return row;
    }

    /** Hộp thoại nhập chữ. Trả về null nếu bấm Hủy. */
    private String prompt(String dialogTitle, String label) {
        JDialog d = baseDialog(dialogTitle, 400, 170);
        JPanel p = (JPanel) d.getContentPane();
        JLabel l = new JLabel(label);
        l.setFont(Ui.FONT_B);
        l.setForeground(Ui.TEXT);
        l.setAlignmentX(0f);
        Ui.RoundedField f = new Ui.RoundedField(18);
        f.setAlignmentX(0f);
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        String[] result = new String[1];
        Runnable submit = () -> {
            result[0] = f.field.getText().trim();
            d.dispose();
        };
        Ui.RoundedButton ok = new Ui.RoundedButton("Tạo", Ui.PRIMARY, Ui.PRIMARY_DARK, Color.WHITE, 16);
        Ui.RoundedButton cancel = new Ui.RoundedButton("Hủy", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK, 16);
        ok.addActionListener(e -> submit.run());
        f.field.addActionListener(e -> submit.run());
        cancel.addActionListener(e -> d.dispose());
        p.add(l);
        p.add(Box.createVerticalStrut(8));
        p.add(f);
        p.add(Box.createVerticalStrut(16));
        p.add(buttonRow(cancel, ok));
        d.pack();
        d.setLocationRelativeTo(this);
        SwingUtilities.invokeLater(f.field::requestFocusInWindow);
        d.setVisible(true);
        return result[0];
    }

    /** Hộp thoại xác nhận. */
    private boolean confirm(String dialogTitle, String message, String okText) {
        int dlgW = 420;
        JDialog d = baseDialog(dialogTitle, dlgW, 170);
        JPanel p = (JPanel) d.getContentPane();
        // khung chữ tự xuống dòng, rộng đúng bằng phần nội dung của hộp thoại (trừ lề 2 x 26px)
        JTextArea l = wrappedText(message, Ui.TEXT, Ui.FONT, dlgW - 52);
        l.setAlignmentX(0f);
        // chiều cao hộp thoại = chữ + lề trên/dưới + khoảng cách + hàng nút
        p.setPreferredSize(new Dimension(dlgW, l.getPreferredSize().height + 44 + 20 + 40));
        boolean[] result = new boolean[1];
        Ui.RoundedButton ok = new Ui.RoundedButton(okText, Ui.PRIMARY, Ui.PRIMARY_DARK, Color.WHITE, 16);
        Ui.RoundedButton cancel = new Ui.RoundedButton("Hủy", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK, 16);
        ok.addActionListener(e -> {
            result[0] = true;
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        p.add(l);
        p.add(Box.createVerticalStrut(20));
        p.add(buttonRow(cancel, ok));
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        return result[0];
    }

    private Ui.RoundedButton smallBtn(String text, Color base, Color hover, Color fg) {
        Ui.RoundedButton b = new Ui.RoundedButton(text, base, hover, fg, 12);
        b.setFont(Ui.FONT_S.deriveFont(Font.BOLD));
        b.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        return b;
    }

    // =================================================================
    // Vai trò
    // =================================================================
    private boolean isLeader() {
        return "L".equals(myRole);
    }

    private boolean isManager() {
        return myGroup != null && ("L".equals(myRole) || "D".equals(myRole));
    }

    private String leaderOfGroup(String g) {
        for (String[] x : groups) {
            if (x[0].equals(g)) return x[2];
        }
        return "?";
    }

    // =================================================================
    // Thanh bên
    // =================================================================
    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_S);
        l.setForeground(Ui.MUTED);
        l.setAlignmentX(0f);
        l.setBorder(BorderFactory.createEmptyBorder(12, 6, 6, 0));
        return l;
    }

    private JLabel hintLabel(String text) {
        JLabel none = new JLabel(text);
        none.setFont(Ui.FONT_S);
        none.setForeground(Ui.MUTED);
        none.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        none.setAlignmentX(0f);
        return none;
    }

    private JComponent item(String text, String key, boolean muted, String tip) {
        boolean active = key.equals(current);
        String label = (unread.contains(key) ? "● " : "") + text;
        Color fg = active ? Color.WHITE : (muted ? Ui.MUTED : Ui.TEXT);
        Ui.RoundedButton b = new Ui.RoundedButton(label,
                active ? Ui.PRIMARY : Ui.CARD,
                active ? Ui.PRIMARY_DARK : Ui.SOFT, fg, 16);
        b.setFont(active ? Ui.FONT_B : Ui.FONT);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setAlignmentX(0f);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        b.setPreferredSize(new Dimension(100, 40));
        if (tip != null) b.setToolTipText(tip);
        b.addActionListener(e -> open(key));
        return b;
    }

    private JComponent inviteRow(String[] inv) {
        Ui.RoundedPanel card = new Ui.RoundedPanel(Ui.OTHER_PRIV, null, 18);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JLabel l = new JLabel("<html><b>#" + esc(inv[0]) + "</b><br>" + esc(inv[1]) + " mời bạn vào nhóm</html>");
        l.setFont(Ui.FONT_S);
        l.setForeground(Ui.TEXT);
        l.setAlignmentX(0f);
        Ui.RoundedButton yes = smallBtn("Chấp nhận", Ui.PRIMARY, Ui.PRIMARY_DARK, Color.WHITE);
        Ui.RoundedButton no = smallBtn("Từ chối", Ui.CARD, HOVER_SOFT, Ui.PRIMARY_DARK);
        yes.addActionListener(e -> send("INVITE_YES\t" + inv[0]));
        no.addActionListener(e -> send("INVITE_NO\t" + inv[0]));
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btns.setOpaque(false);
        btns.setAlignmentX(0f);
        btns.add(yes);
        btns.add(Box.createHorizontalStrut(6));
        btns.add(no);
        card.add(l);
        card.add(Box.createVerticalStrut(8));
        card.add(btns);
        card.setAlignmentX(0f);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private void rebuildSidebar() {
        sidebarList.removeAll();
        sidebarList.add(sectionLabel("CHAT CHUNG"));
        sidebarList.add(item("Chat chung", ALL, false, null));

        if (!invites.isEmpty()) {
            sidebarList.add(sectionLabel("LỜI MỜI VÀO NHÓM (" + invites.size() + ")"));
            for (String[] inv : invites) {
                sidebarList.add(inviteRow(inv));
                sidebarList.add(Box.createVerticalStrut(6));
            }
        }

        sidebarList.add(sectionLabel("NHÓM CHAT"));
        if (groups.isEmpty()) sidebarList.add(hintLabel("Chưa có nhóm nào"));
        for (String[] g : groups) {
            boolean mine = g[0].equals(myGroup);
            String text = "# " + g[0] + "   (" + g[1] + ")" + (mine ? "" : "  · xin vào");
            String tip = "Trưởng nhóm: " + g[2] + (mine ? "" : " - bấm để gửi yêu cầu tham gia");
            sidebarList.add(item(text, "G:" + g[0], !mine, tip));
            sidebarList.add(Box.createVerticalStrut(5));
        }

        sidebarList.add(sectionLabel("ĐANG ONLINE (chat riêng)"));
        boolean any = false;
        for (String u : users) {
            if (u.equals(me)) continue;
            any = true;
            sidebarList.add(item(u, "P:" + u, false, null));
            sidebarList.add(Box.createVerticalStrut(5));
        }
        if (!any) sidebarList.add(hintLabel("Chưa có ai khác online"));
        sidebarList.revalidate();
        sidebarList.repaint();
    }

    private void open(String key) {
        if (key.startsWith("G:") && !key.substring(2).equals(myGroup)) {
            String g = key.substring(2);
            for (String[] inv : invites) {          // đã được mời thì vào luôn
                if (inv[0].equals(g)) {
                    send("INVITE_YES\t" + g);
                    return;
                }
            }
            if (confirm("Xin vào nhóm", "Gửi yêu cầu tham gia nhóm #" + g
                    + " tới trưởng nhóm. Bạn chỉ vào được nhóm sau khi được duyệt.", "Gửi yêu cầu")) {
                send("REQUEST_JOIN\t" + g);
            }
            return;
        }
        current = key;
        unread.remove(key);
        rebuildSidebar();
        updateHeader();
        render();
        SwingUtilities.invokeLater(input.field::requestFocusInWindow);
    }

    private void updateHeader() {
        boolean inMyGroup = myGroup != null && current.equals("G:" + myGroup);
        if (current.equals(ALL)) {
            title.setText("Chat chung");
            subtitle.setText(users.size() + " người đang online");
        } else if (current.startsWith("G:")) {
            title.setText("# " + current.substring(2));
            subtitle.setText(members.size() + " thành viên · Trưởng nhóm: " + leaderOfGroup(myGroup)
                    + (isLeader() ? " (bạn)" : isManager() ? " · bạn là phó nhóm" : ""));
        } else {
            String u = current.substring(2);
            title.setText(u);
            subtitle.setText(users.contains(u) ? "Chat riêng" : "Chat riêng · người này đã offline");
        }
        leaveBtn.setVisible(inMyGroup);
        manageBtn.setVisible(inMyGroup);
        manageBtn.setText(isManager()
                ? (requests.isEmpty() ? "Quản lý nhóm" : "Quản lý nhóm (" + requests.size() + ")")
                : "Thành viên");
    }

    // =================================================================
    // Cửa sổ quản lý nhóm (trưởng nhóm phân công, duyệt, mời)
    // =================================================================
    void showManage() {
        if (myGroup == null) return;
        if (manageDialog == null) {
            manageDialog = new JDialog(this, "Nhóm", false);
            manageDialog.setSize(560, 620);
            manageDialog.setLocationRelativeTo(this);
            manageDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    manageDialog = null;
                }
            });
        }
        buildManage();
        manageDialog.setVisible(true);
        manageDialog.toFront();
    }

    private void refreshManage() {
        if (manageDialog == null) return;
        if (myGroup == null) {
            manageDialog.dispose();
            return;
        }
        buildManage();
    }

    private void buildManage() {
        manageDialog.setTitle("Nhóm #" + myGroup);
        Ui.VerticalPanel root = new Ui.VerticalPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(Ui.BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 18, 18, 18));

        root.add(heading("THÀNH VIÊN (" + members.size() + ")"));
        for (String[] m : members) root.add(memberRow(m));

        if (isManager()) {
            root.add(heading("YÊU CẦU THAM GIA (" + requests.size() + ")"));
            if (requests.isEmpty()) root.add(hintLabel("Chưa có yêu cầu nào"));
            for (String r : requests) root.add(requestRow(r));

            root.add(heading("MỜI THÊM NGƯỜI"));
            boolean any = false;
            for (String u : users) {
                if (inGroup(u)) continue;
                any = true;
                root.add(inviteCandidateRow(u));
            }
            if (!any) root.add(hintLabel("Không còn ai online để mời"));
        } else {
            root.add(Box.createVerticalStrut(10));
            root.add(hintLabel("Chỉ trưởng nhóm hoặc phó nhóm mới mời / duyệt thành viên."));
        }

        JScrollPane sp = new JScrollPane(root);
        Ui.styleScroll(sp, Ui.BG);
        manageDialog.setContentPane(sp);
        manageDialog.revalidate();
        manageDialog.repaint();
    }

    private boolean inGroup(String user) {
        for (String[] m : members) {
            if (m[0].equals(user)) return true;
        }
        return false;
    }

    private JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.FONT_S);
        l.setForeground(Ui.MUTED);
        l.setAlignmentX(0f);
        l.setBorder(BorderFactory.createEmptyBorder(14, 4, 8, 0));
        return l;
    }

    private JComponent row(String htmlText, Ui.RoundedButton... btns) {
        Ui.RoundedPanel card = new Ui.RoundedPanel(Ui.CARD, Ui.BORDER, 18);
        card.setLayout(new BorderLayout(8, 0));
        card.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 10));
        JLabel l = new JLabel(htmlText);
        l.setFont(Ui.FONT);
        l.setForeground(Ui.TEXT);
        card.add(l, BorderLayout.CENTER);
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        bp.setOpaque(false);
        for (Ui.RoundedButton b : btns) bp.add(b);
        card.add(bp, BorderLayout.EAST);
        card.setAlignmentX(0f);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        card.setPreferredSize(new Dimension(100, 48));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        wrap.add(card);
        wrap.setAlignmentX(0f);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        return wrap;
    }

    private JComponent memberRow(String[] m) {
        String name = m[0];
        String role = m[1];
        boolean self = name.equals(me);
        String tag = "L".equals(role) ? " <font color='#5878DB'>· trưởng nhóm</font>"
                : "D".equals(role) ? " <font color='#9A8CF0'>· phó nhóm</font>" : "";
        String text = "<html>" + esc(name) + (self ? " <font color='#8A94A6'>(bạn)</font>" : "") + tag + "</html>";
        List<Ui.RoundedButton> btns = new ArrayList<>();
        if (!self && isLeader()) {
            if ("M".equals(role)) {
                Ui.RoundedButton b = smallBtn("Phó nhóm", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK);
                b.addActionListener(e -> send("PROMOTE\t" + name));
                btns.add(b);
            } else if ("D".equals(role)) {
                Ui.RoundedButton b = smallBtn("Bỏ phó nhóm", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK);
                b.addActionListener(e -> send("DEMOTE\t" + name));
                btns.add(b);
            }
            Ui.RoundedButton t = smallBtn("Chuyển quyền", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK);
            t.addActionListener(e -> {
                if (confirm("Chuyển quyền trưởng nhóm", "Chuyển quyền trưởng nhóm cho " + name
                        + "? Bạn sẽ trở thành phó nhóm.", "Chuyển quyền")) {
                    send("TRANSFER\t" + name);
                }
            });
            btns.add(t);
        }
        boolean canKick = !self && !"L".equals(role)
                && (isLeader() || (isManager() && "M".equals(role)));
        if (canKick) {
            Ui.RoundedButton k = smallBtn("Mời ra", Ui.DANGER, new Color(0xD9667A), Color.WHITE);
            k.addActionListener(e -> {
                if (confirm("Mời ra khỏi nhóm", "Mời " + name + " ra khỏi nhóm #" + myGroup + "?", "Mời ra")) {
                    send("KICK\t" + name);
                }
            });
            btns.add(k);
        }
        return row(text, btns.toArray(new Ui.RoundedButton[0]));
    }

    private JComponent requestRow(String user) {
        Ui.RoundedButton yes = smallBtn("Duyệt", Ui.PRIMARY, Ui.PRIMARY_DARK, Color.WHITE);
        Ui.RoundedButton no = smallBtn("Từ chối", Ui.SOFT, HOVER_SOFT, Ui.PRIMARY_DARK);
        yes.addActionListener(e -> send("REQ_YES\t" + user));
        no.addActionListener(e -> send("REQ_NO\t" + user));
        return row("<html>" + esc(user) + " <font color='#8A94A6'>xin vào nhóm</font></html>", yes, no);
    }

    private JComponent inviteCandidateRow(String user) {
        Ui.RoundedButton b = smallBtn("Mời", Ui.PRIMARY, Ui.PRIMARY_DARK, Color.WHITE);
        b.addActionListener(e -> {
            send("INVITE\t" + user);
            b.setEnabled(false);
            b.setText("Đã mời");
        });
        return row("<html>" + esc(user) + "</html>", b);
    }

    // =================================================================
    // Hiển thị tin nhắn
    // =================================================================
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void render() {
        msgList.removeAll();
        List<Msg> list = history.get(current);
        if (list != null) {
            for (Msg m : list) msgList.add(buildRow(m));
        }
        msgList.revalidate();
        msgList.repaint();
        scrollBottom();
    }

    private void addMsg(String key, Msg m) {
        history.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        if (key.equals(current)) {
            msgList.add(buildRow(m));
            msgList.revalidate();
            msgList.repaint();
            scrollBottom();
        } else if (m.kind == Msg.CHAT && !m.mine) {
            unread.add(key);
            rebuildSidebar();
        }
    }

    private void addSystem(String key, String text) {
        addMsg(key, new Msg(Msg.SYSTEM, "", text, "", false));
    }

    private void scrollBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar b = scroll.getVerticalScrollBar();
            b.setValue(b.getMaximum());
        });
    }

    private JComponent buildRow(Msg m) {
        if (m.kind != Msg.CHAT) {
            Color sysColor = m.kind == Msg.ERROR ? Ui.DANGER : Ui.MUTED;
            int sysMax = Math.max(scroll.getViewport().getWidth() - 100, 240);
            Ui.RoundedPanel chip = new Ui.RoundedPanel(Ui.SIDEBAR, null, 16);
            chip.setLayout(new BorderLayout());
            chip.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
            // thông báo dài (kể cả từ không có khoảng trắng) tự xuống dòng thay vì bị cắt hai đầu
            chip.add(wrappedText(m.text, sysColor, Ui.FONT_S, sysMax));
            fixSize(chip, chip.getPreferredSize());
            JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 6));
            row.setOpaque(false);
            row.add(chip);
            return limit(row);
        }
        boolean priv = current.startsWith("P:");
        Color fill = m.mine ? (priv ? Ui.MINE_PRIV : Ui.PRIMARY) : (priv ? Ui.OTHER_PRIV : Ui.CARD);
        Color fg = m.mine ? Color.WHITE : Ui.TEXT;

        Ui.RoundedPanel bubble = new Ui.RoundedPanel(fill, m.mine ? null : Ui.BORDER, 24);
        bubble.setLayout(new BorderLayout());
        bubble.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        bubble.add(wrappedText(m.text, fg, Ui.FONT, bubbleMaxWidth()));
        fixSize(bubble, bubble.getPreferredSize());
        bubble.setAlignmentX(m.mine ? 1f : 0f);

        JLabel head = new JLabel((m.mine ? "Bạn" : m.from) + "  " + m.time + (priv ? "  · riêng tư" : ""));
        head.setFont(Ui.FONT_S);
        head.setForeground(Ui.MUTED);
        head.setBorder(BorderFactory.createEmptyBorder(0, 8, 3, 8));
        head.setAlignmentX(m.mine ? 1f : 0f);

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.add(head);
        col.add(bubble);

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        row.add(col, m.mine ? BorderLayout.EAST : BorderLayout.WEST);
        return limit(row);
    }

    /** Chiều rộng tối đa của phần chữ trong bong bóng, luôn nhỏ hơn khung chat để không bị cắt. */
    private int bubbleMaxWidth() {
        int vw = scroll.getViewport().getWidth();
        if (vw <= 0) vw = 640;                        // chưa hiển thị: dùng giá trị tạm, render lại khi có kích thước thật
        int w = (int) (vw * 0.62);
        w = Math.min(w, vw - 120);                    // chừa lề + đầu + padding bong bóng
        return Math.max(w, 160);
    }

    /**
     * Văn bản tự xuống dòng, kể cả từ rất dài không có khoảng trắng (URL, AAAA...).
     * Kích thước được đo và cố định theo chiều rộng đã chọn nên các dòng không chồng lên nhau.
     */
    private JTextArea wrappedText(String text, Color fg, Font font, int maxW) {
        JTextArea ta = new JTextArea(text);
        ta.setFont(font);
        ta.setForeground(fg);
        ta.setCaretColor(fg);
        ta.setOpaque(false);
        ta.setEditable(false);
        ta.setBorder(null);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);                    // ngắt theo từ; từ quá dài vẫn bị ngắt theo ký tự
        FontMetrics fm = ta.getFontMetrics(font);
        int natural = fm.stringWidth(text) + 6;       // chừa dư vài px để tránh ngắt dòng sớm do làm tròn
        int w = Math.min(natural, maxW);
        ta.setSize(w, Short.MAX_VALUE);               // để JTextArea tính chiều cao theo đúng chiều rộng w
        fixSize(ta, new Dimension(w, ta.getPreferredSize().height));
        return ta;
    }

    private static void fixSize(JComponent c, Dimension d) {
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
    }

    private JComponent limit(JPanel row) {
        row.setAlignmentX(0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    // =================================================================
    // Mạng
    // =================================================================
    private void send(String line) {
        out.println(line);
        if (out.checkError()) connectionLost();
    }

    private void sendMessage() {
        String t = input.field.getText().trim().replace('\t', ' ');
        if (t.isEmpty()) return;
        input.field.setText("");
        if (current.equals(ALL)) {
            send("MSG_ALL\t" + t);
        } else if (current.startsWith("G:")) {
            send("MSG_GROUP\t" + t);
        } else {
            send("MSG_PRIVATE\t" + current.substring(2) + "\t" + t);
        }
    }

    private void startReader() {
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    final String l = line;
                    SwingUtilities.invokeLater(() -> handle(l));
                }
            } catch (IOException ignored) {
                // mất kết nối
            }
            SwingUtilities.invokeLater(this::connectionLost);
        }, "reader");
        t.setDaemon(true);
        t.start();
    }

    private void connectionLost() {
        if (closed) return;
        closed = true;
        if (manageDialog != null) manageDialog.dispose();
        JOptionPane.showMessageDialog(this, "Mất kết nối tới server", "MiniChat", JOptionPane.WARNING_MESSAGE);
        dispose();
        Client.showLogin();
    }

    /** Không còn là thành viên nhóm (tự rời hoặc bị mời ra). */
    private void leftGroup(String notice) {
        String old = "G:" + myGroup;
        myGroup = null;
        myRole = "M";
        members = new ArrayList<>();
        requests = new ArrayList<>();
        history.remove(old);
        unread.remove(old);
        if (current.equals(old)) current = ALL;
        addSystem(ALL, notice);
        rebuildSidebar();
        updateHeader();
        refreshManage();
        render();
    }

    private void handle(String line) {
        String[] p = line.split("\t", -1);
        switch (p[0]) {
            case "GROUPS": {
                groups = new ArrayList<>();
                for (int i = 1; i < p.length; i++) {
                    String[] kv = p[i].split("\\|");
                    if (kv.length == 3) groups.add(kv);
                }
                rebuildSidebar();
                updateHeader();
                break;
            }
            case "USERS": {
                users = new ArrayList<>();
                for (int i = 1; i < p.length; i++) users.add(p[i]);
                rebuildSidebar();
                updateHeader();
                refreshManage();
                break;
            }
            case "MEMBERS": {
                if (p.length > 1 && p[1].equals(myGroup)) {
                    members = new ArrayList<>();
                    myRole = "M";
                    for (int i = 2; i < p.length; i++) {
                        String[] kv = p[i].split("\\|");
                        if (kv.length == 2) {
                            members.add(kv);
                            if (kv[0].equals(me)) myRole = kv[1];
                        }
                    }
                    updateHeader();
                    refreshManage();
                }
                break;
            }
            case "REQUESTS": {
                if (p.length > 1 && p[1].equals(myGroup)) {
                    requests = new ArrayList<>();
                    for (int i = 2; i < p.length; i++) requests.add(p[i]);
                    updateHeader();
                    refreshManage();
                }
                break;
            }
            case "INVITES": {
                invites = new ArrayList<>();
                for (int i = 1; i < p.length; i++) {
                    String[] kv = p[i].split("\\|");
                    if (kv.length == 2) invites.add(kv);
                }
                rebuildSidebar();
                break;
            }
            case "JOINED": {
                if (myGroup != null) history.remove("G:" + myGroup);
                myGroup = p[1];
                myRole = "M";
                members = new ArrayList<>();
                requests = new ArrayList<>();
                history.put("G:" + myGroup, new ArrayList<>());
                current = "G:" + myGroup;
                unread.remove(current);
                addSystem(current, "Bạn đã vào nhóm #" + myGroup);
                rebuildSidebar();
                updateHeader();
                render();
                input.field.requestFocusInWindow();
                break;
            }
            case "LEFT":
                leftGroup("Bạn đã rời nhóm");
                break;
            case "KICKED":
                leftGroup("Bạn đã bị mời ra khỏi nhóm #" + (p.length > 1 ? p[1] : ""));
                break;
            case "CHAT": {
                String[] c = line.split("\t", 6); // CHAT scope from to time text
                if (c.length < 6) break;
                boolean mine = c[2].equals(me);
                Msg m = new Msg(Msg.CHAT, c[2], c[5], c[4], mine);
                String key;
                if (c[1].equals("ALL")) key = ALL;
                else if (c[1].equals("GROUP")) key = "G:" + c[3];
                else key = "P:" + (mine ? c[3] : c[2]);
                addMsg(key, m);
                break;
            }
            case "GINFO": {
                if (myGroup != null) addSystem("G:" + myGroup, p.length > 1 ? p[1] : "");
                break;
            }
            case "INFO": {
                addSystem(current, p.length > 1 ? p[1] : "");
                break;
            }
            case "ERROR": {
                addMsg(current, new Msg(Msg.ERROR, "", p.length > 1 ? p[1] : "Lỗi", "", false));
                break;
            }
            default:
                break;
        }
    }
}
