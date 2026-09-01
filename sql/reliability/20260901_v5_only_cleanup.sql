-- Pure-v5 schema cleanup. Apply only after deploying the matching v5-only services.
-- Git recovery tag: pre-v5-only-cleanup-20260901
-- Back up any legacy reconciliation data before running: the dropped tables/columns are not recoverable from SQL.

ALTER TABLE stellaris_order_0.d_order_0 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;
ALTER TABLE stellaris_order_0.d_order_1 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;
ALTER TABLE stellaris_order_0.d_order_2 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;
ALTER TABLE stellaris_order_0.d_order_3 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;
ALTER TABLE stellaris_order_1.d_order_0 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;
ALTER TABLE stellaris_order_1.d_order_1 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;
ALTER TABLE stellaris_order_1.d_order_2 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;
ALTER TABLE stellaris_order_1.d_order_3 DROP COLUMN identifier_id, DROP COLUMN reconciliation_status, DROP COLUMN order_version;

ALTER TABLE stellaris_order_0.d_order_ticket_user_0 DROP COLUMN reconciliation_status;
ALTER TABLE stellaris_order_0.d_order_ticket_user_1 DROP COLUMN reconciliation_status;
ALTER TABLE stellaris_order_0.d_order_ticket_user_2 DROP COLUMN reconciliation_status;
ALTER TABLE stellaris_order_0.d_order_ticket_user_3 DROP COLUMN reconciliation_status;
ALTER TABLE stellaris_order_1.d_order_ticket_user_0 DROP COLUMN reconciliation_status;
ALTER TABLE stellaris_order_1.d_order_ticket_user_1 DROP COLUMN reconciliation_status;
ALTER TABLE stellaris_order_1.d_order_ticket_user_2 DROP COLUMN reconciliation_status;
ALTER TABLE stellaris_order_1.d_order_ticket_user_3 DROP COLUMN reconciliation_status;

ALTER TABLE stellaris_order_0.d_order_program_0 DROP INDEX order_program_identifier_id_idx, DROP COLUMN identifier_id, DROP COLUMN handle_status;
ALTER TABLE stellaris_order_0.d_order_program_1 DROP INDEX order_program_identifier_id_idx, DROP COLUMN identifier_id, DROP COLUMN handle_status;
ALTER TABLE stellaris_order_1.d_order_program_0 DROP INDEX order_program_identifier_id_idx, DROP COLUMN identifier_id, DROP COLUMN handle_status;
ALTER TABLE stellaris_order_1.d_order_program_1 DROP INDEX order_program_identifier_id_idx, DROP COLUMN identifier_id, DROP COLUMN handle_status;

DROP TABLE stellaris_order_0.d_order_ticket_user_record_0;
DROP TABLE stellaris_order_0.d_order_ticket_user_record_1;
DROP TABLE stellaris_order_0.d_order_ticket_user_record_2;
DROP TABLE stellaris_order_0.d_order_ticket_user_record_3;
DROP TABLE stellaris_order_1.d_order_ticket_user_record_0;
DROP TABLE stellaris_order_1.d_order_ticket_user_record_1;
DROP TABLE stellaris_order_1.d_order_ticket_user_record_2;
DROP TABLE stellaris_order_1.d_order_ticket_user_record_3;

DROP TABLE stellaris_program_0.d_program_record_task_0;
DROP TABLE stellaris_program_0.d_program_record_task_1;
DROP TABLE stellaris_program_1.d_program_record_task_0;
DROP TABLE stellaris_program_1.d_program_record_task_1;
