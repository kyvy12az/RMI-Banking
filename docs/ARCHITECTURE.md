
> Cập nhật 09/11/2026: xem [cơ chế UPDATE đa node/reset](CHANGE-CAPTURE.md) và [kết quả kiểm thử MySQL 8 mới](CHANGE-CAPTURE-TESTING.md). Nội dung bên dưới mô tả phiên bản trước nâng cấp.
# Kiến trúc và tính nhất quán

## Luồng RMI

```text
Swing / CLI ── Bank RMI ──► Server được chọn
                              │
                    nếu REPLICA: chuyển tiếp
                              ▼
                         PRIMARY BankService
                              │ JDBC transaction
                   users / accounts / transfers / outbox
                              │
                    worker RMI tuần tự theo peer
                       ┌──────┴──────┐
                       ▼             ▼
                  REPLICA 2     REPLICA 3
                  transaction   transaction
                  + applied     + applied
```

`Api.Bank` phục vụ Client, `Api.Replication` phục vụ nội bộ. Hai binding dùng cùng một remote object trên cổng cố định. `Net.Sockets` đặt connect/read timeout. Registry và object có hai cổng riêng.

PRIMARY xử lý đăng ký, đăng nhập, phiên, chuyển khoản, khóa/mở tài khoản, tra cứu người nhận, số dư và lịch sử. Replica chuyển tiếp các nghiệp vụ đó. Riêng `snapshot` đọc accounts/transfers/logs từ JDBC cục bộ sau khi xác minh quyền ADMIN tại PRIMARY. Vì thế có thể nhìn thấy replica chậm mà khách hàng vẫn đọc số dư mới nhất từ PRIMARY.

Không có tự bầu leader hoặc tự đổi vai trò. Khi PRIMARY không hoạt động, nghiệp vụ trả lỗi rõ ràng. Session lưu trong bộ nhớ PRIMARY, token ngẫu nhiên 256 bit, hết hạn theo `session.minutes`; restart PRIMARY yêu cầu đăng nhập lại. Mỗi nghiệp vụ kiểm tra quyền và trạng thái, khách hàng không truyền số tài khoản nguồn tùy ý.

## Ghi và cấp sequence

Mỗi transaction ghi trước tiên khóa `node_state WHERE id=1 FOR UPDATE`. Khóa này tuần tự hóa các commit nghiệp vụ và cấp sequence `seq + 1` trong cùng transaction; không dựa vào thứ tự cấp AUTO_INCREMENT. Ngoài ra chuyển khoản khóa lần lượt hai account theo ID tăng dần bằng `SELECT ... FOR UPDATE`, kiểm tra trạng thái và số dư sau khóa.

Transaction chuyển khoản gồm:

1. Xác định account nguồn từ session và kiểm tra requestId đã có.
2. Nếu cùng requestId và cùng sender/recipient/amount/note, trả giao dịch cũ. Dữ liệu khác bị từ chối.
3. Khóa hai tài khoản theo thứ tự, kiểm tra tài khoản nhận, số tiền nguyên và số dư.
4. Trừ nguồn, cộng đích, tăng version của cả hai.
5. Ghi `transfers`, `replication_events`, hai dòng `replication_deliveries`.
6. Cập nhật sequence và fingerprint, commit toàn bộ.

Tiền dùng `BigDecimal` và `DECIMAL(19,0)`, phù hợp VND nguyên. Giới hạn mỗi lần chuyển là 1.000.000.000 đ; constraint số dư không âm và unique request_id là lớp bảo vệ bổ sung. Nếu bất kỳ bước nào lỗi, rollback toàn bộ. Test có điểm gây lỗi sau khi trừ tiền để kiểm tra transaction thực.

Client tạo UUID trước khi gửi, giữ dữ liệu yêu cầu trong Preferences theo cluster/account khi chưa biết kết quả. Không lưu mật khẩu hoặc session. Lỗi mạng không được coi là thất bại nghiệp vụ: UI cho phép tra cứu `findRequest` và gửi lại cùng payload/requestId. Không cho sửa form của yêu cầu đang chưa rõ kết quả. Lỗi kỹ thuật tại server (kể cả JDBC báo lỗi khi commit) có cờ kết quả chưa xác định; Client giữ nguyên requestId để tra cứu/thử lại. Khi PRIMARY trả COMMITTED, UI cập nhật số dư/lịch sử; trạng thái này độc lập với ACK của replica.

## Outbox và gửi bù

Mọi event có eventId, sequence, type, timestamp UTC, schema version 1 và payload. Payload chứa **ảnh kết quả** của tài khoản/version và transfer đã commit; replica không thực thi lại nghiệp vụ trừ/cộng tiền.

