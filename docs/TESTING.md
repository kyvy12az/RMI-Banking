# Kết quả kiểm thử thực tế

Ngày chạy: **02/11/2026**, Windows, JDK 17, Maven 3.9.11. Không dùng mock số dư hoặc dịch vụ HTTP.

| Lượt xác minh | Kết quả thực tế |
|---|---|
| `mvn clean verify` (sau format Java) | BUILD SUCCESS, cả common/server/client; 13 test, 0 lỗi, 0 bỏ qua |
| `mvn test '-Dbank.test.mysql=jdbc:mysql://127.0.0.1:3306/'` | BUILD SUCCESS, 13 test, 0 lỗi, 0 bỏ qua; backend MariaDB 10.4.32, MySQL Connector/J 9.1.0 |
| Ba tiến trình Java riêng + banking_db1/2/3 | Commit, forwarding, duplicate request và gửi bù qua RMI thành công |
| Dừng replica 3, ghi tiếp, dừng/restart PRIMARY rồi bật replica | Event chờ còn trong CSDL và được gửi bù; ba node hội tụ sequence 3 |
| Swing `UiSmoke` trên các server thực | `UI_SMOKE_OK`, 5 ảnh chụp gồm đăng nhập, đăng ký, chuyển khoản, admin và admin ở 1040×750 |

Các SQL test nhanh dùng H2 2.3.232 chế độ MySQL. Lượt MariaDB chạy chính cùng bộ test bằng JDBC thực và các schema `rmi_test_<UUID>` riêng; toàn bộ schema test đã được dọn khi kết thúc. Test RMI tạo registry và export remote object thật qua loopback, có forwarding từ replica về PRIMARY.

## 13 ca tự động

| Test | Nội dung |
|---|---|
| successfulTransferCommitsOutbox | Chuyển 200.000 đ, hai số dư, sequence/outbox/deliveries |
| insufficientFunds | Không đủ tiền không phát sinh sự kiện |
| recipientMissing | Từ chối người nhận không tồn tại |
| selfTransfer | Từ chối tự chuyển |
| invalidMoney | Từ chối 0, âm, phần lẻ và vượt 1 tỷ |
| duplicateRequestAndMismatch | Trả đúng kết quả cũ, không trừ hai lần; đổi payload bị từ chối; người khác không tra cứu request của chủ nguồn |
| concurrentTransfersCannotOverdraw | Hai thread chuyển đồng thời 800.000 đ, chỉ một thành công, số dư không âm |
| errorAfterDebitRollsBack | Gây lỗi thật giữa debit và credit; tiền, transfer, sequence đều rollback |
| permissionsSessionsAndLocking | Chặn customer gọi admin, sai vai trò, tài khoản khóa, token logout/hết hạn |
| registerNormalizesAndReplicatesZeroBalance | Chuẩn hóa username, chống trùng, validate phone, số dư đăng ký bằng 0, tạo outbox |
| duplicateAndOutOfOrderReplication | Chặn gap, không áp dụng trùng, chặn cùng seq khác event, so fingerprint dữ liệu |
| restartKeepsOutboxAndDetectsTampering | Đóng/mở lại pool/service store cùng database, pending không mất, từ chối DB bị sửa ngoài luồng |
| realRmiOfflineRecoveryAndForwarding | Registry/socket RMI thật với 3 store; replica offline/online, hội tụ, khóa nội bộ sai và PRIMARY offline |

Lượt MySQL-compatible cuối: `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0`, thời gian test 7,999 giây; Maven hoàn thành 18:24:08 +07:00. Báo cáo gốc nằm tại `server/target/surefire-reports/` sau khi chạy lại; bản tóm tắt được lưu ở `docs/results/mariadb.txt` và `docs/results/h2.txt`.

## Thử ba JVM riêng

Khởi tạo 3 CSDL demo mới và bootstrap đúng một event qua PRIMARY. Sau khi ba node online:

1. Gửi qua Server 2: request `ebfe8349-b531-497e-b5fa-2f81ddb4af8a`, từ 100001 đến 100002, 200000 đ.
2. Gửi lại cùng payload qua Server 3: cùng kết quả `GD-31be9490-258a-4d3b-9dde-361a5dcc7a4e`, tổng transfers vẫn 1; số dư 800000 và 1200000, version 2.
3. Dừng tiến trình Server 3. Gửi 50000 đ qua Server 2, request `8b1e6155-42cf-4b15-bbe8-4174f4c64cb9`, trả `COMMITTED`, GD `GD-06834083-3f91-479f-a377-fc7cf473d2b8`. Replica 3 còn số dư cũ và delivery sequence 3 chưa ACK.
4. Dừng tiến trình PRIMARY, khởi động lại với cùng CSDL, sau đó bật Server 3. Không bootstrap lại.
5. Truy vấn cả 3 node đều có sequence **3**, cùng fingerprint:

```text
2f6d1f1816c9e74327beec25dbd6b789b2b1a63eb56b34eafab2f90410a14c3e
```

Tài khoản 100001 = **750000**, 100002 = **1250000**, cả hai version **3**. Cả 6 dòng deliveries (3 events × 2 peers) đều ACK. Hai tài khoản demo còn lại vẫn 1000000. Đây cũng là trạng thái CSDL demo local được giữ lại để xem; README mô tả số dư ban đầu khi bootstrap trên máy mới.

## Kiểm tra giao diện

Ảnh trong `docs/screenshots` được vẽ từ cửa sổ Swing chạy thật, đăng nhập bằng API RMI và lấy số dư/snapshot từ ba CSDL. Bộ smoke không giả lập dữ liệu. Đã sửa cắt nội dung bằng layout co giãn và viewport cuộn; đăng ký chia hai cột, menu đánh dấu mục đang chọn, icon vẽ vector, bảng rỗng có trạng thái rõ ràng. Ở cửa sổ thấp, cuộn phần nội dung để xem các hàng và nút phía dưới.

Chạy lại smoke (cần desktop, đã build, ba server đang chạy):

```powershell
$env:UI_CUSTOMER_PASSWORD = 'YOUR_CUSTOMER_PASSWORD'
$env:UI_ADMIN_PASSWORD = 'YOUR_ADMIN_PASSWORD'
java '-Dfile.encoding=UTF-8' -cp 'client/target/test-classes;client/target/client-1.0.0.jar' vn.rmibank.client.UiSmoke config/client.properties docs/screenshots
Remove-Item Env:UI_CUSTOMER_PASSWORD, Env:UI_ADMIN_PASSWORD
```

## Chưa kiểm chứng

- MySQL 8 riêng biệt: máy có MariaDB 10.4.32 của XAMPP, không có MySQL 8 đang chạy.
- Ubuntu VM, Bridged và mạng ba máy vật lý: đã có cấu hình/lệnh hướng dẫn, chưa có môi trường để chạy thực.
- Không có kiểm thử thao tác chuột/bàn phím tự động toàn bộ UI, firewall thực, rút cáp mạng hoặc crash đúng thời điểm mất ACK. Các bất biến SQL/RMI đã được kiểm tra ở các ca trên; smoke chỉ kiểm tra mở/vẽ cửa sổ và tải dữ liệu.

Ứng dụng phù hợp quy mô bài tập. Session không bền qua restart PRIMARY, fingerprint toàn bộ bảng và lịch sử admin giới hạn 1000 giao dịch/200 dòng log; lịch sử khách hàng có tìm kiếm, lọc ngày và phân trang trên server. Không có failover tự động hay cam kết commit đồng thời ba CSDL.
