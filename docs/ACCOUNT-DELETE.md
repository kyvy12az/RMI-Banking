# Đồng bộ DELETE tài khoản từ SQL trên ba node

## Hành vi

Sau khi cả ba server được nâng cấp, chạy trên bất kỳ database của node nào:

```sql
DELETE FROM accounts WHERE id = 100001 AND balance = 0;
```

Đây là xóa dòng thật khỏi `accounts`, không chỉ đổi `active`. Chỉ xóa tài khoản số dư 0; số dư khác 0 bị trigger từ chối. Không sửa số dư về 0 để né kiểm tra: xử lý tiền còn lại bằng nghiệp vụ hợp lệ trước. Tài khoản khóa có số dư 0 cũng xóa được.

`users` được giữ lại. Đăng nhập/phiên khách hàng của user không còn tài khoản sẽ bị từ chối. DELETE users/transfers và INSERT trực tiếp vẫn bị chặn. Không tự xóa bảng đồng bộ hoặc bảng lưu dấu xóa.

Trigger BEFORE DELETE lưu request UUID, node nguồn và ảnh dòng trước khi xóa; after_image là chuỗi JSON `null`. Queue và DELETE commit/rollback cùng transaction. Worker gửi đề xuất đến PRIMARY, đối chiếu trước-image/version, chỉ PRIMARY sắp thứ tự và xác nhận xóa. Replica được cập nhật qua event ACCOUNT_DELETE schema 3, có fingerprint, ACK và kiểm tra digest chống trùng như các sự kiện khác.

Đồng bộ là bất đồng bộ theo thứ tự, không phải một transaction commit tức thời trên cả ba database. Khi mất mạng, yêu cầu còn bền vững trong queue; dòng vật lý có thể được khôi phục tạm về ảnh confirmed trong lúc chờ. Sau kết nối/restart, worker gửi lại. Một câu DELETE nhiều dòng tạo một đề xuất cho mỗi dòng; PRIMARY kiểm tra từng dòng, có thể chấp nhận một số và từ chối các dòng đã xung đột.

Nếu có chuyển khoản hay UPDATE làm thay đổi dòng trước khi PRIMARY xác nhận xóa, yêu cầu DELETE bị CONFLICT, ảnh mới của PRIMARY được gửi về origin. Nếu DELETE thắng trước, chuyển khoản tới/từ tài khoản đã xóa bị từ chối. UPDATE cũ và DELETE trùng đang chờ trên node khác nhận CONFLICT cùng sự kiện xác nhận đã xóa; không làm sống lại tài khoản và không kẹt retry vì thiếu dòng.

## Lịch sử và số tài khoản

`account_keys` giữ số tài khoản vĩnh viễn. Các khóa ngoại sender/recipient của transfers tham chiếu bảng này để giữ lịch sử khi tài khoản không còn hoạt động. `deleted_accounts` giữ ảnh cuối với version tăng và active=false. Event log cũng giữ sự kiện xóa. Đăng ký cấp số mới dựa trên account_keys nên không dùng lại số đã xóa.

Khi mở database cũ, migration sao lưu trước khi thay khóa ngoại lịch sử, thêm ràng buộc đến account_keys trước rồi mới gỡ ràng buộc đến dòng accounts. Không xóa/chỉnh sửa transfers và không tắt FOREIGN_KEY_CHECKS. Migration có thể tiếp tục khi restart sau DDL dở dang. Tài khoản JDBC cần quyền CREATE/ALTER/INDEX/REFERENCES/TRIGGER và các quyền dữ liệu cần thiết trên schema; MySQL bật binary log cần cài trigger theo hướng dẫn DBA trong CHANGE-CAPTURE.md.

Bản cũ không đọc được event schema 3 và sẽ dừng áp dụng với lỗi unsupported schema; không chạy cụm trộn phiên bản khi sử dụng DELETE.

## Triển khai

