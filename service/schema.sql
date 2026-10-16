-- Laya 统一存储 schema（权威版本）
-- 两侧共用:Node service/db.mjs 用本文件;APK apk/src/com/laya/Db.kt 以常量镜像 DDL。
-- ⚠ 改此文件必须同步改 Db.kt 的 DDL 常量,并两侧同值递增 meta.schema_version。
PRAGMA journal_mode=WAL;

CREATE TABLE IF NOT EXISTS meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL                        -- schema_version / migrated:<file> / collect.intervalSec 等
);

-- 决策日志(Node answers 与 APK decoded 统一存为 answers;llm 升级行 type='llm')
CREATE TABLE IF NOT EXISTS decisions (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  ts         INTEGER NOT NULL,               -- epoch ms
  date       TEXT NOT NULL,                  -- 本地日期 YYYY-MM-DD(写入时算好)
  task       TEXT NOT NULL,
  type       TEXT NOT NULL DEFAULT 'decision',  -- decision | llm
  level      TEXT NOT NULL DEFAULT '未知',   -- 写入时按统一 levelOf 算好(高/中/低/<choice标签>/未知)
  basis      TEXT NOT NULL DEFAULT '',
  state      TEXT NOT NULL DEFAULT '',       -- ≤200 字符
  answers    TEXT NOT NULL DEFAULT '{}',     -- JSON: qid→{type,choice,score,noul,confidence,legend,probabilities}
  latency_ms INTEGER NOT NULL DEFAULT 0,
  via        TEXT NOT NULL DEFAULT '',       -- '' | api | gateway | collect
  llm        TEXT,                           -- type='llm' 行:JSON {model,content} 或 {error}
  UNIQUE(ts, task, type)
);
CREATE INDEX IF NOT EXISTS idx_dec_date ON decisions(date, task, level);
CREATE INDEX IF NOT EXISTS idx_dec_ts   ON decisions(ts);

-- 业务注册表(scanModels() 结果 upsert;内存 TASKS 仍为引擎路由权威)
CREATE TABLE IF NOT EXISTS tasks (
  id            TEXT PRIMARY KEY,
  label         TEXT NOT NULL,
  backend       TEXT NOT NULL DEFAULT 'cpu',
  enabled       INTEGER NOT NULL DEFAULT 1,
  state_key     TEXT NOT NULL DEFAULT 'text',
  model_dir     TEXT NOT NULL DEFAULT '',
  model_path    TEXT NOT NULL DEFAULT '',
  question_defs TEXT NOT NULL DEFAULT '[]',  -- JSON 数组
  classes       TEXT,                        -- JSON 或 NULL
  normal_label  TEXT,
  source        TEXT NOT NULL DEFAULT 'auto-scan',
  updated_at    INTEGER NOT NULL
);

-- 采集配置(APK cfg.collect 迁入;intervalSec 存 meta['collect.intervalSec'])
CREATE TABLE IF NOT EXISTS collect_configs (
  task       TEXT PRIMARY KEY,
  enabled    INTEGER NOT NULL DEFAULT 0,
  samples    TEXT NOT NULL DEFAULT '[]',     -- JSON 数组
  updated_at INTEGER NOT NULL
);

-- 网关入站留痕(邮件/MQTT 原始请求)
CREATE TABLE IF NOT EXISTS inbound (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  ts          INTEGER NOT NULL,
  channel     TEXT NOT NULL,                 -- email | mqtt
  sender      TEXT NOT NULL DEFAULT '',      -- 邮件 from / mqtt clientId
  subject     TEXT NOT NULL DEFAULT '',
  topic       TEXT NOT NULL DEFAULT '',      -- mqtt 主题;邮件为空
  text        TEXT NOT NULL DEFAULT '',
  task        TEXT NOT NULL DEFAULT '',      -- 解析出的业务(空=未识别)
  decision_id INTEGER,                       -- 决策成功后回填 decisions.id
  status      TEXT NOT NULL DEFAULT 'pending',  -- pending | done | failed | ignored
  error       TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_inbound_ts ON inbound(ts);

-- 回复模板(只存用户覆盖行;内置默认在代码里,删行=恢复默认)
-- kind: auto_open(键=部门)/auto_tone(键=紧急度1-5)/auto_escalate/auto_footer/
--       human_open(键=部门)/ugc_open(键=verdict)/agent_complex(键=复杂度)/risk_action(键=强度) 等
CREATE TABLE IF NOT EXISTS templates (
  task       TEXT NOT NULL,
  locale     TEXT NOT NULL,                  -- zh | en
  kind       TEXT NOT NULL,
  key        TEXT NOT NULL DEFAULT '',
  body       TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(task, locale, kind, key)
);

-- 样本/评测数据(tickets.csv 类)
CREATE TABLE IF NOT EXISTS eval_samples (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  source       TEXT NOT NULL DEFAULT 'tickets',
  ext_id       TEXT NOT NULL DEFAULT '',     -- source+ext_id 幂等(csv 行号等)
  text         TEXT NOT NULL,
  expect_task  TEXT NOT NULL DEFAULT '',
  expect_label TEXT NOT NULL DEFAULT '',
  meta         TEXT NOT NULL DEFAULT '{}',   -- 其余列 JSON
  created_at   INTEGER NOT NULL,
  UNIQUE(source, ext_id)
);