Worker lấy heartbeat đã xác thực và sequence thực tế của mỗi replica, gửi tối đa 50 event liên tục mỗi lượt. Trước khi gửi tiếp, worker xác minh lại event tại sequence cuối cùng của replica; cluster khác có cùng số sequence nhưng khác event/digest bị từ chối. Nếu replica mới hoàn toàn, gửi lại từ event 1. Nếu mất ACK sau commit, heartbeat hoặc gửi trùng khôi phục trạng thái mà không áp dụng lại.

`apply` tại replica khóa node_state, kiểm tra schema, sequence tiếp theo, eventId và digest SHA-256. Event vượt khoảng trống bị từ chối; heartbeat sau đó khiến PRIMARY gửi từ sequence thiếu. Mỗi thay đổi account đang tồn tại phải có version cũ + 1. Thay đổi của hai account, transfer và `applied_events` commit nguyên tử; chỉ sau đó mới trả ACK. Replica không tạo outbox và không phát tiếp sự kiện.

ACK, số lần thử, thời điểm thử tiếp và lỗi từng peer lưu trong `replication_deliveries`. Khi offline, event log được giữ nguyên. Backoff tăng dần đến `retry.max.ms`; restart không mất outbox và có thể thử ngay để khôi phục kết nối. Nút Đồng bộ lại chỉ đánh thức worker gửi bù, không reset dữ liệu hay sequence. Heartbeat được thực hiện trong lượt worker, tối thiểu theo heartbeat.ms khi peer khỏe, giãn theo backoff khi lỗi.

## Schema

| Bảng | Ràng buộc và ý nghĩa |
|---|---|
| users | PK id, UNIQUE username; BCrypt hash; vai trò/trạng thái |
| accounts | PK id, UNIQUE user_id + FK users, DECIMAL balance >= 0, version |
| transfers | PK UUID, UNIQUE request_id, FK hai account, amount > 0, index account/time |
| node_state | sequence cuối và fingerprint dữ liệu nghiệp vụ; khóa tuần tự hóa ghi |
| replication_events | PK sequence, UNIQUE eventId, payload JSON nội bộ; giữ toàn bộ event log |
| replication_deliveries | PK (sequence,node), FK event; ACK riêng từng replica |
| applied_events | PK sequence, UNIQUE eventId, digest; chỉ replica sử dụng |
| sync_logs | nhật ký vận hành UTC, không chứa mật khẩu/token/khóa |

Thời gian lưu chuỗi ISO-8601 UTC cố định 9 chữ số phần giây, dài 30 ký tự, để sắp xếp/lọc từ điển chính xác. Date filter là ngày UTC, bao gồm ngày kết thúc thông qua cận trên ngày kế tiếp. Các bảng vận hành giữa PRIMARY/REPLICA khác nhau; dữ liệu users/accounts/transfers phải hội tụ.

Khi khởi động, server so fingerprint với nội dung users/accounts/transfers và kiểm tra event log liên tục tương ứng role. CSDL được seed thủ công hoặc thay đổi ngoài luồng sẽ bị báo không tương thích. Không tự ghi đè. Để tạo replica mới, dùng CSDL **rỗng mới** và cho PRIMARY replay toàn bộ event; không xóa/chỉnh dữ liệu cũ khi chưa điều tra.

## Giao diện và vận hành

SwingWorker chạy RMI/JDBC bên ngoài EDT; callback cập nhật Swing trên EDT. Timer 2,5 giây chỉ kích hoạt refresh khi lượt trước đã xong. Thay form không nằm trong refresh. Bảng giữ ID hàng chọn; log giữ vị trí con trỏ. File Logback UTF-8 được tách theo node trong `logs/banking-<id>.log`. Đăng xuất/đóng cửa sổ dừng timer và hủy tác dụng callback cũ bằng generation; các lời gọi mạng đang chạy kết thúc theo socket timeout.

Launcher khởi động/dừng runtime ngoài EDT. Headless có shutdown hook. Worker dừng trước khi unexport remote object/registry và đóng pool. Admin từ xa không có nút giả khởi động server đã tắt.

Đây là mô hình nhất quán cuối cùng cho bài tập trên LAN tin cậy: chỉ một PRIMARY, hai replica, giữ đầy đủ log, fingerprint tính trên toàn bộ dữ liệu khi commit. RMI không cung cấp distributed transaction; ba CSDL không commit đồng thời. Không có TLS transport, failover, rate limiting hay cơ chế quản lý secret cho triển khai ngân hàng thật.
