package minichat;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** MiniChat Client: điểm vào chương trình + màn hình đăng nhập. */
public class Client {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Client::showLogin);
    }

    static void showLogin() {
        new LoginFrame().setVisible(true);
    }

    static class LoginFrame extends JFrame {
        private final Ui.RoundedField host = new Ui.RoundedField(16);
        private final Ui.RoundedField port = new Ui.RoundedField(16);
        private final Ui.RoundedField user = new Ui.RoundedField(16);
        private final JLabel status = new JLabel(" ", SwingConstants.CENTER);
        private final Ui.RoundedButton connect =
                new Ui.RoundedButton("Kết nối", Ui.PRIMARY, Ui.PRIMARY_DARK, java.awt.Color.WHITE, 18);

        LoginFrame() {
            setTitle("MiniChat");
            setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            getContentPane().setBackground(Ui.BG);
            setLayout(new java.awt.GridBagLayout());

            Ui.RoundedPanel card = new Ui.RoundedPanel(Ui.CARD, Ui.BORDER, 32);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(BorderFactory.createEmptyBorder(28, 32, 28, 32));
            card.setPreferredSize(new Dimension(360, 470));

            JLabel title = new JLabel("MiniChat");
            title.setFont(Ui.FONT_T);
            title.setForeground(Ui.PRIMARY_DARK);
            centerLabel(title);
            JLabel sub = new JLabel("Kết nối đến server để bắt đầu");
            sub.setFont(Ui.FONT_S);
            sub.setForeground(Ui.MUTED);
            centerLabel(sub);
            card.add(title);
            card.add(Box.createVerticalStrut(4));
            card.add(sub);
            card.add(Box.createVerticalStrut(22));

            host.field.setText("localhost");
            port.field.setText(String.valueOf(Server.PORT));
            addField(card, "Server", host);
            addField(card, "Port", port);
            addField(card, "Tên của bạn", user);

            status.setFont(Ui.FONT_S);
            status.setForeground(Ui.DANGER);
            centerLabel(status);
            card.add(Box.createVerticalStrut(2));
            card.add(status);
            card.add(Box.createVerticalStrut(8));
            connect.setAlignmentX(0f);
            connect.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            card.add(connect);

            add(card);
            setSize(440, 560);
            setLocationRelativeTo(null);

            connect.addActionListener(e -> doConnect());
            user.field.addActionListener(e -> doConnect());
            host.field.addActionListener(e -> doConnect());
            port.field.addActionListener(e -> doConnect());
            SwingUtilities.invokeLater(user.field::requestFocusInWindow);
        }

        /** Căn giữa nhãn nhưng vẫn cùng trục căn lề trái với các thành phần khác. */
        private void centerLabel(JLabel l) {
            l.setAlignmentX(0f);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            l.setMaximumSize(new Dimension(Integer.MAX_VALUE, l.getPreferredSize().height));
        }

        private void addField(JPanel card, String label, Ui.RoundedField f) {
            JLabel l = new JLabel(label);
            l.setFont(Ui.FONT_S);
            l.setForeground(Ui.MUTED);
            l.setAlignmentX(0f);
            f.setAlignmentX(0f);
            f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            JPanel wrap = new JPanel(new BorderLayout(0, 4));
            wrap.setOpaque(false);
            wrap.setAlignmentX(0f);
            wrap.add(l, BorderLayout.NORTH);
            wrap.add(f, BorderLayout.CENTER);
            wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
            card.add(wrap);
            card.add(Box.createVerticalStrut(12));
        }

        private void fail(String msg) {
            status.setText(msg);
            connect.setEnabled(true);
        }

        private void doConnect() {
            final String h = host.field.getText().trim();
            final String name = user.field.getText().trim();
            final int p;
            try {
                p = Integer.parseInt(port.field.getText().trim());
            } catch (NumberFormatException ex) {
                fail("Port không hợp lệ");
                return;
            }
            if (name.isEmpty()) {
                fail("Hãy nhập tên của bạn");
                return;
            }
            connect.setEnabled(false);
            status.setForeground(Ui.MUTED);
            status.setText("Đang kết nối...");
            new Thread(() -> {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(h, p), 4000);
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(new BufferedWriter(
                            new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)), true);
                    out.println("LOGIN\t" + name);
                    String resp = in.readLine();
                    if (resp != null && resp.startsWith("LOGIN_OK")) {
                        SwingUtilities.invokeLater(() -> {
                            dispose();
                            new ChatFrame(s, in, out, name).setVisible(true);
                        });
                    } else {
                        s.close();
                        String m = resp != null && resp.contains("\t") ? resp.split("\t", 2)[1]
                                : "Đăng nhập thất bại";
                        SwingUtilities.invokeLater(() -> {
                            status.setForeground(Ui.DANGER);
                            fail(m);
                        });
                    }
                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() -> {
                        status.setForeground(Ui.DANGER);
                        fail("Không kết nối được: " + ex.getMessage());
                    });
                }
            }, "login").start();
        }
    }
}
