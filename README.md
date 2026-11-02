# RMI Banking

Ứng dụng Java 17/Swing cho đề tài **đồng bộ 3 CSDL trên 3 server bằng Java RMI**. Client và server trao đổi qua RMI; PRIMARY commit nghiệp vụ và outbox trong một transaction JDBC. Hai REPLICA nhận sự kiện theo thứ tự và đồng bộ bù sau khi mất kết nối.

## Thành phần

| Thư mục | Nội dung |
|---|---|
| `common` | Remote interfaces, DTO công khai và DTO đồng bộ nội bộ, timeout socket |
| `server` | JDBC/HikariCP, BCrypt, phiên đăng nhập, outbox, worker, launcher Swing và headless |
| `client` | FlatLaf/MigLayout: đăng nhập, đăng ký, tổng quan, chuyển khoản, lịch sử, quản trị từ xa; CLI chẩn đoán |
| `database` | SQL riêng cho 3 node, không bật replication của MySQL |
| `config` | Mẫu cấu hình chạy cùng máy và trên LAN |
| `docs` | Kiến trúc, triển khai Windows/Ubuntu, demo, kết quả và ảnh chụp ứng dụng thật |

## Chạy nhanh trên một máy

Cần JDK 17, Maven 3.9+, MySQL 8.0.16+ (hoặc MariaDB tương thích). Lệnh dưới đây chạy từ thư mục chứa `pom.xml` gốc. H2 chỉ có trong dependency kiểm thử, ứng dụng thực tế dùng MySQL JDBC.

```powershell
mvn clean verify
Copy-Item config/server1.example.properties config/server1.local.properties
Copy-Item config/server2.example.properties config/server2.local.properties
Copy-Item config/server3.example.properties config/server3.local.properties
```

Sửa ba file `.local.properties`: `jdbc.user`, `jdbc.password`, và `replication.secret` (cùng khóa ngẫu nhiên ít nhất 24 ký tự trên cả ba node). Các file này đã được `.gitignore` loại trừ. File `client.properties` không chứa mật khẩu. Biến môi trường thay thế khóa cấu hình bằng cách viết HOA và thay dấu chấm thành `_`, ví dụ `JDBC_PASSWORD`, `REPLICATION_SECRET`, `JAVA_RMI_SERVER_HOSTNAME`.

Trong MySQL shell, tạo ba CSDL và tài khoản JDBC. Thay mật khẩu mẫu trước khi chạy:

```sql
CREATE DATABASE banking_db1 CHARACTER SET utf8mb4;
CREATE DATABASE banking_db2 CHARACTER SET utf8mb4;
CREATE DATABASE banking_db3 CHARACTER SET utf8mb4;
CREATE USER 'banking'@'localhost' IDENTIFIED BY 'YOUR_DATABASE_PASSWORD';
GRANT ALL PRIVILEGES ON banking_db1.* TO 'banking'@'localhost';
GRANT ALL PRIVILEGES ON banking_db2.* TO 'banking'@'localhost';
GRANT ALL PRIVILEGES ON banking_db3.* TO 'banking'@'localhost';
```

Server tự tạo bảng/index khi kết nối CSDL rỗng; hoặc nhập `database/node1.sql`, `node2.sql`, `node3.sql` một lần vào các CSDL mới. Không chép dữ liệu demo thủ công sang replica.

Khởi tạo demo **một lần, tại PRIMARY, trước khi chạy server**:

```powershell
$env:DEMO_ADMIN_PASSWORD = 'YOUR_ADMIN_PASSWORD_AT_LEAST_8_CHARS'
$env:DEMO_CUSTOMER_PASSWORD = 'YOUR_CUSTOMER_PASSWORD_AT_LEAST_8_CHARS'
java -cp server/target/server-1.0.0.jar vn.rmibank.server.Bootstrap config/server1.local.properties
Remove-Item Env:DEMO_ADMIN_PASSWORD, Env:DEMO_CUSTOMER_PASSWORD
```

Bootstrap băm BCrypt, tạo admin và bốn khách hàng trong cùng transaction, phát duy nhất event sequence 1. Tất cả replica bắt đầu từ CSDL rỗng và nhận dữ liệu này qua RMI. Mật khẩu không có trong Java/SQL. Tài khoản đăng ký mới luôn có số dư 0.

