# Reset số dư một lần và đồng bộ UPDATE từ mọi node

## Kiến trúc sau nâng cấp

Server 1 vẫn là PRIMARY, hai server còn lại là REPLICA. Chỉ PRIMARY xác nhận thay đổi, cấp sequence và phát event. Không đổi vai trò tự động khi mất mạng.

Các thay đổi từ Java (chuyển khoản, khóa/mở, sửa số dư quản trị) đi qua PRIMARY và transaction/outbox như trước. UPDATE SQL trực tiếp trên dòng hiện có của `users` và `accounts` được trigger bắt ở **cả ba node**. Trigger lưu UUID, node nguồn, bảng/ID, ảnh trước/sau trong `change_requests`, cùng transaction với UPDATE. Một câu UPDATE nhiều dòng sinh một yêu cầu cho mỗi dòng; tất cả yêu cầu của câu SQL chỉ nhìn thấy sau khi transaction SQL commit.

Worker replica gửi yêu cầu về PRIMARY qua RMI nội bộ có xác thực. PRIMARY kiểm tra ảnh trước so với dữ liệu đã xác nhận, kiểm tra dữ liệu/ràng buộc, rồi chấp nhận hoặc ghi CONFLICT/REJECTED. Cả kết quả chấp nhận lẫn từ chối đều phát ảnh dữ liệu đã được PRIMARY xác nhận để các node hội tụ. Các dòng trong một câu SQL có thể có kết quả xung đột khác nhau; đây không phải distributed transaction cho toàn câu SQL. Giao dịch chuyển khoản vẫn cập nhật nguyên tử hai tài khoản và transfer bằng một event.

`confirmed_users` và `confirmed_accounts` lưu ảnh được PRIMARY xác nhận. UPDATE trực tiếp trên replica là đề xuất đang chờ, chưa phải số dư được phép dùng cho chuyển khoản. Trước khi Java ghi nghiệp vụ hoặc áp dụng event, transaction khóa dữ liệu, đưa ảnh vật lý về trạng thái xác nhận, giữ nguyên đề xuất trong hàng đợi rồi xử lý. Vì vậy trong lúc mất mạng, giá trị vừa sửa bằng SQL có thể quay về số dư đã xác nhận; đề xuất vẫn còn bền vững và sẽ được xét khi có mạng.

Tài khoản so sánh cả before-image và version; trigger tự tăng version cho UPDATE trực tiếp, PRIMARY là nơi quyết định version cuối. Không tự đặt version/fingerprint/sequence bằng SQL. User so sánh toàn bộ ảnh trước. Nếu hai node cùng sửa một dòng, yêu cầu phù hợp được PRIMARY xác nhận trước sẽ thắng; yêu cầu dựa trên ảnh cũ bị CONFLICT. Không ghi đè số dư mới phát sinh bởi chuyển khoản dựa trên một ảnh cũ.

`external_requests` lưu hash và kết quả theo requestId; gửi lại cùng request không phát thêm event, đổi payload với cùng ID bị từ chối. Retry/backoff và yêu cầu chờ lưu trong CSDL. Worker chạy trên mọi node, cả sau restart. Cờ `@rmibank_app_write` chỉ tồn tại trên connection nội bộ khi apply/ghi nghiệp vụ và được xóa khi trả connection về pool, ngăn trigger tự phát yêu cầu vòng lặp.

## Nâng cấp cụm đang có

1. Tạm dừng Client và thao tác SQL trực tiếp trong cửa sổ nâng cấp. Dừng các server cũ, không chạy hai bản trên cùng CSDL/cổng.
2. Build tại repository gốc: `mvn verify`. Chép **JAR server mới cho cả ba node** và Client JAR mới cho máy khách. Event mới có schema version 2; không chạy trộn server cũ và mới.
3. Giữ cấu hình ID/role/IP/JDBC/secret của từng máy. JDBC user phải có quyền DDL/INDEX/TRIGGER và SELECT/INSERT/UPDATE/DELETE trên schema của node đó. Không cần mở MySQL ra LAN khi Java và DB ở cùng máy.
4. Với dữ liệu hợp lệ, server tự tạo các bảng hỗ trợ, xác minh fingerprint rồi cài trigger; không tự reset số dư. Giữ toàn bộ event log cũ và bảng vận hành khi chuyển máy.
5. Nếu startup báo fingerprint sai, chạy quy trình audit/repair bên dưới. Khởi động PRIMARY trước; replica cần lấy prefix event từ PRIMARY để phục hồi khi cần.
6. Khi ba node chạy bản mới, kiểm tra bảng trigger/queue rồi gọi reset một lần qua CLI.

