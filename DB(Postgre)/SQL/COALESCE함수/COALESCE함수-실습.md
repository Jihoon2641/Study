---
주제: COALESCE() 함수 (NULL 대체 표현식)
폴더: SQL/COALESCE함수
분류: PostgreSQL / SQL 처리·표현식 평가
난이도: 중급
관련버전: PostgreSQL 12+ (6-3의 표현식 확장 통계는 14+)
작성일: 2026-09-16
선행지식: [NULL과 3값 논리, EXPLAIN 읽기, B-tree 인덱스 기본, 플래너 선택도 추정]
---

# COALESCE() — 실습

관련 문서: [이론](./COALESCE함수-이론.md)

> 전제: PostgreSQL 16, 로컬 단일 인스턴스, `shared_buffers = 128MB`(기본값), SSD. 측정 시 병렬 쿼리와 JIT은 끄고 비교한다.
>
> **이 문서의 수치는 작성 환경에서 측정하지 않았다.** 8장에는 기대되는 노드 구조와 논리적으로 결정되는 행 수만 채웠고, 시간·버퍼 수치는 직접 실행 후 기록하는 칸으로 비워 두었다. 9장 비교표도 같은 규칙이다.

---

## 1. 실습 개요

| # | 재현 내용 | 수치로 증명하는 것 |
|---|---|---|
| 4-1 | `WHERE COALESCE(nickname, name) = '...'` | Seq Scan + `Filter:`, 추정 `rows`가 정확히 1,000,000 × 0.005 = **5,000** (`DEFAULT_EQ_SEL`) |
| 4-2 | `WHERE nickname = COALESCE($1, nickname)` | 리터럴(상수 접힘) vs `PREPARE` 바인드에서 **계획이 다르다**. `$1 = NULL`일 때 nickname NULL인 200,000행이 **누락**된다 |
| 4-3 | `ORDER BY COALESCE(last_login_at, created_at) DESC LIMIT 20` | `Sort` 노드의 `actual rows = 1,000,000`. LIMIT 20인데 입력이 100만 |
| 4-4 | `COALESCE(SUM(amount), 0)` + `GROUP BY` | 주문이 0건인 등급이 결과에서 **행 자체가 사라짐**(4행 → 3행) |
| 4-5 | `LEFT JOIN` + `WHERE COALESCE(o.status, 'NONE') <> 'CANCELED'` | 조인 노드에 `Left`가 **남는다**. `WHERE o.status <> ...`로 바꾸면 사라진다 |
| 4-6 | `COALESCE(nickname, (스칼라 서브쿼리))` | `SubPlan`의 `loops = 200,000` (nickname NULL 행 수와 일치) |
| 6-2 | 표현식 인덱스 적용 | `Filter` → `Index Cond`, `Rows Removed by Filter` 소멸 |
| 6-3 | `CREATE STATISTICS` 적용 (PG14+) | 계획은 그대로여도 추정 `rows`가 5,000 → 실제값 근처로 이동 |

---

## 2. 환경

### 2.1 버전 확인

```sql
SELECT version();
SHOW server_version_num;   -- 140000 미만이면 6-3(확장 통계)은 건너뛴다
```

### 2.2 필요한 확장

```sql
-- 선택: 쿼리별 누적 통계. postgresql.conf의 shared_preload_libraries에 추가 후 재시작 필요
--   shared_preload_libraries = 'pg_stat_statements'
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 선택: 인덱스/테이블 물리 크기 확인용 (pg_relation_size로도 충분하므로 필수는 아님)
CREATE EXTENSION IF NOT EXISTS pgstattuple;
```

### 2.3 측정용 세션 파라미터

계획 비교에서 변수를 줄인다. **모두 세션 단위이며 재접속하면 원복된다.**

```sql
SET jit = off;                            -- 기본 on. JIT 컴파일 시간이 측정에 섞이는 것을 막는다
SET max_parallel_workers_per_gather = 0;  -- 기본 2. Gather 노드를 없애 계획을 단순하게 본다
SET work_mem = '4MB';                     -- 기본값 명시. 4-3의 정렬이 디스크로 넘어가는지 보기 위함
SET track_io_timing = on;                 -- BUFFERS와 함께 I/O 시간을 본다 (권한 필요할 수 있음)
```

원복:

```sql
RESET jit;
RESET max_parallel_workers_per_gather;
RESET work_mem;
RESET track_io_timing;
-- 또는 세션 종료
```

psql 사용 시 `\timing on`으로 클라이언트 왕복 시간도 함께 본다. (psql 전용 메타 명령)

---

## 3. 재현 데이터 생성

### 3.1 규모를 이렇게 잡는 이유

| 테이블 | 행 수 | 이유 |
|---|---|---|
| `lab_member` | 1,000,000 | ① `DEFAULT_EQ_SEL = 0.005`가 만드는 추정 5,000행이 실제값과 뚜렷하게 갈린다. ② 100만 행이면 플래너가 인덱스가 있을 때 Seq Scan을 고르지 않는다. 10만 행이면 표현식이 없어도 Seq Scan이 이겨서 대조가 안 된다 |
| `lab_order` | 2,000,000 | 회원당 평균 2건. 4-5의 외부 조인 축소 여부가 행 수 차이로 드러날 만큼 |
| `lab_profile` | 800,000 | 회원의 80%만 프로필 보유. 4-6의 `SubPlan` `loops`와 대조 |

NULL 비율은 의도적으로 고정한다.

- `nickname` NULL 20% → 정확히 200,000행. 4-2의 누락 행 수, 4-6의 `loops`와 일치해야 한다
- `last_login_at` NULL 15% → 150,000행 (신규 가입자)
- `memo`는 NULL 10% / 빈 문자열 `''` 10% → 이론 함정 6 재현용

### 3.2 시드 스크립트

