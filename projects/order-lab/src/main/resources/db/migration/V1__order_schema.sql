-- 金额用整数分，避免浮点误差。DATETIME 统一保存 UTC；数据库是库存和状态的事实来源。
CREATE TABLE product (
  id BIGINT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  price_cents BIGINT NOT NULL,
  available INT NOT NULL,
  reserved INT NOT NULL DEFAULT 0,
  sold INT NOT NULL DEFAULT 0,
  CONSTRAINT ck_stock CHECK (available >= 0 AND reserved >= 0 AND sold >= 0),
  CONSTRAINT ck_price CHECK (price_cents > 0)
) ENGINE=InnoDB;
INSERT INTO product(id,name,price_cents,available) VALUES (1,'机械键盘',29900,100),(2,'学习笔记本',3900,200);

CREATE TABLE purchase_order (
  id CHAR(36) PRIMARY KEY,
  buyer_id VARCHAR(64) NOT NULL,
  request_key VARCHAR(64) NOT NULL COMMENT '客户端一次下单意图的幂等键，重试必须复用',
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  amount_cents BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL COMMENT 'PENDING / PAID / CANCELLED',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_buyer_request (buyer_id,request_key),
  KEY idx_expiry (status,expires_at,id),
  CONSTRAINT fk_order_product FOREIGN KEY (product_id) REFERENCES product(id),
  CONSTRAINT ck_quantity CHECK (quantity BETWEEN 1 AND 10),
  CONSTRAINT ck_status CHECK (status IN ('PENDING','PAID','CANCELLED'))
) ENGINE=InnoDB;

-- 状态流转与审计同事务，故不会出现只记录成功日志却没有完成业务的问题。
CREATE TABLE order_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id CHAR(36) NOT NULL,
  action VARCHAR(32) NOT NULL,
  trace_id VARCHAR(64),
  thread_name VARCHAR(100) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_history_order (order_id,id)
) ENGINE=InnoDB;

-- Outbox：业务提交即保存发送意图。租约 token 防止过期工作者覆盖新工作者的发送结果。
CREATE TABLE outbox_event (
  id CHAR(36) PRIMARY KEY,
  order_id CHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  lease_token CHAR(36),
  lease_until DATETIME(6),
  last_error VARCHAR(200),
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_paid_event (order_id),
  KEY idx_dispatch (status,next_attempt_at,lease_until),
  CONSTRAINT ck_event_status CHECK (status IN ('PENDING','SENDING','SENT','FAILED'))
) ENGINE=InnoDB;

-- 一笔订单只奖励一次；消费日志、积分流水、积分余额在同一事务内提交。
CREATE TABLE consumed_event (
  event_id CHAR(36) PRIMARY KEY,
  order_id CHAR(36) NOT NULL,
  consumed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE points_ledger (
  order_id CHAR(36) PRIMARY KEY,
  buyer_id VARCHAR(64) NOT NULL,
  points BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE points_account (
  buyer_id VARCHAR(64) PRIMARY KEY,
  points BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
