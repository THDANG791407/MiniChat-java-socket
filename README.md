# MiniChat-java-socket
Đồ án minichat văn bằng 2 đại học Trà Vinh 
# 🚀 Mini-Chat Java Socket Application

Ứng dụng nhắn tin thời gian thực (Real-time Chat) được phát triển bằng ngôn ngữ **Java** theo mô hình **Client-Server**, sử dụng **TCP/IP Socket** thuần túy kết hợp giao diện đồ họa **Java Swing**. Dự án được xây dựng và tối ưu hóa trên môi trường **Eclipse IDE**.

---

## ✨ Các Tính năng Nổi Bật

* **Đa luồng đồng thời (Multithreading):** Server hỗ trợ quản lý và phục vụ đồng thời nhiều Client kết nối song song an toàn luồng (`Thread Safety` với `synchronized`).
* **Ba chế độ hội thoại linh hoạt:**
  * 💬 **Chat Chung (All Chat):** Phát sóng tin nhắn công khai tới toàn bộ người dùng đang online.
  * 👥 **Chat Nhóm (Group Chat):** Trao đổi thông điệp nội bộ giữa các thành viên trong cùng một phòng nhóm.
  * 🔒 **Chat Riêng tư (Private Chat):** Gửi tin nhắn bảo mật trực tiếp giữa hai cá nhân.
* **Hệ thống Phân quyền Quản lý Nhóm:** Phân chia cấp bậc rõ ràng gồm Trưởng nhóm (`L`), Phó nhóm (`D`) và Thành viên (`M`) với đầy đủ các nghiệp vụ:
  * Tạo nhóm mới, gửi yêu cầu tham gia (`Request Join`), chấp nhận/từ chối thành viên.
  * Mời người dùng online vào nhóm, bổ nhiệm/bãi nhiệm phó nhóm.
  * Chuyển giao quyền trưởng nhóm (`Transfer`) hoặc mời thành viên ra khỏi nhóm (`Kick`).
* **Giao diện Hiện đại (Modern UI):** Sử dụng Java Swing tùy chỉnh các thành phần bo tròn góc (`RoundedPanel`, `RoundedButton`, `RoundedField`) cùng thanh cuộn mảnh tùy biến.

---

## 📂 Cấu Trúc Mã Nguồn (Project Structure)

Mã nguồn được tổ chức trong package `minichat`:
```text
minichat/
│
├── Server.java       # Tiến trình Server: Lắng nghe kết nối, quản lý client và phân phối tin nhắn
├── Client.java       # Điểm khởi chạy ứng dụng phía Client và màn hình đăng nhập (LoginFrame)
├── ChatFrame.java    # Cửa sổ giao diện chat chính, xử lý logic hiển thị và điều hướng hội thoại
└── Ui.java           # Thư viện tiện ích giao diện (Bảng màu, nút bấm, panel bo tròn, scrollbar)
```

---

## ⚙️ Hướng Dẫn Chạy Dự Án (How to Run)

### 📋 Yêu cầu hệ thống
* Đã cài đặt **Java JDK 17** (hoặc các phiên bản tương đương trở lên).
* Công cụ lập trình **Eclipse IDE**.

### Bước 1: Clone hoặc Import dự án vào Eclipse
1. Mở **Eclipse IDE**, chọn `File` -> `Import` -> `Existing Projects into Workspace`.
2. Chọn thư mục chứa mã nguồn dự án `minichat` và nhấn **Finish**.

### Bước 2: Khởi chạy Server (`Server.java`)
1. Mở tệp `Server.java` trong Eclipse.
2. Nhấn chuột phải chọn `Run As` -> `Java Application` (hoặc nhấn tổ hợp phím `Alt + Shift + X, J`).
3. Kiểm tra Console hiển thị thông báo: `MiniChat server đang chờ kết nối tại cổng 5000` là thành công.

### Bước 3: Khởi chạy Client (`Client.java`)
1. Mở tệp `Client.java`.
2. Nhấn `Run As` -> `Java Application` để mở cửa sổ Đăng nhập (`LoginFrame`).
3. Nhập thông tin cấu hình:
   * **Server:** `localhost` (hoặc địa chỉ IP của máy chủ nếu chạy trên mạng LAN).
   * **Port:** `5000` (hoặc cổng mặc định đã cấu hình).
   * **Tên của bạn:** Nhập định danh cá nhân (VD: `UserA`).
4. Nhấn nút **Kết nối** để mở cửa sổ chat chính (`ChatFrame`). *Bạn có thể chạy nhiều Client khác nhau để giả lập chat đa người dùng.*

