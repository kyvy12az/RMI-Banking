# Kiểm thử nâng cấp UPDATE đa node và reset số dư

Ngày thực hiện: 09/11/2026. Java 17, Maven reactor common/server/client.

## Kết quả thực tế

| Môi trường | Kết quả |
|---|---|
| MySQL 8.4.13 trong Docker, cổng localhost 33306 | 41 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS |
| MariaDB 10.4.32 local, MySQL Connector/J | Lượt đầu 39 tests, 0 failures, 0 errors, 0 skipped |
| H2 chế độ MySQL, build mặc định | 41 tests, 0 failures/errors; 13 ca yêu cầu trigger MySQL được bỏ qua đúng điều kiện |
| Kiểm tra bổ sung trên MySQL 8 sau lượt đầy đủ | Phục hồi lịch sử có amount `1000.0` và request trùng/khác payload qua RMI đều thành công |

Các test tạo schema riêng tên rmi_test_<UUID> và dọn chúng sau khi chạy. Không dùng banking_db1/2/3 của người dùng để thử nghiệm. Ba node có Store/JDBC riêng và RMI registry/socket thật; test worker nền có chạy scheduler, không chỉ gọi đồng bộ bằng tay.

Lượt MySQL đầy đủ hoàn thành 21:15:48 +07:00, tổng 3 phút 19 giây. Báo cáo gốc được giữ tại:

- `docs/results/change-capture-mysql8-bank.txt` (14 ca gốc)
- `docs/results/change-capture-mysql8-changes.txt` (27 ca, gồm hồi quy dùng lại fixture và 13 ca nâng cấp)

## Đối chiếu yêu cầu bắt buộc

| Yêu cầu | Ca kiểm thử / kết quả |
|---|---|
| Reset toàn bộ về 100000, gồm account khóa | resetAllAccountsOnceIncludesLockedAndKeepsHistory: 3 DB bằng nhau, giữ users/transfers/active, có backup, version tăng |
| Không reset lại sau giao dịch/restart | Cùng ca trên: gửi lại cùng/new requestId, restart PRIMARY, số dư sau chuyển khoản vẫn 99000/101000 |
| SQL UPDATE trên từng node | mysqlSqlUpdatesFromAllThreeNodesConverge: lần lượt PRIMARY, replica 2, replica 3, hai node còn lại hội tụ |
| Một UPDATE nhiều account và user | mysqlMultiRowAccountsAndUsers: 2 account, 3 user được xử lý qua trigger/queue |
| Replica offline rồi online | mysqlOfflineReplicaAndConflictRecovery: outbox chờ, SQL khi node offline, bắt kịp và xử lý xung đột |
| Restart khi còn yêu cầu chờ | mysqlPendingProposalSurvivesNodeRestart và mysqlPrimaryPendingSurvivesRestartAndAutoWorkersCatchUp |
| Worker tự gửi bù | Ca auto workers bật scheduler cả ba node và đợi ảnh confirmed hội tụ |
| Gửi trùng idempotent | mysqlDuplicateProposalIsIdempotentOverRmi: cùng request không phát event mới, khác payload cùng ID bị từ chối |
| Hai node sửa cùng account | mysqlConcurrentUpdatesAtTwoReplicasDetectConflict: một ACCEPTED, một CONFLICT, 3 DB bằng nhau |
| Chuyển khoản đồng thời | mysqlConcurrentTransferDoesNotUseProvisionalReplicaBalance: đề xuất cũ bị từ chối, transfer và số dư xác nhận được giữ |
| Đăng nhập và sửa từ ứng dụng | mysqlUserUpdatesAndAdministrativeApplicationEdits: đổi username/password hash/phone/name qua replica, login mới hoạt động, admin set-balance retry đúng kết quả |
| Các cột có ràng buộc unique/FK | mysqlOwnershipAndUsernameChainsPreserveExistingKeys: chuỗi đổi chủ và đổi tên được đồng bộ, giữ PK và không để lại user tạm |
| Phục hồi fingerprint | approvedRecoveryBacksUpAndPreservesUntrackedEditsAsProposals: backup, sai plan hash bị từ chối, repair giữ edit thành pending rồi reset explicit |
| Phục hồi replica | mysqlReplicaRecoveryUsesPrimaryHistory: dùng prefix event từ PRIMARY qua RMI để đối chiếu và phục hồi |
| Hồi quy cũ | Thành công/không đủ tiền/receiver sai/tự chuyển, request trùng, hai transfer đồng thời, rollback sau debit, duplicate/gap event, phiên/quyền/khóa account |

H2 không mô phỏng trigger. Kết quả trigger chỉ được tuyên bố dựa trên lượt MySQL 8 thật và lượt MariaDB đã chạy. Bộ kiểm thử MySQL được thực hiện với cấu hình test dùng schema/cổng độc lập.

## Chạy lại trên MySQL riêng

```powershell
$env:MYSQL_TEST_USER = 'root'
$env:MYSQL_TEST_PASSWORD = 'MAT_KHAU_MYSQL_TEST'
mvn test '-Dbank.test.mysql=jdbc:mysql://127.0.0.1:33306/'
```

Tài khoản test cần quyền tạo/xóa schema riêng. Có thể tạo MySQL tạm bằng Docker:

```powershell
docker run --rm -d --name rmi-mysql-verify -p 127.0.0.1:33306:3306 -e MYSQL_ROOT_PASSWORD=MAT_KHAU_MYSQL_TEST -e MYSQL_ROOT_HOST=% mysql:8.4
# Đợi MySQL khởi động, chạy test ở trên, rồi dọn container riêng:
docker stop rmi-mysql-verify
```

## Chưa xác minh / chưa thực hiện trên cụm thật

Tại thời điểm kiểm tra, các endpoint trong cấu hình thực tế đều không kết nối được:

- 172.20.10.7:1099 (PRIMARY)
- 172.20.10.5:1100 (REPLICA 2)
- 192.168.93.128:1101 (Ubuntu REPLICA 3)

Vì vậy chưa triển khai JAR lên ba máy, chưa chạy reset 100000 trên cụm thật và chưa kiểm thử mạng Ubuntu/LAN thực tế ở lần nâng cấp này. Không sửa cấu hình hoặc thay các CSDL local cũ vào vị trí của những node đó. Lệnh triển khai/reset và quy trình fingerprint có tại `docs/CHANGE-CAPTURE.md`.
Đã dừng/xóa container MySQL kiểm thử riêng sau khi xác nhận còn 0 schema rmi_test_. JAR cuối đã đóng gói thành công; SHA-256 tại docs/results/change-capture-artifacts.sha256.
