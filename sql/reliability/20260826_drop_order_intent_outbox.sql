-- v5 已切换为 Redis Lua + Redis Stream，不再保留节目库的 Intent/Outbox 阶段。
DROP TABLE IF EXISTS stellaris_program_0.d_order_intent_0;
DROP TABLE IF EXISTS stellaris_program_0.d_order_intent_1;
DROP TABLE IF EXISTS stellaris_program_1.d_order_intent_0;
DROP TABLE IF EXISTS stellaris_program_1.d_order_intent_1;

DROP TABLE IF EXISTS stellaris_program_0.d_order_create_event_0;
DROP TABLE IF EXISTS stellaris_program_0.d_order_create_event_1;
DROP TABLE IF EXISTS stellaris_program_1.d_order_create_event_0;
DROP TABLE IF EXISTS stellaris_program_1.d_order_create_event_1;