| Username | Vai trò | Số tài khoản | Số dư demo |
|---|---|---|---|
| `admin` | ADMIN | Không có | — |
| `kyvy` | CUSTOMER | 100001 | 1.000.000 đ |
| `mylinh` | CUSTOMER | 100002 | 1.000.000 đ |
| `minhanh` | CUSTOMER | 100003 | 1.000.000 đ |
| `hoangnam` | CUSTOMER | 100004 | 1.000.000 đ |

Mở ba terminal, mỗi terminal chạy một lệnh:

```powershell
java -jar server/target/server-1.0.0.jar config/server1.local.properties
java -jar server/target/server-1.0.0.jar config/server2.local.properties
java -jar server/target/server-1.0.0.jar config/server3.local.properties
```

Hoặc dùng launcher có nút khởi động/dừng trên Windows:

```powershell
java -jar server/target/server-1.0.0.jar config/server1.local.properties --gui
```

Khởi động Client:

```powershell
java -jar client/target/client-1.0.0.jar config/client.properties
```

Đăng nhập `kyvy`, chọn bất kỳ server nào để giao dịch. Chọn **Quản trị viên**, đăng nhập `admin` để xem CSDL cục bộ của server được chọn. Có thể mở nhiều Client, mỗi Client quản trị một server, kể cả node Ubuntu headless. Trong ứng dụng, nhập số tiền nguyên, ví dụ `200000`.

Các JAR trên đã đóng gói dependency. Dừng headless bằng Ctrl+C; shutdown hook đóng worker, remote object, registry và pool. Không chạy hai PRIMARY trên cùng CSDL.

## Kiểm thử

```powershell
# 13 ca kiểm thử tự động, SQL thật trong H2 chế độ MySQL + RMI registry/socket thật
mvn test

# Cùng 13 ca trên MySQL/MariaDB thực tế, 3 CSDL độc lập
$env:MYSQL_TEST_USER = 'root'
$env:MYSQL_TEST_PASSWORD = 'YOUR_TEST_DATABASE_PASSWORD'
mvn test '-Dbank.test.mysql=jdbc:mysql://127.0.0.1:3306/'
```

Tài khoản kiểm thử MySQL cần quyền tạo/xóa CSDL. Test chỉ tạo và xóa các schema riêng tên `rmi_test_<UUID>`; không sử dụng `banking_db1/2/3`. H2 dùng để chạy nhanh; lệnh thứ hai xác minh hành vi JDBC trên database thực tế.

Ngày 02/11/2026 đã chạy thành công 13/13 ca trên H2 và MariaDB 10.4.32 qua MySQL Connector/J, và chụp các màn hình từ ba node thực tế trên máy Windows. Chưa kiểm chứng trên MySQL 8 riêng biệt, Ubuntu VM hoặc mạng ba máy. Xem [kết quả kiểm thử](docs/TESTING.md).

## Tài liệu

- [Kiến trúc, schema và luồng giao dịch](docs/ARCHITECTURE.md)
- [Triển khai 3 máy Windows/Ubuntu, cổng và firewall](docs/DEPLOYMENT.md)
- [Kịch bản demo, mất kết nối và requestId](docs/DEMO.md)
- [Kết quả kiểm thử và giới hạn](docs/TESTING.md)
- [Ảnh đăng nhập](docs/screenshots/01-login.png), [đăng ký](docs/screenshots/02-register.png), [chuyển khoản](docs/screenshots/03-transfer.png), [quản trị](docs/screenshots/04-server-console.png)

Tại máy làm việc hiện tại, ba CSDL demo đã được tạo; cấu hình và mật khẩu demo ngẫu nhiên nằm trong các file **không đưa vào Git**: `config/server*.local.properties`, `config/demo.local.properties`. Có thể chạy lại server và Client bằng các lệnh trên. Khi mang dự án sang máy khác, thực hiện quy trình cấu hình và bootstrap từ đầu.

Giao diện SVG mới: xem [tích hợp FlatSVGIcon và ảnh chụp](docs/UI-SVG.md).