Các địa chỉ cấu hình tại thời điểm triển khai là PRIMARY `172.20.10.7:1099`, REPLICA 2 `172.20.10.5:1100`, REPLICA 3 `192.168.93.128:1101`. Phiên làm việc này không kết nối được ba địa chỉ này; cần kiểm tra lại IP/firewall/mạng VM trên các máy thực tế. Không dùng các CSDL local cũ thay thế tùy tiện cho cụm thật.

## Gọi reset 100.000 VNĐ đúng một lần

Sau khi triển khai, chạy tại thư mục dự án trên Windows:

```powershell
$requestId = [Guid]::NewGuid().ToString()
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 1 admin reset-balances --confirm-reset-100000 $requestId
```

Đăng xuất phiên admin đang mở trên giao diện trước khi chạy CLI, vì ứng dụng giữ quy tắc một phiên đăng nhập cho mỗi người dùng. CLI hỏi mật khẩu quản trị qua terminal. Nếu terminal không hỗ trợ System.console, đặt `RMI_BANK_PASSWORD` trong phiên shell rồi xóa biến sau khi chạy. Không đưa mật khẩu lên dòng lệnh hoặc commit vào repository.

Lệnh xác minh ADMIN tại PRIMARY, sao lưu ảnh CSDL vào `backups/server1/`, rồi trong một transaction đặt **mọi account hiện có, kể cả bị khóa** về 100000, tăng version, ghi event BALANCE_RESET, fingerprint, nhật ký và marker `admin_operations.operation_key=RESET_BALANCES_100000`. Không xóa user/account/transfer hay đổi trạng thái khóa. Backup JSON có SHA-256; có thể cấu hình đường dẫn bằng khóa `backup.dir` trong file properties.

Lệnh trả `ResetResult` gồm requestId, sequence, số account và đường dẫn backup. Đây là commit tại PRIMARY; chờ ACK của hai replica để xác nhận toàn cụm đã cập nhật.

Marker là một lần cho cả cụm, không chỉ cho một requestId: gọi lại cùng ID hoặc dùng ID mới cũng trả kết quả cũ, **không đặt lại số dư lần nữa**. Startup/đồng bộ/restart không chạy reset. Chuyển khoản và tài khoản đăng ký sau đó hoạt động bình thường; tài khoản mới vẫn có số dư ban đầu 0 theo nghiệp vụ gốc. Không xóa marker bằng SQL để ép chạy lại.

## Sửa số dư từ ứng dụng

Đăng nhập ADMIN, chọn một account trong bảng và dùng nút **Sửa số dư**. UI gửi số dư mới cùng version đang hiển thị đến PRIMARY. Nếu dữ liệu đã đổi, thao tác bị CONFLICT và bảng được làm mới.

Hoặc dùng CLI:

```powershell
$requestId = [Guid]::NewGuid().ToString()
# Ví dụ expectedVersion là 5; lấy đúng version đang có từ bảng quản trị.
java -cp client/target/client-1.0.0.jar vn.rmibank.client.BankCli config/client.properties 2 admin set-balance 100001 150000 5 $requestId
```

Nếu mất kết nối, giữ nguyên account/amount/expectedVersion/requestId khi thử lại. Server lưu kết quả idempotent. Khách hàng không được gọi thao tác quản trị này.

## SQL trực tiếp và quan sát kết quả

Sau khi cả ba node đã nâng cấp, thực hiện trên bất kỳ DB nào:

```sql
UPDATE accounts SET balance=150000 WHERE id=100001;
UPDATE accounts SET balance=balance+500 WHERE id IN (100001,100002);
UPDATE users SET full_name='Nguyễn Văn A',phone='0912345678' WHERE id=2;
```

Phạm vi: dữ liệu trên dòng users/accounts đang tồn tại, giữ nguyên id. Các ràng buộc username duy nhất, chủ sở hữu hợp lệ, số dư nguyên không âm, phone hợp lệ, role hợp lệ và password_hash phải là BCrypt vẫn được kiểm tra. `version` là metadata do hệ thống quản lý. Chuỗi đổi tên hoặc đổi chủ tài khoản có thể gửi qua queue; mỗi bước vẫn phải hợp lệ và sẽ bị từ chối nếu trước đó có thay đổi xung đột.

Không INSERT/DELETE trực tiếp hoặc UPDATE lịch sử transfers; trigger bảo vệ các thao tác đó. Đăng ký và chuyển khoản phải dùng ứng dụng. Không SET cờ nội bộ, tắt trigger, sửa bảng confirmed hoặc sửa metadata đồng bộ.