```sql
-- 실행 순서 1
DROP TABLE IF EXISTS lab_order;
DROP TABLE IF EXISTS lab_profile;
DROP TABLE IF EXISTS lab_member;

CREATE TABLE lab_member (
    id            bigint      PRIMARY KEY,
    name          text        NOT NULL,
    nickname      text,                       -- 20% NULL
    grade         text        NOT NULL,
    memo          text,                       -- 10% NULL, 10% ''
    last_login_at timestamptz,                -- 15% NULL
    created_at    timestamptz NOT NULL
);

INSERT INTO lab_member (id, name, nickname, grade, memo, last_login_at, created_at)
SELECT g,
       'name_'  || g,
       CASE WHEN g % 5 = 0 THEN NULL ELSE 'nick_' || g END,          -- 20% NULL
       CASE WHEN g % 100 = 0 THEN 'VIP'
            WHEN g % 10  = 0 THEN 'GOLD'
            WHEN g % 4   = 0 THEN 'SILVER'
            ELSE 'BASIC' END,
       CASE WHEN g % 10 = 0 THEN NULL
            WHEN g % 10 = 1 THEN ''
            ELSE 'memo_' || g END,
       CASE WHEN g % 20 < 3 THEN NULL                                 -- 15% NULL
            ELSE now() - ((g % 500) || ' days')::interval END,
       now() - ((g % 1000) || ' days')::interval
FROM generate_series(1, 1000000) AS g;

CREATE TABLE lab_profile (
    member_id bigint PRIMARY KEY REFERENCES lab_member(id),
    alias     text   NOT NULL
);

INSERT INTO lab_profile (member_id, alias)
SELECT g, 'alias_' || g
FROM   generate_series(1, 800000) AS g;      -- 회원의 80%만 보유

CREATE TABLE lab_order (
    id        bigint PRIMARY KEY,
    member_id bigint NOT NULL,
    status    text   NOT NULL,
    amount    numeric(12,2) NOT NULL
);

INSERT INTO lab_order (id, member_id, status, amount)
SELECT g,
       ((g - 1) % 900000) + 1,               -- 1~900,000번 회원만 주문 보유 → 100,000명은 주문 0건
       CASE WHEN g % 17 = 0 THEN 'CANCELED' ELSE 'DONE' END,
       (g % 1000) + 0.5
FROM   generate_series(1, 2000000) AS g;

-- 4-1 대조군: nickname 단일 컬럼 인덱스는 있다. "인덱스가 있는데 왜 안 타나"를 보기 위함
CREATE INDEX lab_member_nickname_idx  ON lab_member (nickname);
CREATE INDEX lab_member_name_idx      ON lab_member (name);
CREATE INDEX lab_member_lastlogin_idx ON lab_member (last_login_at);
CREATE INDEX lab_order_member_idx     ON lab_order  (member_id);
```

### 3.3 ANALYZE 시점

**대량 `INSERT` 직후 / 인덱스 생성 직후 / `CREATE STATISTICS` 직후에 반드시 돌린다.** autovacuum이 언제 올지 기다리면 측정이 흔들린다.

```sql
-- 실행 순서 2
ANALYZE lab_member;
ANALYZE lab_profile;
ANALYZE lab_order;
```

```sql
-- 통계가 실제로 갱신됐는지 확인
SELECT relname, n_live_tup, last_analyze, last_autoanalyze
FROM   pg_stat_user_tables
WHERE  relname LIKE 'lab_%';
```

### 3.4 기준값 확인

이후 모든 비교의 기준이 되는 수치다. 먼저 찍어 둔다.

```sql
SELECT count(*)                                            AS total,
       count(*) FILTER (WHERE nickname IS NULL)            AS nickname_null,
       count(*) FILTER (WHERE last_login_at IS NULL)       AS lastlogin_null,
       count(*) FILTER (WHERE memo IS NULL)                AS memo_null,
       count(*) FILTER (WHERE memo = '')                   AS memo_empty
FROM   lab_member;
-- 기대: total=1000000, nickname_null=200000, lastlogin_null=150000,
--       memo_null=100000, memo_empty=100000
```

---

## 4. 문제 상황 재현

각 실습은 **먼저 계획과 시간을 기록**한 뒤 6장에서 개선한다.

### 4-1. 표현식으로 감싼 조회 조건

흔히 쓰는 방식:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name
FROM   lab_member
WHERE  COALESCE(nickname, name) = 'nick_777';
```

`nickname`에 인덱스가 있는데도 Seq Scan이 나온다. 볼 지점:

- `Seq Scan on lab_member` + `Filter: (COALESCE(nickname, name) = 'nick_777'::text)`
- 추정 `rows=5000` — **정확히 1,000,000 × `DEFAULT_EQ_SEL`(0.005)**
- `actual rows=1`
- `Rows Removed by Filter: 999999`

대조군(같은 값을 컬럼 조건으로):

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name FROM lab_member WHERE nickname = 'nick_777';
-- Index Scan using lab_member_nickname_idx, Index Cond, rows=1
```

두 쿼리는 이 데이터에서 같은 1행을 돌려준다. 차이는 전적으로 표현식 유무다.

### 4-2. 선택적 파라미터 관용구

**(a) psql에 리터럴로 치는 경우 — 재현 실패하는 쪽**

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM lab_member WHERE nickname = COALESCE('nick_777', nickname);
-- 상수 접힘으로 COALESCE가 사라지고 nickname = 'nick_777'이 된다 → Index Scan
```

**(b) 바인드 파라미터 — 실제 애플리케이션 쪽**

```sql
PREPARE p_opt(text) AS
    SELECT id FROM lab_member WHERE nickname = COALESCE($1, nickname);

EXPLAIN (ANALYZE, BUFFERS) EXECUTE p_opt('nick_777');
-- 우변에 컬럼이 남아 있으므로 Index Cond를 만들 수 없다 → Seq Scan + Filter
```

**(c) 정확성 버그 — 파라미터가 NULL일 때**

```sql
SELECT count(*) FROM lab_member WHERE nickname = COALESCE(NULL, nickname);
-- 기대했던 "전체 조회" = 1000000
-- 실제 = 800000   (nickname IS NULL인 200,000행이 nickname = nickname 에서 UNKNOWN이 되어 탈락)

