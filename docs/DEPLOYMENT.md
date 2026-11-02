# Triển khai Windows và Ubuntu

## Cùng máy

| Node | Vai trò | Registry | Object cố định | CSDL |
|---|---|---|---|---|
| 1 | PRIMARY | 1099 | 1199 | banking_db1 |
| 2 | REPLICA | 1100 | 1200 | banking_db2 |
| 3 | REPLICA | 1101 | 1201 | banking_db3 |

Dùng `config/serverN.example.properties`. Mỗi process dùng một file `.local.properties`. JDBC URL/user/password có thể khác hoàn toàn giữa các node. Không dùng một biến môi trường SERVER_ID chung khi khởi động cả ba từ cùng shell.

## Ba máy trong LAN

| Node | Địa chỉ ví dụ | Vai trò | CSDL cục bộ |
|---|---|---|---|
| Windows 1 | 192.168.1.10 | PRIMARY | banking_db1 |
| Windows 2 | 192.168.1.11 | REPLICA | banking_db2 |
| Ubuntu VM | 192.168.1.12 | REPLICA | banking_db3 |

Dùng `serverN.lan.example.properties`: trên ba máy khác nhau đều có thể dùng registry 1099 và object 1199. Sửa IP thực tế trong `server.host`, `java.rmi.server.hostname` và mọi `node.N.host`. Sửa `node.N.port` nếu đổi registry. JDBC URL trỏ đến CSDL thuộc node đó, thường `127.0.0.1:3306`. Khóa `replication.secret` phải giống nhau và chỉ chia sẻ giữa các server. Không đưa mật khẩu thật vào file `.example.properties`.

Client dùng `config/client.lan.properties`, sửa cùng địa chỉ registry. Cổng object không có trong Client config: RMI stub chứa hostname/cổng đã export; vì vậy `java.rmi.server.hostname` phải là IP mà máy khác truy cập được, không để localhost trong mô hình LAN.

## Ubuntu VM headless

Đặt card mạng VM ở **Bridged Adapter** để VM cùng subnet với hai máy Windows, hoặc cấu hình routing/port forwarding tương đương cho cả registry và object. Gán IP ổn định/reservation DHCP. Kiểm tra `ip addr`, `ip route`; Windows dùng `Test-NetConnection 192.168.1.12 -Port 1099` sau khi server đã chạy. Ping có thể bị firewall chặn riêng.

```bash
sudo apt update
sudo apt install openjdk-17-jre-headless mysql-server
java -version
```

Tạo CSDL banking_db3 và JDBC user trong MySQL. Chép server JAR và `config/server3.local.properties` đã điền vào thư mục triển khai. Không cần Maven hoặc desktop trên VM.

```bash
export REPLICATION_SECRET='SAME_RANDOM_SECRET_AS_OTHER_NODES'
export JDBC_PASSWORD='YOUR_NODE3_DATABASE_PASSWORD'
java -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -jar server/target/server-1.0.0.jar config/server3.local.properties
```

Nếu chạy trong thư mục chỉ chứa JAR, thay đường dẫn tương ứng. Dừng bằng Ctrl+C hoặc SIGTERM để hook đóng runtime. Có thể dùng systemd với `WorkingDirectory`, `ExecStart`, `EnvironmentFile` và `Restart=on-failure`; không bootstrap lại mỗi khi service restart.

## Firewall

Chỉ cho phép IP các máy trong nhóm thực hành, điều chỉnh subnet theo mạng thực tế. Ví dụ trên Windows LAN:

```powershell
New-NetFirewallRule -DisplayName 'RMI Banking LAN' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 1099,1199 -RemoteAddress 192.168.1.0/24
```

Ubuntu:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 1099 proto tcp
sudo ufw allow from 192.168.1.0/24 to any port 1199 proto tcp
```

Nếu dùng cấu hình cùng máy trên các máy riêng, mở các cổng node tương ứng 1100/1200 và 1101/1201. Registry dùng cổng động sẽ không đủ; ứng dụng đã export object ở `rmi.service.port` cố định. Không cần mở MySQL 3306 ra LAN khi mỗi Java server truy cập CSDL cùng máy.

## Quản trị từ xa

Chạy Client trên Windows, chọn node Ubuntu, chọn vai trò Quản trị viên và đăng nhập admin. Bảng Tài khoản/Giao dịch đọc banking_db3 cục bộ qua endpoint RMI của node 3; session/quyền vẫn xác minh tại PRIMARY. Các thao tác khóa/mở tài khoản và Đồng bộ lại được chuyển về PRIMARY. Khởi động server đã dừng phải thực hiện trên máy chủ (launcher/systemd/terminal).

## Lỗi thường gặp

- `Connection refused`: kiểm tra process, registry port, địa chỉ peer và firewall.
- Lookup thành công nhưng gọi method treo/lỗi: kiểm tra hostname trong stub và object port 1199/1200/1201.
- `PRIMARY không hoạt động`: bật lại PRIMARY; không thay replica thành primary hoặc ghi trực tiếp vào replica.
- `GAP`: xem sequence local và log gửi bù; PRIMARY phải còn đầy đủ replication_events.
- `fingerprint sai`, `User đã tồn tại ngoài event log`, `sự kiện cũ không tương thích`: không sửa seq để bỏ qua; điều tra nhầm CSDL/cluster hoặc dữ liệu sửa tay. Khởi tạo replica mới bằng schema rỗng mới khi cần.
- Sai mật khẩu sau restart: phiên cũ không tồn tại; đăng nhập lại. Thay đổi mật khẩu JDBC qua file/biến môi trường không đổi mật khẩu khách hàng.

Mẫu cấu hình và hướng dẫn Ubuntu/LAN đã được chuẩn bị; phiên làm việc này chỉ có kiểm thử Windows/cùng máy, chưa chạy trên ba máy hoặc Ubuntu VM.
