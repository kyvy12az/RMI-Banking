-- Run separately on banking_db1, banking_db2 and banking_db3 after selecting the database.
SELECT node_id,node_role,initialized FROM capture_state;
SELECT id,balance,version,active,user_id FROM accounts ORDER BY id;
SELECT seq,fingerprint FROM node_state WHERE id=1;
SELECT state,COUNT(*) AS requests FROM change_requests GROUP BY state;
SELECT request_id,origin_node,table_name,row_id,state,confirmed_seq,attempts,detail FROM change_requests ORDER BY local_order DESC LIMIT 30;
SELECT seq,node_id,ack,attempts,last_error FROM replication_deliveries ORDER BY seq DESC,node_id LIMIT 30;
SELECT operation_key,request_id,created_at FROM admin_operations ORDER BY created_at;
SELECT TRIGGER_NAME,EVENT_MANIPULATION,EVENT_OBJECT_TABLE FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE();
-- Account DELETE: compare on all three nodes after upgrading all server JARs.
SELECT id,user_id,balance,version,active FROM deleted_accounts ORDER BY id;
SELECT COUNT(*) AS transfer_count FROM transfers;
SELECT a.id AS resurrected_account FROM accounts a JOIN deleted_accounts d ON d.id=a.id;
SELECT t.id AS missing_history_identity FROM transfers t LEFT JOIN account_keys s ON s.id=t.sender LEFT JOIN account_keys r ON r.id=t.recipient WHERE s.id IS NULL OR r.id IS NULL;