SELECT count(*) FROM lab_member;   -- 1000000
```

성능 문제 이전에 **결과가 틀린다.** 이 20만 행 차이가 이 관용구를 쓰면 안 되는 가장 큰 이유다.

```sql
DEALLOCATE p_opt;
```

### 4-3. 표현식 정렬 + LIMIT

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id
FROM   lab_member
ORDER  BY COALESCE(last_login_at, created_at) DESC
LIMIT  20;
```

볼 지점:

- `Limit` → `Sort` → `Seq Scan`
- `Sort`의 `actual rows` 입력이 1,000,000 (LIMIT 20인데 100만 행을 정렬 대상으로 넣는다)
- `Sort Method: top-N heapsort  Memory: ...kB` 또는 `external merge  Disk: ...kB`
- `Buffers: shared read` 값이 테이블 전체 페이지 수에 근접

대조군(표현식 없는 정렬):

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM lab_member ORDER BY last_login_at DESC NULLS LAST LIMIT 20;
-- Index Scan Backward using lab_member_lastlogin_idx → Sort 노드 없음
```

### 4-4. 집계에서 COALESCE의 위치

```sql
-- (a) 위치를 잘못 잡은 경우 — SUM이 어차피 NULL을 무시하므로 의미 없는 중복
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.grade, SUM(COALESCE(o.amount, 0)) AS total
FROM   lab_member m JOIN lab_order o ON o.member_id = m.id
GROUP  BY m.grade;

-- (b) 0건 그룹이 사라지는 문제
SELECT m.grade, COALESCE(SUM(o.amount), 0) AS total
FROM   lab_member m JOIN lab_order o ON o.member_id = m.id
WHERE  m.grade = 'VIP' AND o.status = 'NOT_EXIST_STATUS'
GROUP  BY m.grade;
-- 결과: 0 rows.  COALESCE(SUM(...), 0)을 썼는데도 "VIP | 0" 행이 나오지 않는다
--       GROUP BY는 행이 있어야 그룹을 만들기 때문
```

**(c) 의도대로 0을 채우려면** 기준 목록과 `LEFT JOIN`해야 한다.

```sql
SELECT g.grade, COALESCE(SUM(o.amount), 0) AS total
FROM   (VALUES ('BASIC'), ('SILVER'), ('GOLD'), ('VIP')) AS g(grade)
LEFT   JOIN lab_member m ON m.grade = g.grade
LEFT   JOIN lab_order  o ON o.member_id = m.id AND o.status = 'NOT_EXIST_STATUS'
GROUP  BY g.grade
ORDER  BY g.grade;
-- 결과: 4 rows, 전부 total = 0.00   ← 여기서 비로소 COALESCE가 일한다
```

### 4-5. LEFT JOIN + COALESCE 필터

```sql
-- (a) COALESCE로 감싼 경우
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM   lab_member m
LEFT   JOIN lab_order o ON o.member_id = m.id
WHERE  COALESCE(o.status, 'NONE') <> 'CANCELED';
-- 볼 것: 조인 노드명에 "Left"가 남아 있는지
--        주문 0건인 회원 100,000명이 결과에 포함되는지

-- (b) 컬럼 조건으로 쓴 경우
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM   lab_member m
LEFT   JOIN lab_order o ON o.member_id = m.id
WHERE  o.status <> 'CANCELED';
-- 볼 것: "Left"가 사라지고 Hash Join으로 축소됨
--        주문 0건 회원 100,000명이 통째로 빠짐
```

행 수로 직접 확인:

```sql
SELECT
  (SELECT count(*) FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
    WHERE COALESCE(o.status, 'NONE') <> 'CANCELED')  AS with_coalesce,
  (SELECT count(*) FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
    WHERE o.status <> 'CANCELED')                    AS with_plain;
-- with_coalesce - with_plain = 100000 (주문 0건 회원 수)
```

### 4-6. 인자에 스칼라 서브쿼리

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(m.nickname,
                (SELECT p.alias FROM lab_profile p WHERE p.member_id = m.id)) AS display
FROM   lab_member m;
```

볼 지점:

- `SubPlan 1` 블록과 그 안의 `Index Scan using lab_profile_pkey`
- **`loops=200000`** — nickname이 NULL인 행 수와 정확히 일치한다. 단축 평가가 실제로 동작한다는 증거이면서, 동시에 20만 번 실행된다는 증거다
- `SubPlan`의 `actual time`에 `loops`를 곱한 값이 전체 시간의 대부분을 차지

---

## 5. 진단

결론부터 보지 않고 좁혀 간다.

### 5-1. 계획에서 추정과 실제의 괴리를 본다

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id FROM lab_member WHERE COALESCE(nickname, name) = 'nick_777';
```

`rows=5000` 같은 수치가 보이면 다음을 계산한다.

```sql
SELECT reltuples::bigint                       AS est_rows,
       (reltuples * 0.005)::bigint             AS default_eq_sel_rows
FROM   pg_class WHERE relname = 'lab_member';
-- 계획의 추정 rows가 default_eq_sel_rows와 같으면
-- → 통계를 전혀 못 찾고 DEFAULT_EQ_SEL로 떨어진 것 (이론 5.4절)
```

### 5-2. 통계가 붙을 자리가 있는지 확인한다

```sql
-- (a) 컬럼 통계는 존재하는가 (있어도 표현식에는 안 쓰인다)
SELECT attname, n_distinct, null_frac
FROM   pg_stats
WHERE  tablename = 'lab_member' AND attname IN ('nickname', 'name', 'grade');

-- (b) 표현식 인덱스가 있는가
SELECT indexname, indexdef
FROM   pg_indexes
WHERE  tablename = 'lab_member';

