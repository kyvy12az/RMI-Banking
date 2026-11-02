# Kịch bản demo

Chuẩn bị theo README: bootstrap PRIMARY một lần, 3 CSDL riêng, mở ba cửa sổ quản trị bằng Client (admin chọn lần lượt Server 1, 2, 3). Để một Client khách hàng ở `kyvy`, chọn Server 2 để chứng minh forwarding qua RMI.

1. **Khởi động**: ba thẻ trạng thái trực tuyến, sequence bằng nhau. Trong ba cửa sổ admin xác nhận có 4 tài khoản demo, mỗi tài khoản 1.000.000 đ.
2. **Đăng ký**: ở Client khác đăng ký username mới, tên/số điện thoại hợp lệ, mật khẩu >=8 ký tự. Nhận số tài khoản tự cấp; kiểm tra account này xuất hiện ở cả ba bảng cục bộ, số dư 0. Không có chức năng khách hàng tự tăng số dư.
3. **Chuyển khoản**: kyvy gửi 200000 đến 100002, tra cứu đúng Võ Lê Mỹ Linh, xác nhận hộp thoại. Kết quả nguồn 800.000 đ, đích 1.200.000 đ. PRIMARY hiển thị COMMITTED; chi tiết đồng bộ GD thể hiện ACK riêng cho từng replica.
4. **Kiểm tra ba CSDL**: so tài khoản, số dư/version và cùng ID giao dịch trên cả ba console; sequence hội tụ sau vài giây.
5. **Offline**: dừng Server 3 bằng launcher hoặc Ctrl+C. Gửi thêm 50000. Giao dịch vẫn COMMITTED tại PRIMARY, nguồn 750.000 đ, đích 1.250.000 đ. Server 2 cập nhật; Server 3 offline và còn sự kiện chờ.
6. **Phục hồi**: bật lại Server 3 với chính CSDL cũ. Worker tự bắt kịp sau backoff tối đa 30 giây; hoặc dùng Đồng bộ lại. Không bootstrap lại. Xác minh số dư/version/GD hội tụ và pending về 0.
7. **Request trùng**: dùng CLI bên dưới gửi hai lần cùng ID và payload. Hai kết quả có cùng GD UUID; tiền chỉ trừ một lần. Đổi amount với cùng ID phải bị từ chối.
8. **Restart PRIMARY**: để Server 3 offline, gửi thêm một GD; dừng PRIMARY, khởi động lại với cùng CSDL. Đăng nhập lại admin/khách hàng, bật Server 3, xem sự kiện cũ được gửi bù.
9. **Phân quyền**: chọn ADMIN nhưng dùng tài khoản kyvy sẽ bị từ chối. Admin khóa tài khoản kyvy, thử giao dịch bằng session đang có sẽ bị chặn. Mở khóa rồi đăng nhập lại.
10. **Mất PRIMARY**: dừng PRIMARY; gọi số dư/chuyển khoản qua Server 2 phải trả lỗi không thể thực hiện nghiệp vụ, không phát sinh giao dịch cục bộ tại replica.

## Lặp requestId qua CLI

Chạy từ repository. CLI dùng chính Api.Bank RMI, không truy cập JDBC và không log token.

```powershell
$env:RMI_BANK_PASSWORD = 'YOUR_CUSTOMER_PASSWORD'
$requestId = [Guid]::NewGuid().ToString()
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 2 kyvy transfer 100002 200000 $requestId 'Demo idempotency'
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 3 kyvy transfer 100002 200000 $requestId 'Demo idempotency'
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 1 kyvy lookup $requestId
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 2 kyvy me
Remove-Item Env:RMI_BANK_PASSWORD
```

Có thể không đặt biến mật khẩu nếu chạy trong terminal hỗ trợ `System.console()`: CLI hỏi mật khẩu và không hiển thị ký tự nhập. Với admin:

```powershell
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 3 admin snapshot
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 1 admin resync
```

## SQL đối chiếu

Trên mỗi máy, thay tên CSDL tương ứng:

```sql
USE banking_db1;
SELECT id,user_id,balance,version,active FROM accounts ORDER BY id;
SELECT id,request_id,sender,recipient,amount,note,created_at,status FROM transfers ORDER BY created_at;
SELECT * FROM node_state;
-- PRIMARY:
SELECT seq,node_id,ack,attempts,next_attempt,last_error FROM replication_deliveries ORDER BY seq,node_id;
-- REPLICA:
SELECT seq,event_id FROM applied_events ORDER BY seq;
```

Chỉ đọc để đối chiếu, không cập nhật số dư/version/sequence bằng SQL. Xuất nhật ký qua console admin; log có UTC, eventId, peer và các dòng PRIMARY/RMI/ACK/RETRY/SYNC.
