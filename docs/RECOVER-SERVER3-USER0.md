# Phục hồi Server 3 bị chặn bởi user 0 trong sự kiện cũ

Đã đối chiếu PRIMARY ngày 2026-11-09: hai event schema 1 DIRECT_UPDATE sequence 30 và 31 chỉ chứa user ID 0 không tồn tại, không chứa account hoặc transfer. Log gốc được giữ nguyên. Sau khi phát lại nguyên trạng user 8 qua API RMI (sequence 52), replay bỏ đúng hai ảnh user lỗi khớp fingerprint toàn bộ users/accounts/transfers của PRIMARY:

`410abc9d6a987cdce84f69bee37f3dc6f2a1599d2471d4cb5d0d3795bbea39f0`

Backup đối chiếu và trước phát lại nằm trong `backups/server1/`. Không tự reset số dư, không xóa sự kiện, không tự sửa sequence/version/fingerprint.

1. Dừng process Server 3 bằng Ctrl+C. Sao chép JAR server mới từ `server/target/server-1.0.0.jar` trên Windows sang `~/rmi-banking/server/target/server-1.0.0.jar` trên Ubuntu.
2. Giữ cấu hình JDBC/IP/secret hiện có. Thêm dòng sau vào `~/rmi-banking/config/server3.local.properties`:

```properties
recovery.legacy-user-noop-digests=75ac95a8cdcc909c266d7f6bedc634323ea1ea771653c263b41719392a5208ae,0b483f18b8e1e8786bd6b3f5a8760b7540b58f1dd62dacff211797e96f18438a
```

3. Khởi động lại:

```bash
cd ~/rmi-banking
java -jar server/target/server-1.0.0.jar config/server3.local.properties
```

Bản mới tạo backup trước mỗi sự kiện được miễn, kiểm tra user 0 không tồn tại/được tham chiếu ở replica rồi ghi `[LEGACY_RECOVERY] ... action=PHANTOM_USER_NOOP`. Chỉ đúng event có SHA-256 trong cấu hình được xử lý như no-op; ID/event body/digest chống trùng vẫn giữ nguyên. Event khác, user thiếu ID dương, schema 2 hoặc event có account/transfer không được miễn.

4. Kiểm tra trên cả banking_db1 và banking_db3:

```sql
SELECT seq,fingerprint FROM node_state WHERE id=1;
SELECT id,user_id,balance,version,active FROM accounts ORDER BY id;
SELECT COUNT(*) AS transfer_count FROM transfers;
```

Chờ sequence theo kịp PRIMARY và fingerprint bằng nhau khi không còn giao dịch/SQL đang chờ. Mốc lúc đối chiếu: sequence 52, 8 users, 7 accounts, 15 transfers; giao dịch sau đó có thể làm các mốc thay đổi. Log PRIMARY phải có ACK sự kiện 30,31 rồi các sự kiện tiếp theo. Không thể kết luận chỉ từ thông báo server đang chạy. Hãy refresh phpMyAdmin/HeidiSQL.

Giữ dòng cấu hình trong các lần audit/repair lịch sử vì Maintenance cũng cần diễn giải đúng hai event cũ. Có thể thêm cùng dòng cho các node khác khi cập nhật JAR và cần phục hồi prefix lịch sử này. Bản thường không bật cơ chế miễn trừ nếu chưa được cấu hình.

Công cụ đối chiếu (chỉ đọc DB, tạo backup, không tự cho phép khi có chênh lệch):

```powershell
java -cp server/target/server-1.0.0.jar vn.rmibank.server.LegacyUserRecovery config/server1.local.properties
```

Cờ quản trị `--publish-current-user <id>` chỉ phát lại nguyên trạng user đã xác nhận qua API submitChange, kiểm tra before-image và tạo backup; không sửa trực tiếp user hoặc event log. Không cần gọi lại cho ca này.

Kiểm thử trên MariaDB local: 43 lượt, 0 lỗi, 0 bỏ qua; gồm trigger UPDATE, xung đột, restart/offline, phục hồi user 0 theo đúng digest, từ chối schema 2 và payload thay đổi, retry idempotent, transfer sau phục hồi. Chưa xác minh DB Ubuntu thực tế sau triển khai JAR.