-- (c) 표현식 확장 통계가 있는가 (PG14+)
SELECT statistics_name, expr, n_distinct, null_frac
FROM   pg_stats_ext_exprs
WHERE  tablename = 'lab_member';
```

(b), (c)가 모두 비어 있으면 5-1의 결론이 확정된다.

### 5-3. 스캔 방식이 실제로 바뀌고 있는지 누적치로 본다

```sql
SELECT relname,
       seq_scan, seq_tup_read,
       CASE WHEN seq_scan > 0 THEN seq_tup_read / seq_scan END AS tup_per_seq_scan,
       idx_scan
FROM   pg_stat_user_tables
WHERE  relname LIKE 'lab_%';
-- tup_per_seq_scan이 테이블 행 수에 가까우면 매번 전체를 훑고 있다는 뜻
```

### 5-4. 인덱스를 만들었는데 안 쓰이는 경우

```sql
SELECT indexrelname, idx_scan, idx_tup_read, idx_tup_fetch
FROM   pg_stat_all_indexes
WHERE  relname = 'lab_member';
-- idx_scan = 0인 표현식 인덱스가 있으면 정의 문자열을 쿼리와 대조한다
SELECT indexdef FROM pg_indexes
WHERE  tablename = 'lab_member' AND indexdef LIKE '%COALESCE%';
```

### 5-5. 코드베이스 전체에서 같은 패턴 찾기

```sql
SELECT calls,
       round(mean_exec_time::numeric, 2) AS mean_ms,
       rows / GREATEST(calls, 1)         AS rows_per_call,
       left(query, 100)                  AS q
FROM   pg_stat_statements
WHERE  query ILIKE '%coalesce(%'
ORDER  BY total_exec_time DESC
LIMIT  20;
```

`rows_per_call`이 계획의 추정 `rows`와 크게 다른 쿼리가 우선 수정 대상이다.

### 5-6. NOT NULL 컬럼을 감싸고 있지는 않은가

```sql
SELECT a.attname, a.attnotnull
FROM   pg_attribute a
WHERE  a.attrelid = 'lab_member'::regclass
  AND  a.attnum > 0 AND NOT a.attisdropped
ORDER  BY a.attnum;
-- attnotnull = true인 컬럼(name, grade)을 COALESCE로 감싼 쿼리가 있으면
-- 그건 그냥 지우면 되는 건이다 (이론 함정 7)
```

---

## 6. 개선

단계별로 적용하고, 각 단계에서 **계획의 어느 노드가 어떻게 바뀌는지**만 본다.

### 6-1. 술어를 컬럼 쪽으로 되돌린다 (인덱스도 통계도 회복)

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name
FROM   lab_member
WHERE  nickname = 'nick_777'
   OR (nickname IS NULL AND name = 'nick_777');
```

바뀌는 지점:

- `Seq Scan` → `Bitmap Heap Scan` 위의 `BitmapOr`
- `BitmapOr` 아래에 `Bitmap Index Scan on lab_member_nickname_idx`와 `lab_member_name_idx`
- 추정 `rows`가 `DEFAULT_EQ_SEL`이 아니라 **컬럼 통계 기반**으로 계산됨
- `Rows Removed by Filter`가 사라지거나 크게 줄어듦

의미가 완전히 같은지는 반드시 확인한다.

```sql
SELECT (SELECT count(*) FROM lab_member WHERE COALESCE(nickname, name) = 'nick_777') AS a,
       (SELECT count(*) FROM lab_member
         WHERE nickname = 'nick_777' OR (nickname IS NULL AND name = 'nick_777'))    AS b;
-- a = b 여야 한다
```

### 6-2. 표현식 인덱스 (조회식이 고정돼 있을 때)

```sql
-- 운영 환경 경고: CREATE INDEX는 SHARE 락을 잡아 해당 테이블의 INSERT/UPDATE/DELETE를 전부 차단한다.
--                운영에서는 반드시 CONCURRENTLY를 쓴다 (SHARE UPDATE EXCLUSIVE, 쓰기 통과).
CREATE INDEX lab_member_display_idx ON lab_member ((COALESCE(nickname, name)));
ANALYZE lab_member;   -- 인덱스 생성만으로는 통계가 붙지 않는다

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, name FROM lab_member WHERE COALESCE(nickname, name) = 'nick_777';
```

바뀌는 지점:

- `Seq Scan` + `Filter:` → `Index Scan using lab_member_display_idx` + **`Index Cond:`**
- `Rows Removed by Filter: 999999` → 0 (또는 항목 자체가 사라짐)
- `Buffers: shared read`가 테이블 전체 페이지에서 인덱스 몇 페이지 수준으로 감소
- 추정 `rows`도 표현식 인덱스에 붙은 통계로 계산되어 실제값에 근접

통계가 실제로 붙었는지:

```sql
SELECT tablename, attname, n_distinct, null_frac
FROM   pg_stats
WHERE  tablename = 'lab_member_display_idx';
-- 표현식 인덱스의 통계는 "인덱스 이름"으로 pg_stats에 나타난다
```

**비용**: `lab_member`에 대한 모든 `INSERT`/`UPDATE`가 이 인덱스도 갱신한다. 인덱스 크기를 함께 기록한다.

```sql
SELECT pg_size_pretty(pg_relation_size('lab_member_display_idx')) AS idx_size;
```

### 6-3. 인덱스 없이 추정만 고치기 (PG14+)

읽기 경로를 바꿀 필요는 없고 **조인 순서가 잘못 잡히는 것만** 막고 싶을 때.

```sql
-- PG14 미만이면 이 절은 건너뛴다
CREATE STATISTICS lab_member_display_stat ON (COALESCE(nickname, name)) FROM lab_member;
ANALYZE lab_member;

EXPLAIN
SELECT id FROM lab_member WHERE COALESCE(nickname, name) = 'nick_777';
-- 계획 형태(Seq Scan)는 그대로일 수 있으나 추정 rows가 5000에서 실제값 근처로 이동한다
```

확인:

```sql
SELECT statistics_name, expr, n_distinct, null_frac, most_common_vals IS NOT NULL AS has_mcv
FROM   pg_stats_ext_exprs
WHERE  tablename = 'lab_member';
```

