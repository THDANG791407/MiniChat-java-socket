package minichat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MiniChat Server (Java socket).
 *
 * - Luôn chờ và chấp nhận kết nối từ client (ServerSocket.accept), mỗi client một luồng.
 * - Giữ danh sách kết nối (clients) và danh sách nhóm (groups).
 * - Nhóm có trưởng nhóm (người tạo) và các phó nhóm do trưởng nhóm bổ nhiệm.
 * - User KHÔNG tự vào nhóm: phải được trưởng/phó nhóm mời, hoặc gửi yêu cầu và được duyệt.
 * - Phân phối tin nhắn: chat chung (tất cả), chat nhóm (thành viên), chat riêng.
 *
 * Giao thức văn bản, mỗi dòng một lệnh, các trường cách nhau bằng TAB.
 */
public class Server {

    public static final int PORT = 5000;

    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_\\- ]{1,20}");
    private static final Pattern GROUP = Pattern.compile("[\\p{L}\\p{N}_\\- ]{1,30}");

    /** Danh sách các kết nối từ user. */
    private final List<Handler> clients = new ArrayList<>();
    /** Danh sách các nhóm đang tồn tại. */
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Object lock = new Object();

    /** Một nhóm chat: trưởng nhóm, phó nhóm, thành viên và các yêu cầu đang chờ duyệt. */
    private static class Group {
        final String name;
        Handler leader;
        final Set<Handler> deputies = new LinkedHashSet<>();
        final List<Handler> members = new ArrayList<>();
        final Set<Handler> requests = new LinkedHashSet<>();

        Group(String name) {
            this.name = name;
        }

        boolean isManager(Handler h) {
            return h == leader || deputies.contains(h);
        }

        /** L = trưởng nhóm, D = phó nhóm, M = thành viên. */
        String role(Handler h) {
            return h == leader ? "L" : deputies.contains(h) ? "D" : "M";
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : PORT;
        new Server().start(port);
    }

    void start(int port) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("MiniChat server dang cho ket noi tai cong " + port);
            while (true) {
                Socket socket = serverSocket.accept(); // chờ và chấp nhận client
                try {
                    Handler h = new Handler(socket);
                    synchronized (lock) {
                        clients.add(h); // đưa kết nối vào danh sách
                    }
                    Thread t = new Thread(h, "client-" + socket.getPort());
                    t.setDaemon(true);
                    t.start();
                } catch (IOException e) {
                    socket.close();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Tiện ích gửi trạng thái
    // ------------------------------------------------------------------
    private static String time() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private Handler findUser(String name) {
        for (Handler h : clients) {
            if (h.name != null && h.name.equalsIgnoreCase(name)) return h;
        }
        return null;
    }

    /** Gửi danh sách nhóm (tên|số thành viên|trưởng nhóm) và user online cho mọi client. */
    private void broadcastLists() {
        synchronized (lock) {
            StringBuilder g = new StringBuilder("GROUPS");
            for (Group x : groups.values()) {
                g.append('\t').append(x.name).append('|').append(x.members.size())
                        .append('|').append(x.leader.name);
            }
            StringBuilder u = new StringBuilder("USERS");
            for (Handler h : clients) {
                if (h.name != null) u.append('\t').append(h.name);
            }
            for (Handler h : clients) {
                if (h.name != null) {
                    h.send(g.toString());
                    h.send(u.toString());
                }
            }
        }
    }

    private void sendMembers(Group g) {
        StringBuilder sb = new StringBuilder("MEMBERS\t").append(g.name);
        for (Handler h : g.members) sb.append('\t').append(h.name).append('|').append(g.role(h));
        for (Handler h : g.members) h.send(sb.toString());
    }

    /** Danh sách yêu cầu chờ duyệt chỉ gửi cho trưởng nhóm và phó nhóm. */
    private void sendRequests(Group g) {
        StringBuilder sb = new StringBuilder("REQUESTS\t").append(g.name);
        for (Handler r : g.requests) sb.append('\t').append(r.name);
        for (Handler h : g.members) {
            if (g.isManager(h)) h.send(sb.toString());
        }
    }

    private void sendGroupState(Group g) {
        sendMembers(g);
        sendRequests(g);
    }

    private void sendInvites(Handler h) {
        StringBuilder sb = new StringBuilder("INVITES");
        for (Map.Entry<String, String> e : h.invites.entrySet()) {
            sb.append('\t').append(e.getKey()).append('|').append(e.getValue());
        }
        h.send(sb.toString());
    }

    // ------------------------------------------------------------------
    // Thao tác nhóm (luôn gọi trong khi giữ lock)
    // ------------------------------------------------------------------
    /** Đưa user vào nhóm (đã được mời hoặc đã được duyệt). */
    private void addMember(Handler h, Group g) {
        synchronized (lock) {
            removeFromGroup(h, null);       // mỗi user chỉ ở một nhóm
            g.members.add(h);
            h.group = g;
            g.requests.remove(h);
            h.invites.remove(g.name);
            for (Handler m : g.members) {
                if (m != h) m.send("GINFO\t" + h.name + " đã tham gia nhóm");
            }
            h.send("JOINED\t" + g.name);
            sendGroupState(g);
            sendInvites(h);
            broadcastLists();
        }
    }

    /**
     * Xóa user khỏi nhóm hiện tại.
     * reason: "LEFT" (tự rời), "KICKED" (bị mời ra), null (chuyển nhóm / mất kết nối).
     */
    private void removeFromGroup(Handler h, String reason) {
        synchronized (lock) {
            Group g = h.group;
            if (g == null) return;
            boolean wasLeader = g.leader == h;
            g.members.remove(h);
            g.deputies.remove(h);
            h.group = null;
            if (g.members.isEmpty()) {
                deleteGroup(g);             // nhóm trống thì xóa
            } else {
                String msg = h.name + ("KICKED".equals(reason) ? " đã bị mời ra khỏi nhóm" : " đã rời nhóm");
                if (wasLeader) {            // trưởng nhóm rời: phó nhóm đầu tiên (hoặc người vào sớm nhất) kế nhiệm
                    Handler nl = g.deputies.isEmpty() ? g.members.get(0) : g.deputies.iterator().next();
                    g.deputies.remove(nl);
                    g.leader = nl;
                    msg += ". " + nl.name + " là trưởng nhóm mới";
                }
                for (Handler m : g.members) m.send("GINFO\t" + msg);
                sendGroupState(g);
            }
            if ("KICKED".equals(reason)) h.send("KICKED\t" + g.name);
            else if ("LEFT".equals(reason)) h.send("LEFT");
            broadcastLists();
        }
    }

    private void deleteGroup(Group g) {
        groups.remove(g.name);
        for (Handler c : clients) {
            if (c.invites.remove(g.name) != null) sendInvites(c);
        }
    }

    // ------------------------------------------------------------------
    /** Xử lý một client. */
    private class Handler implements Runnable {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        volatile String name;   // null = chưa đăng nhập
        volatile Group group;   // null = chưa ở nhóm nào (mặc định)
        /** Lời mời đang chờ: tên nhóm -> người mời. */
        final Map<String, String> invites = new LinkedHashMap<>();

        Handler(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)), true);
        }

        void send(String line) {
            synchronized (out) {
                out.println(line);
            }
        }

        void error(String text) {
            send("ERROR\t" + text);
        }

        void info(String text) {
            send("INFO\t" + text);
        }

        @Override
        public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    process(line);
                }
            } catch (IOException ignored) {
                // client ngắt kết nối
            } finally {
                cleanup();
            }
        }

        private void process(String line) {
            String[] p = line.split("\t", line.startsWith("MSG_PRIVATE") ? 3 : 2);
            String cmd = p[0];
            String a1 = p.length > 1 ? p[1].trim() : "";

            if (name == null) {
                if (!cmd.equals("LOGIN")) {
                    error("Cần đăng nhập trước");
                    return;
                }
                login(a1);
                return;
            }
            synchronized (lock) {
                switch (cmd) {
                    case "CREATE":
                        create(a1);
                        break;
                    case "REQUEST_JOIN":
                        requestJoin(a1);
                        break;
                    case "INVITE":
                        invite(a1);
                        break;
                    case "INVITE_YES":
                        inviteReply(a1, true);
                        break;
                    case "INVITE_NO":
                        inviteReply(a1, false);
                        break;
                    case "REQ_YES":
                        requestReply(a1, true);
                        break;
                    case "REQ_NO":
                        requestReply(a1, false);
                        break;
                    case "KICK":
                        kick(a1);
                        break;
                    case "PROMOTE":
                        setDeputy(a1, true);
                        break;
                    case "DEMOTE":
                        setDeputy(a1, false);
                        break;
                    case "TRANSFER":
                        transfer(a1);
                        break;
                    case "LEAVE":
                        if (group == null) error("Bạn chưa ở trong nhóm nào");
                        else removeFromGroup(this, "LEFT");
                        break;
                    case "MSG_ALL":
                        msgAll(a1);
                        break;
                    case "MSG_GROUP":
                        msgGroup(a1);
                        break;
                    case "MSG_PRIVATE":
                        msgPrivate(a1, p.length > 2 ? p[2].trim() : "");
                        break;
                    default:
                        error("Lệnh không hợp lệ");
                }
            }
        }

        private void login(String n) {
            synchronized (lock) {
                if (!NAME.matcher(n).matches()) {
                    send("LOGIN_ERR\tTên phải từ 1-20 ký tự (chữ, số, _ - và khoảng trắng)");
                    return;
                }
                if (findUser(n) != null) {
                    send("LOGIN_ERR\tTên này đang được dùng");
                    return;
                }
                name = n;
                send("LOGIN_OK\t" + n);
                System.out.println("[+] " + n + " " + socket.getRemoteSocketAddress());
                broadcastLists();
            }
        }

        // ----- kiểm tra quyền -----
        private Group needGroup() {
            if (group == null) error("Bạn chưa ở trong nhóm nào");
            return group;
        }

        private Group needManager() {
            Group g = needGroup();
            if (g != null && !g.isManager(this)) {
                error("Chỉ trưởng nhóm hoặc phó nhóm mới làm được việc này");
                return null;
            }
            return g;
        }

        private Group needLeader() {
            Group g = needGroup();
            if (g != null && g.leader != this) {
                error("Chỉ trưởng nhóm mới làm được việc này");
                return null;
            }
            return g;
        }

        private Handler memberNamed(Group g, String n) {
            for (Handler m : g.members) {
                if (m.name.equalsIgnoreCase(n)) return m;
            }
            error("'" + n + "' không ở trong nhóm");
            return null;
        }

        // ----- tạo nhóm / vào nhóm -----
        /** Tạo nhóm: người tạo là trưởng nhóm và tự vào nhóm. */
        private void create(String g) {
            if (!GROUP.matcher(g).matches()) {
                error("Tên nhóm phải từ 1-30 ký tự (chữ, số, _ - và khoảng trắng)");
                return;
            }
            if (groups.containsKey(g)) {
                error("Tên nhóm đã tồn tại");
                return;
            }
            Group ng = new Group(g);
            ng.leader = this;
            groups.put(g, ng);              // đưa nhóm vào danh sách
            addMember(this, ng);
        }

        /** User gửi yêu cầu xin vào nhóm, chờ trưởng/phó nhóm duyệt. */
        private void requestJoin(String gname) {
            Group g = groups.get(gname);
            if (g == null) {
                error("Nhóm không tồn tại");
                return;
            }
            if (g == group) {
                error("Bạn đã ở trong nhóm này");
                return;
            }
            if (invites.containsKey(gname)) {   // đã được mời sẵn thì vào luôn
                addMember(this, g);
                return;
            }
            if (g.requests.contains(this)) {
                error("Bạn đã gửi yêu cầu rồi, hãy chờ trưởng nhóm duyệt");
                return;
            }
            g.requests.add(this);
            info("Đã gửi yêu cầu vào nhóm #" + gname + ", chờ trưởng nhóm duyệt");
            for (Handler m : g.members) {
                if (g.isManager(m)) m.send("GINFO\t" + name + " xin vào nhóm");
            }
            sendRequests(g);
        }

        /** Trưởng/phó nhóm mời một user đang online. */
        private void invite(String user) {
            Group g = needManager();
            if (g == null) return;
            Handler t = findUser(user);
            if (t == null) {
                error("'" + user + "' không online");
                return;
            }
            if (t.group == g) {
                error(t.name + " đã ở trong nhóm");
                return;
            }
            if (g.requests.contains(t)) {       // người này đang xin vào: duyệt luôn
                addMember(t, g);
                return;
            }
            if (t.invites.containsKey(g.name)) {
                error("Đã mời " + t.name + " rồi, đang chờ trả lời");
                return;
            }
            t.invites.put(g.name, name);
            sendInvites(t);
            t.info(name + " mời bạn vào nhóm #" + g.name);
            info("Đã gửi lời mời tới " + t.name);
        }

        /** Người được mời chấp nhận hoặc từ chối. */
        private void inviteReply(String gname, boolean yes) {
            String from = invites.remove(gname);
            if (from == null) {
                error("Không có lời mời vào nhóm này");
                return;
            }
            Group g = groups.get(gname);
            if (!yes || g == null) {
                sendInvites(this);
                if (g != null) {
                    for (Handler m : g.members) {
                        if (g.isManager(m)) m.send("GINFO\t" + name + " từ chối lời mời vào nhóm");
                    }
                }
                return;
            }
            addMember(this, g);
        }

        /** Trưởng/phó nhóm duyệt hoặc từ chối yêu cầu xin vào. */
        private void requestReply(String user, boolean yes) {
            Group g = needManager();
            if (g == null) return;
            Handler t = null;
            for (Handler r : g.requests) {
                if (r.name.equalsIgnoreCase(user)) t = r;
            }
            if (t == null) {
                error("Không có yêu cầu của '" + user + "' (có thể người khác đã xử lý)");
                return;
            }
            g.requests.remove(t);
            if (yes) {
                addMember(t, g);
            } else {
                t.info("Yêu cầu vào nhóm #" + g.name + " đã bị từ chối");
                sendRequests(g);
            }
        }

        // ----- phân công / quản lý thành viên -----
        /** Mời thành viên ra khỏi nhóm. Phó nhóm chỉ mời được thành viên thường. */
        private void kick(String user) {
            Group g = needManager();
            if (g == null) return;
            Handler t = memberNamed(g, user);
            if (t == null) return;
            if (t == this) {
                error("Hãy dùng nút Rời nhóm");
                return;
            }
            if (t == g.leader) {
                error("Không thể mời trưởng nhóm ra khỏi nhóm");
                return;
            }
            if (g.deputies.contains(t) && g.leader != this) {
                error("Chỉ trưởng nhóm mới mời được phó nhóm ra khỏi nhóm");
                return;
            }
            removeFromGroup(t, "KICKED");
        }

        /** Trưởng nhóm bổ nhiệm / bãi nhiệm phó nhóm. */
        private void setDeputy(String user, boolean on) {
            Group g = needLeader();
            if (g == null) return;
            Handler t = memberNamed(g, user);
            if (t == null) return;
            if (t == g.leader) {
                error("Bạn là trưởng nhóm rồi");
                return;
            }
            if (on == g.deputies.contains(t)) {
                error(on ? t.name + " đã là phó nhóm" : t.name + " không phải phó nhóm");
                return;
            }
            if (on) g.deputies.add(t);
            else g.deputies.remove(t);
            String msg = t.name + (on ? " được bổ nhiệm làm phó nhóm" : " không còn là phó nhóm");
            for (Handler m : g.members) m.send("GINFO\t" + msg);
            sendGroupState(g);
        }

        /** Trưởng nhóm chuyển quyền cho thành viên khác (mình thành phó nhóm). */
        private void transfer(String user) {
            Group g = needLeader();
            if (g == null) return;
            Handler t = memberNamed(g, user);
            if (t == null) return;
            if (t == this) {
                error("Bạn đã là trưởng nhóm");
                return;
            }
            g.deputies.remove(t);
            g.deputies.add(this);
            g.leader = t;
            for (Handler m : g.members) {
                m.send("GINFO\t" + name + " đã chuyển quyền trưởng nhóm cho " + t.name);
            }
            sendGroupState(g);
            broadcastLists();
        }

        // ----- chat -----
        /** Chat chung: gửi cho tất cả user đang kết nối. */
        private void msgAll(String text) {
            text = clean(text);
            if (text.isEmpty()) return;
            String m = "CHAT\tALL\t" + name + "\t\t" + time() + "\t" + text;
            for (Handler h : clients) {
                if (h.name != null) h.send(m);
            }
        }

        /** Chat nhóm: chỉ thành viên của nhóm mới gửi và nhận được. */
        private void msgGroup(String text) {
            text = clean(text);
            if (text.isEmpty()) return;
            Group g = group;
            if (g == null) {
                error("Bạn chưa là thành viên của nhóm nào");
                return;
            }
            String m = "CHAT\tGROUP\t" + name + "\t" + g.name + "\t" + time() + "\t" + text;
            for (Handler h : g.members) h.send(m);
        }

        /** Chat riêng: gửi cho một user cụ thể (và báo lại cho người gửi). */
        private void msgPrivate(String to, String text) {
            text = clean(text);
            if (text.isEmpty()) return;
            Handler target = findUser(to);
            if (target == null) {
                error("'" + to + "' không còn online");
                return;
            }
            String m = "CHAT\tPRIVATE\t" + name + "\t" + target.name + "\t" + time() + "\t" + text;
            target.send(m);
            if (target != this) send(m);
        }

        private String clean(String s) {
            return s.replace('\t', ' ').trim();
        }

        private void cleanup() {
            synchronized (lock) {
                for (Group g : new ArrayList<>(groups.values())) {
                    if (g.requests.remove(this)) sendRequests(g);
                }
                removeFromGroup(this, null);
                clients.remove(this);
                broadcastLists();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // đã đóng
            }
            if (name != null) System.out.println("[-] " + name);
        }
    }
}
