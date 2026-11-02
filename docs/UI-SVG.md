# Giao diện Swing và SVG

Đã cập nhật giao diện ngày 02/11/2026 tại `D:\Project-LTM\RMI-Banking\RMI-Banking`.

## Thay đổi

- Dùng `FlatSVGIcon` từ `flatlaf-extras` 3.5.4, cùng phiên bản FlatLaf đang có.
- Giữ nguyên 27 SVG tại `client/src/main/resources/icons/`. Maven đóng gói nguồn SVG này vào common; Client và Server cùng nhận tài nguyên qua dependency common. Không sao chép bộ icon thứ hai và không cần phụ thuộc Server vào Client.
- `BankIcons` quản lý tên icon, ánh xạ chức năng và đổi màu bằng `FlatSVGIcon.ColorFilter`. SVG `currentColor` dùng được trên nền trắng, navy và nút teal. Không còn lớp Glyph tự vẽ icon.
- `BankTheme` dùng chung palette navy–teal, font có kiểm tra hỗ trợ tiếng Việt, màu focus/hover, bảng, tab và hộp thoại cho Client và launcher Server.
- Đăng nhập/đăng ký có icon tiêu đề, chọn vai trò, trạng thái kết nối và hộp thông tin. Nút mật khẩu đổi eye/eye-off cùng trạng thái ẩn/hiện.
- Sidebar có icon đúng chức năng và nền đánh dấu mục đang chọn; trang khách hàng có avatar SVG, số tài khoản riêng dòng, số dư nổi bật, tiền vào/ra phân biệt màu, trạng thái COMMITTED được trình bày thành Thành công. Dữ liệu model gốc vẫn giữ nguyên.
- Trang quản trị có tab SVG, trạng thái online/offline và icon tìm kiếm, cấu hình, nhật ký, đồng bộ.
- Launcher Server có thông tin node/Registry/service port, tiến trình loading và nút khởi động/dừng ghim phía dưới; nội dung có thể cuộn khi cửa sổ nhỏ.
- Bố cục vẫn dùng MigLayout/BorderLayout, có khoảng cách, viền bo góc và viewport co giãn. Thông báo dài tự xuống dòng.

Không đổi remote interfaces, DTO nghiệp vụ, câu SQL, schema, thuật toán chuyển khoản, xác thực/session hoặc cơ chế outbox/RMI. `ServerMain` chỉ gọi theme trong nhánh mở launcher GUI; chế độ headless giữ nguyên. Không dừng hay khởi động lại server đang được người dùng chạy để thử giao diện.

## Các tệp chính

- `common/src/main/java/vn/rmibank/common/ui/BankIcons.java`
- `common/src/main/java/vn/rmibank/common/ui/BankTheme.java`
- `client/src/main/java/vn/rmibank/client/Ui.java`
- `client/src/main/java/vn/rmibank/client/ClientMain.java`
- `server/src/main/java/vn/rmibank/server/Launcher.java`
- `common/pom.xml`, `client/pom.xml`: dependency và đóng gói tài nguyên dùng chung.

## Xác minh thực tế

- `mvn -B -ntp verify`: **BUILD SUCCESS**, common/server/client đều thành công.
- Bộ kiểm thử nghiệp vụ hiện có: **13 tests, 0 failures, 0 errors, 0 skipped**.
- Kiểm tra hai JAR: mỗi JAR chứa đúng 27 SVG trùng danh sách nguồn. Cả 27 icon nạp và vẽ được ở ba màu navy/teal/trắng, không rỗng và không sai màu ngoài sai số anti-alias ở viền.
- `UiSmoke`: **UI_SMOKE_OK**, mở và chụp đăng nhập, đăng ký, chuyển khoản, quản trị ở kích thước thường và 1040×750. Màn hình nghiệp vụ đọc dữ liệu thật từ dịch vụ RMI đang chạy; trạng thái replica offline trên ảnh là trạng thái thực tế tại lúc chụp.
- `LauncherVisualCheck`: **LAUNCHER_VISUAL_OK**, mở và chụp launcher ở trạng thái dừng; không nhấn khởi động/dừng và không thay đổi CSDL.

Ảnh mới giữ riêng tại `docs/screenshots/svg/`:

1. [Đăng nhập](screenshots/svg/01-login.png)
2. [Đăng ký](screenshots/svg/02-register.png)
3. [Chuyển khoản](screenshots/svg/03-transfer.png)
4. [Quản trị Server](screenshots/svg/04-server-console.png)
5. [Quản trị khi thu nhỏ](screenshots/svg/05-server-compact.png)
6. [Launcher Server](screenshots/svg/06-server-launcher.png)

## Build và chạy

Chạy từ thư mục chứa pom.xml gốc để common đóng gói đúng nguồn SVG:

```powershell
mvn verify
java -jar client/target/client-1.0.0.jar config/client.properties
java -jar server/target/server-1.0.0.jar config/server1.local.properties --gui
```

Chỉ mở launcher tương ứng node cần quản lý; không khởi động thêm node cùng cổng với process đang chạy. Sau khi thêm/thay SVG trong thư mục nguồn, build lại toàn reactor để cập nhật cả hai JAR.