`CREATE STATISTICS`는 `SHARE UPDATE EXCLUSIVE` 락이라 쓰기를 막지 않는다. 표현식 인덱스보다 부작용이 작다.

### 6-4. 정렬용 표현식 인덱스 (4-3 개선)

```sql
-- 쿼리의 ORDER BY와 정렬 방향까지 정확히 일치시켜야 한다
CREATE INDEX lab_member_lastseen_idx
    ON lab_member ((COALESCE(last_login_at, created_at)) DESC);
ANALYZE lab_member;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM lab_member
ORDER BY COALESCE(last_login_at, created_at) DESC
LIMIT 20;
```

바뀌는 지점:

- `Sort` 노드가 **사라진다**
- `Index Scan using lab_member_lastseen_idx` 하나만 남고 `Limit`이 20행에서 스캔을 끊는다
- `Buffers: shared read`가 테이블 전체 → 인덱스 상위 몇 페이지 수준

**주의**: `ORDER BY ... ASC`로 바꾸거나 `NULLS FIRST`를 붙이면 이 인덱스는 쓰이지 않거나 역방향 스캔이 된다. 11장 변형 실습 2에서 확인한다.

### 6-5. 선택적 파라미터를 동적 SQL로 (4-2 개선)

SQL 쪽에서는 조건이 **아예 없는** 형태가 되어야 한다.

```sql
-- 파라미터가 있을 때만 생성되는 SQL
SELECT id FROM lab_member WHERE nickname = $1;   -- Index Scan
-- 파라미터가 없을 때 생성되는 SQL
SELECT id FROM lab_member;                       -- 조건 없음. 4-2(c)의 20만 행 누락도 없음
```

애플리케이션 코드는 10장에 있다.

### 6-6. 스칼라 서브쿼리를 조인으로 (4-6 개선)

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT COALESCE(m.nickname, p.alias) AS display
FROM   lab_member m
LEFT   JOIN lab_profile p ON p.member_id = m.id;
```

바뀌는 지점:

- `SubPlan` 블록과 `loops=200000`이 **사라진다**
- `Hash Left Join` 또는 `Merge Left Join` 한 번으로 처리
- 전체 실행 시간이 서브쿼리 20만 회 실행분만큼 감소

주의: 이 재작성은 `lab_profile.member_id`가 유니크할 때만 결과가 같다. 1:N이면 행이 늘어난다.

### 6-7. 빈 문자열 정리 (이론 함정 6)

```sql
SELECT COALESCE(NULLIF(memo, ''), '없음') AS memo_display
FROM   lab_member WHERE id IN (1, 11, 21);
-- id % 10 = 1인 행의 memo는 ''이지만 '없음'으로 표시된다

-- 근본 대응 (운영 경고: 대량 UPDATE는 같은 수의 데드 튜플을 만든다. 배치로 쪼갤 것)
UPDATE lab_member SET memo = NULL WHERE memo = '';
```

---

## 7. 전체 스크립트

```sql
-- ============================================================
-- COALESCE 실습 전체 스크립트
-- 실행 순서:
--   0) 세션 파라미터   1) 시드 생성   2) ANALYZE   3) 기준값 확인
--   4) 문제 재현       5) 진단        6) 개선       7) 정리(12장)
-- 주의: lab_ 접두어 객체만 다룬다. 운영 스키마와 섞이지 않게 별도 DB에서 실행 권장
-- ============================================================

-- 0) 세션 파라미터 (세션 단위, 재접속 시 원복)
SET jit = off;
SET max_parallel_workers_per_gather = 0;
SET work_mem = '4MB';

-- 1) 시드 생성  ── 3.2절 스크립트 전체를 여기서 실행
--    DROP TABLE IF EXISTS lab_order; lab_profile; lab_member; ... (3.2 참조)

-- 2) ANALYZE
ANALYZE lab_member;
ANALYZE lab_profile;
ANALYZE lab_order;

-- 3) 기준값
SELECT count(*)                                      AS total,
       count(*) FILTER (WHERE nickname IS NULL)      AS nickname_null,
       count(*) FILTER (WHERE last_login_at IS NULL) AS lastlogin_null
FROM   lab_member;

-- 4) 문제 재현
EXPLAIN (ANALYZE, BUFFERS) SELECT id, name FROM lab_member
 WHERE COALESCE(nickname, name) = 'nick_777';                        -- 4-1

PREPARE p_opt(text) AS SELECT id FROM lab_member WHERE nickname = COALESCE($1, nickname);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE p_opt('nick_777');                -- 4-2(b)
SELECT count(*) FROM lab_member WHERE nickname = COALESCE(NULL, nickname);  -- 4-2(c) → 800000
DEALLOCATE p_opt;

EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM lab_member
 ORDER BY COALESCE(last_login_at, created_at) DESC LIMIT 20;         -- 4-3

SELECT m.grade, COALESCE(SUM(o.amount), 0) AS total                   -- 4-4(b) → 0 rows
FROM   lab_member m JOIN lab_order o ON o.member_id = m.id
WHERE  m.grade = 'VIP' AND o.status = 'NOT_EXIST_STATUS'
GROUP  BY m.grade;

EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM lab_member m          -- 4-5(a)
 LEFT JOIN lab_order o ON o.member_id = m.id
 WHERE COALESCE(o.status, 'NONE') <> 'CANCELED';

EXPLAIN (ANALYZE, BUFFERS)                                            -- 4-6
SELECT COALESCE(m.nickname,
       (SELECT p.alias FROM lab_profile p WHERE p.member_id = m.id)) FROM lab_member m;

-- 5) 진단
SELECT reltuples::bigint AS est_rows, (reltuples * 0.005)::bigint AS default_eq_sel_rows
FROM   pg_class WHERE relname = 'lab_member';
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'lab_member';
SELECT relname, seq_scan, seq_tup_read, idx_scan
FROM   pg_stat_user_tables WHERE relname LIKE 'lab_%';