1. Tạm dừng Client và SQL trong thời gian nâng cấp. Dừng các process Server 1, 2, 3.
2. Giữ bản sao lưu DB của từng node. Chép cùng `server/target/server-1.0.0.jar` mới sang cả ba máy, giữ properties/IP/secret/JDBC của mỗi node. Giữ dòng recovery.legacy-user-noop-digests nếu node đang dùng để đọc lịch sử cũ.
3. Khởi động PRIMARY trước, sau đó hai replica. Migration/trigger được cài khi mở node. Nếu báo quyền MySQL, dừng nâng cấp và sửa quyền trước khi dùng DELETE; không tự bỏ trigger hoặc sửa sequence.
4. Đợi các node theo kịp rồi thử trên tài khoản thử nghiệm số dư 0. Không có tài khoản thật nào bị xóa tự động khi khởi động/nâng cấp.

Windows tại thư mục dự án:

```powershell
java -jar server/target/server-1.0.0.jar config/server1.local.properties
# Trên máy bạn chạy Server 2:
java -jar server/target/server-1.0.0.jar config/server2.local.properties
```

Ubuntu:

```bash
cd ~/rmi-banking
java -jar server/target/server-1.0.0.jar config/server3.local.properties
```

## Theo dõi

```sql
SELECT request_id, origin_node, row_id, state, confirmed_seq, detail
FROM change_requests
WHERE table_name='accounts' AND after_image='null'
ORDER BY local_order DESC LIMIT 20;

SELECT seq,fingerprint FROM node_state WHERE id=1;
SELECT id,user_id,balance,version,active FROM accounts ORDER BY id;
SELECT id,user_id,balance,version,active FROM deleted_accounts ORDER BY id;
SELECT COUNT(*) AS transfer_count FROM transfers;
```

Chờ queue hết PENDING, ACK đủ và sequence/fingerprint bằng nhau khi cụm không có cập nhật đang chờ. Khi còn giao dịch phát sinh, so sánh ở cùng một thời điểm ổn định. `ACCEPTED` chỉ xác nhận PRIMARY commit; cần ACK replica để khẳng định cả cụm đã áp dụng. Log có request/node/account và lý do CONFLICT/REJECTED hoặc ACCOUNT_DELETE.

## Kết quả kiểm thử ngày 2026-11-10

MySQL 8.4.13 thật trong Docker riêng, ba schema/node RMI loopback; không dùng banking_db1/2/3 thực tế. Bộ hồi quy 49 lượt: 0 lỗi, 0 bỏ qua. Sau khi tăng kiểm tra before-image tại replica, chạy lại 7 lượt chuyên biệt DELETE: 0 lỗi, 0 bỏ qua. Build Maven verify thành công. Bao gồm xóa lần lượt ba node, không tái sử dụng số tài khoản, giữ lịch sử/khóa ngoại, rollback nhiều dòng, replica offline/restart có queue chờ, retry cùng UUID, UPDATE đến muộn, xung đột chuyển khoản, từ chối sự kiện xóa sai version/chủ sở hữu/số dư, migration khóa ngoại cũ; các ca đăng nhập/chuyển khoản cũ thuộc bộ hồi quy cũng đạt.

Lệnh bộ hồi quy: `mvn -B -ntp verify "-Dbank.test.mysql=jdbc:mysql://127.0.0.1:33306/"`.

Lệnh chuyên biệt: `mvn -B -ntp verify "-Dbank.test.mysql=jdbc:mysql://127.0.0.1:33306/" "-Dtest=BankIntegrationTest#deletionEventsRejectStaleVersionsOwnersAndNonzeroBalances,ChangeIntegrationTest#mysql*Delete*+mysqlMigrates*" "-Dsurefire.failIfNoSpecifiedTests=false"`.

Báo cáo tại results/account-delete-*-mysql.txt; SHA-256 JAR tại results/account-delete-artifacts.sha256. Đã xác nhận còn 0 schema rmi_test_ trước khi dọn container kiểm thử riêng. Chưa triển khai/xác minh DELETE trên ba database thật và máy Ubuntu; cần nâng cấp JAR toàn cụm theo hướng dẫn trên trước khi sử dụng.