Chạy các truy vấn trong `database/verify-cluster.sql` trên từng máy. Đợi PENDING về 0, các sequence bằng nhau và deliveries được ACK. Nhật ký có node nguồn, requestId, bảng/ID, trạng thái và nguyên nhân cụ thể. Nếu có deadlock từ một transaction SQL ngoài ứng dụng, MySQL rollback transaction đó; kiểm tra lỗi client SQL và gửi lại transaction. Transaction Java có retry deadlock giới hạn, không commit nửa giao dịch.

## Fingerprint sai: audit, backup, plan rồi mới repair

Dừng node cần phục hồi và tạm dừng mọi SQL trên DB đó. Runtime mới giữ named lock theo database; Maintenance từ chối chạy nếu node mới vẫn hoạt động. Với bản server cũ chưa có named lock, phải dừng process cũ bằng launcher/Ctrl+C trước.

Trên PRIMARY, audit đọc event log local; trên REPLICA, audit lấy prefix log đến sequence của node từ PRIMARY qua RMI nội bộ. Khóa xác thực phải khớp và PRIMARY mới phải đang chạy khi audit replica.

```powershell
java -cp server/target/server-1.0.0.jar vn.rmibank.server.Maintenance audit config/server1.local.properties backups/plan-node1.json
```

Trên Ubuntu dùng cùng lớp, thay file cấu hình thành server3.local.properties và đường dẫn JAR thực tế.

Mỗi lần audit cần một tên file plan mới; không ghi đè bản đối chiếu cũ. Audit luôn tạo backup JSON trước, đối chiếu dòng thực tế với replay event log và ghi plan chứa hash, sequence, các dòng khác và blocker. Đọc các dòng DIFF và blocker được in, đối chiếu dữ liệu trước khi áp dụng. File plan/backup chứa dữ liệu nội bộ và BCrypt hash, cần giữ riêng; thư mục backups đã được gitignore.

Nếu blocker rỗng, dùng SHA-256 mà audit vừa in để gọi rõ ràng:

```powershell
java -cp server/target/server-1.0.0.jar vn.rmibank.server.Maintenance repair config/server1.local.properties backups/plan-node1.json --apply SHA256_CUA_PLAN
```

Repair kiểm tra hash và đúng DB/node/role; nếu DB đã đổi từ lúc audit thì từ chối và yêu cầu audit lại. Nó tạo thêm backup trước repair, phục hồi ảnh đã xác nhận, cập nhật shadow/fingerprint/ledger cần thiết, **giữ các thay đổi users/accounts chưa ghi nhận thành đề xuất PENDING** để PRIMARY xét, thay vì âm thầm bỏ chúng. Khóa chính/user/account/transfer không bị xóa để seed lại. Không tự gọi reset trong repair.

Nếu số dòng/khóa chính khác event log, hoặc transfers khác log, plan có blocker và công cụ không tự ghi đè. Cần đối chiếu thủ công nguồn dữ liệu/lịch sử trước khi tiếp tục; không có cờ bỏ qua fingerprint. Không xóa replication_events/applied_events để chữa lỗi.

Sau repair, chạy lại node, đợi xử lý các đề xuất chờ, rồi mới gọi lệnh reset 100000 ở trên. Backup JSON phục vụ đối chiếu/phục hồi có kiểm soát, không phải một script SQL để import mù vào cụm đang chạy.
Kết quả kiểm thử MySQL 8.4.13: xem [báo cáo mới](CHANGE-CAPTURE-TESTING.md). Bảng direct_update_queue của phiên bản cũ chỉ được giữ để đối chiếu khi nâng cấp; hàng đợi hoạt động mới là change_requests.

## Quyền cài trigger trên MySQL 8

Với JDBC user không phải root, cần quyền TRIGGER cùng các quyền trên schema đã nêu. Nếu MySQL bật binary logging và báo ERROR 1419 khi CREATE TRIGGER, DBA kiểm tra `log_bin_trust_function_creators`. Trong cửa sổ cài đặt có thể cho phép tạo trigger bằng:

```sql
SHOW VARIABLES LIKE 'log_bin_trust_function_creators';
SET GLOBAL log_bin_trust_function_creators = 1;
```

Ghi lại giá trị cũ; sau khi các node đã cài trigger thành công, DBA có thể khôi phục giá trị đó. Server giữ các trigger hợp lệ qua restart, không xóa/tạo lại chỉ vì khởi động. Không cần cấp SUPER cho JDBC user chạy thường ngày. Tham khảo [MySQL 8.4: stored program binary logging](https://dev.mysql.com/doc/refman/8.4/en/stored-programs-logging.html).

Idempotency áp dụng cho một requestId đã được queue ghi nhận: retry worker/RMI giữ ID đó. Gửi mới một câu SQL như `balance=balance+500` là một transaction mới và có ID mới; khi chưa rõ SQL đã commit hay chưa, đối chiếu queue trước khi gửi lại câu SQL tương đối.