-- 6) 개선
--    운영 경고: CREATE INDEX = SHARE 락(모든 쓰기 차단). 운영에서는 CONCURRENTLY 사용
CREATE INDEX lab_member_display_idx  ON lab_member ((COALESCE(nickname, name)));
CREATE INDEX lab_member_lastseen_idx ON lab_member ((COALESCE(last_login_at, created_at)) DESC);
ANALYZE lab_member;

EXPLAIN (ANALYZE, BUFFERS) SELECT id, name FROM lab_member
 WHERE COALESCE(nickname, name) = 'nick_777';                        -- 6-2 확인
EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM lab_member
 ORDER BY COALESCE(last_login_at, created_at) DESC LIMIT 20;         -- 6-4 확인

-- PG14+ 전용
CREATE STATISTICS lab_member_display_stat ON (COALESCE(nickname, name)) FROM lab_member;
ANALYZE lab_member;
SELECT statistics_name, expr, n_distinct FROM pg_stats_ext_exprs WHERE tablename = 'lab_member';

-- 7) 정리는 12장 참조
```

---

## 8. 실행 결과

> 아래 블록은 **작성 환경에서 측정하지 않았다.** 노드 구조와 논리적으로 확정되는 행 수만 채우고, 시간·버퍼는 직접 실행 후 채운다.

### 8-1. 4-1 개선 전 (기대 형태)

```
Seq Scan on lab_member  (cost=... rows=5000 width=...) (actual time=... rows=1 loops=1)
  Filter: (COALESCE(nickname, name) = 'nick_777'::text)
  Rows Removed by Filter: 999999
  Buffers: shared hit=____ read=____
Planning Time: ____ ms
Execution Time: ____ ms
```

핵심 확인 포인트

- `rows=5000` ← 1,000,000 × 0.005. 통계 미적용의 지문(fingerprint)
- `Rows Removed by Filter: 999999` ← 1행 얻으려고 100만 행을 읽었다

### 8-2. 6-2 개선 후 (기대 형태)

```
Index Scan using lab_member_display_idx on lab_member  (cost=... rows=__ width=...) (actual time=... rows=1 loops=1)
  Index Cond: (COALESCE(nickname, name) = 'nick_777'::text)
  Buffers: shared hit=____ read=____
Planning Time: ____ ms
Execution Time: ____ ms
```

- `Filter:` → `Index Cond:` 로 이동한 것이 개선의 본질
- `Rows Removed by Filter` 항목 소멸

### 8-3. 4-3 / 6-4 정렬 비교 (기대 형태)

```
-- 개선 전
Limit  (actual rows=20 loops=1)
  ->  Sort  (actual rows=20 loops=1)
        Sort Key: (COALESCE(last_login_at, created_at)) DESC
        Sort Method: top-N heapsort  Memory: ____kB
        ->  Seq Scan on lab_member  (actual rows=1000000 loops=1)
              Buffers: shared read=____

-- 개선 후
Limit  (actual rows=20 loops=1)
  ->  Index Scan using lab_member_lastseen_idx on lab_member  (actual rows=20 loops=1)
        Buffers: shared hit=____ read=____
```

- `Sort` 노드의 소멸과 하위 스캔의 `actual rows`가 1,000,000 → 20으로 줄어드는 것이 지표

### 8-4. 4-6 SubPlan (기대 형태)

```
Seq Scan on lab_member m  (actual rows=1000000 loops=1)
  SubPlan 1
    ->  Index Scan using lab_profile_pkey on lab_profile p  (actual time=... rows=1 loops=200000)
          Index Cond: (member_id = m.id)
```

- `loops=200000` = nickname NULL 행 수. 단축 평가가 동작한다는 증거이자 비용의 출처

### 8-5. 4-2(c) 정확성 확인 (논리적으로 확정되는 값)

```
 count
--------
 800000        ← "전체 조회" 의도였으나 200,000행 누락
(1 row)
```

### 8-6. 4-4(b)/(c) 비교 (논리적으로 확정되는 값)

```
-- (b) JOIN + GROUP BY
 grade | total
-------+-------
(0 rows)        ← COALESCE(SUM(...), 0)을 썼는데도 행이 없다

-- (c) 기준 목록 LEFT JOIN
 grade  | total
--------+-------
 BASIC  |  0.00
 GOLD   |  0.00
 SILVER |  0.00
 VIP    |  0.00
(4 rows)
```

---

## 9. 전후 비교표

> 시간·버퍼 칸은 직접 측정해 채운다. 굵게 표시한 칸은 **환경과 무관하게 논리적으로 결정되는 값**이다.

### 9-1. 4-1 → 6-2 (조회 조건)

| 지표 | 개선 전 (Seq Scan) | 개선 후 (표현식 인덱스) |
|---|---|---|
| 스캔 노드 | **Seq Scan** | **Index Scan** |
| 술어 위치 | **`Filter:`** | **`Index Cond:`** |
| 추정 rows | **5,000** (= 1,000,000 × 0.005) | 실제값 근처 (`____`) |
| 실제 rows | **1** | **1** |
| Rows Removed by Filter | **999,999** | **0 / 항목 없음** |
| shared hit / read | `____` / `____` | `____` / `____` |
| 실행 시간 | `____ ms` | `____ ms` |
| 추가 인덱스 크기 | 0 | `____ MB` (`pg_relation_size`) |

### 9-2. 4-3 → 6-4 (정렬 + LIMIT)

| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| Sort 노드 | **존재** | **없음** |
| 하위 스캔 actual rows | **1,000,000** | **20** |
| Sort Method | top-N heapsort / external merge | — |
| shared read | `____` | `____` |
| 실행 시간 | `____ ms` | `____ ms` |

### 9-3. 4-6 → 6-6 (스칼라 서브쿼리)

| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| SubPlan | **존재, `loops=200,000`** | **없음** |
| 조인 노드 | 없음 | **Hash Left Join** |
| 반환 행수 | **1,000,000** | **1,000,000** (동일해야 함) |
| 실행 시간 | `____ ms` | `____ ms` |

### 9-4. 4-2 정확성

| 쿼리 | 반환 행수 |
|---|---|
| `WHERE nickname = COALESCE(NULL, nickname)` | **800,000** |
| 조건 없음 (동적 SQL) | **1,000,000** |
| 차이 | **200,000 (nickname IS NULL)** |

---

## 10. 애플리케이션 연동

### 10-1. MyBatis — `COALESCE(#{param}, col)`을 `<if>`로 대체

**바꾸기 전 (안티패턴)**

```xml
<select id="findMembers" resultType="LabMember">
  SELECT id, name, nickname, grade
  FROM   lab_member
  WHERE  nickname = COALESCE(#{nickname}, nickname)
    AND  grade    = COALESCE(#{grade}, grade)
</select>
```

조건이 늘수록 Seq Scan이 확정되고, 파라미터가 null일 때 해당 컬럼이 NULL인 행이 조용히 누락된다(4-2(c)).

**바꾼 뒤**

```xml
<select id="findMembers" parameterType="MemberSearchCondition" resultType="LabMember">
  SELECT id, name, nickname, grade
  FROM   lab_member
  <where>
    <if test="nickname != null and nickname != ''">
      AND nickname = #{nickname}
    </if>
    <if test="grade != null and grade != ''">
      AND grade = #{grade}
    </if>
  </where>
  ORDER BY id
  LIMIT #{limit} OFFSET #{offset}
</select>
```

- MyBatis는 `#{}`를 바인드 파라미터(`?`)로 넘기므로, 조건이 붙은 SQL만 플래너에 도달한다.
- `<if>`로 조건 자체가 빠지면 인덱스 선택은 나머지 조건 기준으로 정상 계산된다.
- 주의: `${}`(문자열 치환)는 SQL 인젝션 위험이 있으므로 쓰지 않는다.

**조건 DTO (Java 7 호환)**

```java
package com.example.lab.member;

public class MemberSearchCondition {

    private String nickname;
    private String grade;
    private int limit = 20;
    private int offset = 0;

    public MemberSearchCondition() {
    }

    public MemberSearchCondition(String nickname, String grade, int limit, int offset) {
        this.nickname = nickname;
        this.grade = grade;
        this.limit = limit;
        this.offset = offset;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }
}
```

**매퍼 인터페이스**

```java
package com.example.lab.member;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MemberMapper {

    List<LabMember> findMembers(MemberSearchCondition condition);
}
```

### 10-2. JPA — Criteria로 조건을 동적으로 붙인다

JPQL에도 `COALESCE`가 있지만(`coalesce(m.nickname, m.name)`), **조건절에 쓰면 PostgreSQL 쪽에서 그대로 표현식이 되어 4-1과 같은 계획**이 나온다. 선택적 조건은 Criteria로 붙인다.

```java
package com.example.lab.member;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MemberQueryRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * 조건이 null이면 WHERE 절에서 아예 빠진다.
     * COALESCE(:param, column) 관용구를 쓰지 않는 이유는 두 가지다.
     *  1) 우변에 컬럼이 남아 인덱스 탐색 조건(Index Cond)을 만들 수 없다.
     *  2) param이 null일 때 column = column 이 되어 column이 NULL인 행이 탈락한다.
     */
    @Transactional(readOnly = true)
    public List<LabMember> findMembers(MemberSearchCondition condition) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<LabMember> cq = cb.createQuery(LabMember.class);
        Root<LabMember> root = cq.from(LabMember.class);

        List<Predicate> predicates = new ArrayList<Predicate>();

        String nickname = condition.getNickname();
        if (nickname != null && !nickname.isEmpty()) {
            predicates.add(cb.equal(root.get("nickname"), nickname));
        }

        String grade = condition.getGrade();
        if (grade != null && !grade.isEmpty()) {
            predicates.add(cb.equal(root.get("grade"), grade));
        }

        if (!predicates.isEmpty()) {
            cq.where(predicates.toArray(new Predicate[predicates.size()]));
        }
        cq.orderBy(cb.asc(root.get("id")));

        TypedQuery<LabMember> query = em.createQuery(cq);
        query.setFirstResult(condition.getOffset());
        query.setMaxResults(condition.getLimit());
        return query.getResultList();
    }
}
```

**버전 메모**

- 위 코드는 Java 7 문법으로만 작성했다(`new ArrayList<LabMember>()`, `toArray(new Predicate[size])`). Java 8+에서는 `new ArrayList<>()`와 `toArray(new Predicate[0])`로 줄여도 된다.
- Spring Boot 3.x / Jakarta EE 9+ 환경이면 `javax.persistence.*` → **`jakarta.persistence.*`**로 바꿔야 한다. Java 7~8 + Spring 3.x 레거시 모듈이 섞여 있다면 모듈별로 import가 갈린다.

### 10-3. 표시용 COALESCE는 조회 컬럼에만 남긴다

표시 목적(`display_name`)의 `COALESCE`는 `SELECT` 목록에 있는 한 인덱스 선택에 영향을 주지 않는다. **조건절과 정렬절로 넘어갈 때만 문제가 된다.**

```java
package com.example.lab.member;

public class MemberView {

    private final Long id;
    private final String displayName;

    public MemberView(Long id, String nickname, String name) {
        this.id = id;
        // DB의 COALESCE를 애플리케이션으로 옮긴 형태.
        // 조건/정렬에 쓰이지 않는 표시용 폴백이면 이쪽이 플래너에 부담을 주지 않는다.
        if (nickname != null && !nickname.isEmpty()) {
            this.displayName = nickname;
        } else if (name != null && !name.isEmpty()) {
            this.displayName = name;
        } else {
            this.displayName = "익명";
        }
    }

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}
```

다만 **정렬 키로도 쓰인다면** 애플리케이션으로 옮기면 안 된다. 페이징이 깨진다. 그때는 6-4의 표현식 인덱스나 생성 컬럼을 쓴다.

### 10-4. `@Transactional`과 대량 UPDATE

6-7의 `UPDATE lab_member SET memo = NULL WHERE memo = ''`를 JPA로 돌릴 때는 벌크 연산을 쓰고 영속성 컨텍스트를 비운다.

```java
package com.example.lab.member;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberMaintenanceService {

    @PersistenceContext
    private EntityManager em;

    /**
     * 빈 문자열을 NULL로 정리한다.
     * 주의: 벌크 UPDATE는 영속성 컨텍스트를 우회하므로 실행 후 clear()가 필요하다.
     *       100만 행을 한 트랜잭션에서 갱신하면 같은 수의 데드 튜플이 생기고
     *       autovacuum이 따라오지 못한다. 실제 운영에서는 id 범위로 쪼개 여러 트랜잭션으로 나눈다.
     */
    @Transactional
    public int normalizeEmptyMemo(long fromId, long toId) {
        int updated = em.createQuery(
                "UPDATE LabMember m SET m.memo = NULL "
                        + "WHERE m.memo = '' AND m.id BETWEEN :fromId AND :toId")
                .setParameter("fromId", fromId)
                .setParameter("toId", toId)
                .executeUpdate();
        em.clear();
        return updated;
    }
}
```

---

## 11. 변형 실습

### 변형 1 — 표현식 인덱스를 만들어 두고 인자 순서를 바꿔 조회한다

```sql
-- 인덱스: ((COALESCE(nickname, name)))
EXPLAIN SELECT id FROM lab_member WHERE COALESCE(name, nickname) = 'nick_777';
```

**예상**: 인덱스를 쓰지 않는다. `Seq Scan` + `Filter`. 플래너는 표현식 트리가 **동등한지**를 구조적으로 비교하므로 인자 순서가 다르면 다른 표현식이다. `pg_stat_all_indexes.idx_scan`이 증가하지 않는 것으로 확인할 수 있다. 실무에서 "인덱스 만들었는데 왜 안 타요"의 가장 흔한 원인이다.

### 변형 2 — 정렬 방향과 NULLS 위치를 어긋나게 한다

```sql
-- 인덱스: ((COALESCE(last_login_at, created_at)) DESC)
EXPLAIN SELECT id FROM lab_member
ORDER BY COALESCE(last_login_at, created_at) ASC LIMIT 20;

EXPLAIN SELECT id FROM lab_member
ORDER BY COALESCE(last_login_at, created_at) DESC NULLS FIRST LIMIT 20;
```

**예상**:
- 첫 번째는 같은 인덱스를 **역방향(Index Scan Backward)**으로 읽어 `Sort` 없이 처리될 가능성이 높다. B-tree는 양방향 스캔이 가능하기 때문이다.
- 두 번째는 NULLS 위치가 인덱스 정의와 다르다. `DESC`의 기본은 `NULLS FIRST`이므로 이 경우엔 오히려 일치할 수 있다 — **직접 계획을 확인할 것.** 이 조합은 헷갈리기 쉬워서 반드시 `EXPLAIN`으로 검증하는 습관이 필요하다. 다만 이 실습 데이터에서 `COALESCE(last_login_at, created_at)`은 `created_at`이 NOT NULL이라 **결과가 절대 NULL이 아니므로** NULLS 위치가 실제 순서를 바꾸지 않는다.

### 변형 3 — `IS NOT DISTINCT FROM`으로 바꿔 본다

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM lab_member WHERE nickname IS NOT DISTINCT FROM 'nick_777';

EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM lab_member WHERE nickname IS NOT DISTINCT FROM NULL;
```

**예상**: 의미는 "NULL도 같은 값으로 취급하는 `=`"라 `COALESCE`로 NULL을 상수로 치환하는 트릭보다 정확하지만, **인덱스 탐색 조건으로 내려가지 않아** `Seq Scan` + `Filter`가 된다. 정확성은 얻고 성능은 못 얻는 선택지다. 두 번째 쿼리는 `nickname IS NULL`과 같은 200,000행을 반환하지만, `WHERE nickname IS NULL`로 쓰면 인덱스를 탈 수 있다는 점을 비교한다.

---

## 12. 정리

```sql
-- 실습으로 만든 객체와 설정을 모두 되돌린다.
-- 운영 경고: DROP은 ACCESS EXCLUSIVE 락이다. lab_ 접두어 객체만 지우는지 반드시 확인할 것.

-- 1) 확장 통계 (PG14+에서 만들었다면)
DROP STATISTICS IF EXISTS lab_member_display_stat;

-- 2) 인덱스 (테이블을 지우면 함께 사라지지만, 테이블을 남길 경우를 위해 명시)
DROP INDEX IF EXISTS lab_member_display_idx;
DROP INDEX IF EXISTS lab_member_lastseen_idx;
DROP INDEX IF EXISTS lab_member_nickname_idx;
DROP INDEX IF EXISTS lab_member_name_idx;
DROP INDEX IF EXISTS lab_member_lastlogin_idx;
DROP INDEX IF EXISTS lab_order_member_idx;

-- 3) 테이블 (FK 때문에 순서를 지킨다)
DROP TABLE IF EXISTS lab_order;
DROP TABLE IF EXISTS lab_profile;
DROP TABLE IF EXISTS lab_member;

-- 4) 준비된 문이 남아 있다면
-- DEALLOCATE ALL;

-- 5) 세션 파라미터 원복
RESET jit;
RESET max_parallel_workers_per_gather;
RESET work_mem;
RESET track_io_timing;

-- 6) pg_stat_statements 누적치를 초기화하고 싶다면 (권한 필요, 전역 영향)
-- SELECT pg_stat_statements_reset();

-- 7) 확장은 다른 실습에서도 쓰므로 기본적으로 남겨 둔다.
--    완전히 지우려면:
-- DROP EXTENSION IF EXISTS pgstattuple;
-- DROP EXTENSION IF EXISTS pg_stat_statements;
```

정리 확인:

```sql
SELECT relname FROM pg_stat_user_tables WHERE relname LIKE 'lab_%';   -- 0 rows
SELECT indexname FROM pg_indexes WHERE indexname LIKE 'lab_%';        -- 0 rows
```
