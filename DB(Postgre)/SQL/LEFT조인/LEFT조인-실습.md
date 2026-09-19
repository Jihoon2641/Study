---
주제: LEFT JOIN (Left Outer Join)의 의미와 PostgreSQL 내부 처리
폴더: SQL/LEFT조인
분류: PostgreSQL / SQL 처리·조인
난이도: 중급
관련버전: PostgreSQL 12+ (Hash Right Anti Join 관찰은 16+)
작성일: 2026-09-13
선행지식: [INNER JOIN, EXPLAIN 읽기, NULL과 3값 논리, B-tree 인덱스 기본]
---

# LEFT JOIN — 실습

관련 문서: [이론](./LEFT조인-이론.md)

> 전제: PostgreSQL 16, 로컬 단일 인스턴스, `shared_buffers = 128MB`(기본값), SSD. 병렬 쿼리와 JIT은 끄고 측정한다.
>
> **이 문서의 실행 결과 수치는 작성 환경에서 측정하지 않았다.** 8장은 기대되는 노드 구조와 논리적으로 결정되는 행 수만 채웠고, 시간·버퍼 수치는 직접 실행 후 기록하는 칸으로 비워 두었다.

---

## 1. 실습 개요

| # | 재현 내용 | 수치로 증명하는 것 |
|---|---|---|
| 4-1 | `COUNT(*)` vs `COUNT(o.id)` | 주문 0건 회원 30,000명의 `cnt_star = 1`, `cnt_order = 0` |
| 4-2 | WHERE에 B 조건 → INNER 축소 | 반환 행 1,030,000 → 약 900,000, 노드명에서 `Left`/`Right` 소실 |
| 4-3 | ON에 A 조건 → 필터 안 됨 | DORMANT 회원이 NULL 확장으로 남음, `Join Filter` / `Rows Removed by Join Filter` |
| 4-4 | 단건 LEFT JOIN, B 조인 컬럼 인덱스 없음 | inner `Seq Scan`의 `Rows Removed by Filter` ≈ 999,986, 버퍼 수 |
| 4-5 | 1:N LEFT JOIN + LIMIT | LIMIT 20인데 회원 수는 20 미만 |
| 4-6 | 미사용 LEFT JOIN 제거 | 유니크 인덱스 전: 조인 노드 존재 / 후: `lab_member_profile` 노드 소멸 |
| 4-7 | "주문 없는 회원" 3가지 작성법 | `Anti Join` 변환 여부, `NOT IN` + NULL 1건 → 0행 |
| 4-8 | 조인 키 양쪽에 NULL | INNER 1행 / LEFT 4행 / FULL 7행 / `IS NOT DISTINCT FROM` 6행, `enable_nestloop=off`에도 Nested Loop 잔존 |

---

## 2. 환경

| 항목 | 값 |
|---|---|
| PostgreSQL | 16.x (12~15에서도 4-1~4-6은 동일하게 재현. 4-7의 `Hash Right Anti Join`만 16+) |
| 클라이언트 | psql (메타 명령 `\timing`, `\d+` 사용 — psql 전용) |
| 확장 | `pg_stat_statements` (선택. 10장 관측 쿼리용) |

### 확장 설치 (선택)

`pg_stat_statements`는 `shared_preload_libraries`에 등록 후 **서버 재시작**이 필요하다.

```conf
# postgresql.conf
shared_preload_libraries = 'pg_stat_statements'   # 서버 재시작 필요
```

```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

### 실습용 세션 파라미터 (세션 한정, 원복: `RESET`)

```sql
SET max_parallel_workers_per_gather = 0;  -- 기본 2. 병렬 노드(Gather)를 없애 조인 노드만 보이게
SET jit = off;                            -- 기본 on (PG12+). JIT 컴파일 시간이 측정에 섞이지 않게
SET work_mem = '4MB';                     -- 기본 4MB. 명시적으로 고정
-- hash_mem_multiplier 는 기본값(PG15+: 2.0) 유지
```

원복:

```sql
RESET max_parallel_workers_per_gather;
RESET jit;
RESET work_mem;
```

---

## 3. 재현 데이터 생성

| 테이블 | 행 수 | 설계 의도 |
|---|---|---|
| `lab_member` | 100,000 | 80% ACTIVE, 20% DORMANT. id 70,001~100,000 (30,000명)은 주문이 없음 |
| `lab_order` | 1,000,000 | `member_id`는 1~70,000에 균등 분포(회원당 평균 약 14.3건), 90% PAID / 10% CANCELLED |
| `lab_member_profile` | 50,000 | 짝수 id 회원만 프로필 보유. **처음에는 `member_id`에 인덱스·UNIQUE 없음** |

**규모 이유**

- 100,000 × 1,000,000이면 전체 LEFT JOIN에서 Nested Loop이 비용상 불리해 해시 조인이 선택되고, 단건 조회에서는 inner `Seq Scan` 비용(100만 행 필터)이 수치로 크게 드러난다.
- 회원 1~70,000이 각각 주문 0건일 확률은 `(1 - 1/70000)^1000000 ≈ e^-14.3 ≈ 6×10^-7`이므로, **주문 없는 회원은 사실상 정확히 30,000명**이다. 결과 행 수를 논리적으로 예측할 수 있다.
- `lab_member` 해시 테이블 크기가 `work_mem × hash_mem_multiplier = 8MB` 부근이라 `Batches` 값 변화를 관찰할 여지가 있다 (실제 값은 행 폭에 따라 다름).

```sql
-- ============================================================
-- 3. 시드 데이터
-- ⚠ DROP TABLE: 운영이라면 ACCESS EXCLUSIVE 락. 실습 DB에서만 실행
-- ============================================================
DROP TABLE IF EXISTS lab_order, lab_member_profile, lab_member;

CREATE TABLE lab_member (
    id          bigint      PRIMARY KEY,
    name        text        NOT NULL,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE TABLE lab_order (
    id          bigint        PRIMARY KEY,
    member_id   bigint        NOT NULL REFERENCES lab_member(id),  -- FK는 member_id 인덱스를 만들지 않는다
    amount      numeric(12,2) NOT NULL,
    status      text          NOT NULL,
    ordered_at  timestamptz   NOT NULL
);

CREATE TABLE lab_member_profile (
    member_id   bigint NOT NULL,   -- 의도적으로 PK/UNIQUE/인덱스 없음 (4-6에서 추가)
    nickname    text,
    bio         text
);

SELECT setseed(0.42);  -- 같은 세션에서 이어서 실행해야 random() 재현

INSERT INTO lab_member (id, name, status, created_at)
SELECT g,
       'member_' || g,
       CASE WHEN g % 10 < 8 THEN 'ACTIVE' ELSE 'DORMANT' END,
       now() - make_interval(mins => g)
FROM generate_series(1, 100000) AS g;

INSERT INTO lab_order (id, member_id, amount, status, ordered_at)
SELECT g,
       1 + floor(random() * 70000)::bigint,             -- 1 ~ 70,000
       round((random() * 100000)::numeric, 2),
       CASE WHEN random() < 0.9 THEN 'PAID' ELSE 'CANCELLED' END,
       now() - make_interval(secs => random() * 365 * 86400)
FROM generate_series(1, 1000000) AS g;

INSERT INTO lab_member_profile (member_id, nickname, bio)
SELECT g, 'nick_' || g, repeat('x', 200)
FROM generate_series(2, 100000, 2) AS g;

-- 대량 INSERT 직후에는 autovacuum이 아직 ANALYZE 하지 않았을 수 있으므로 반드시 수동 실행.
-- VACUUM은 가시성 맵을 채워 이후 측정의 버퍼 수치를 안정시킨다.
-- ⚠ VACUUM/ANALYZE: SHARE UPDATE EXCLUSIVE 락 (DML은 막지 않음, DDL·다른 VACUUM과 충돌)
VACUUM (ANALYZE) lab_member, lab_order, lab_member_profile;
```

검증:

```sql
SELECT
  (SELECT count(*) FROM lab_member)                                   AS members,         -- 100000
  (SELECT count(*) FROM lab_order)                                    AS orders,          -- 1000000
  (SELECT count(*) FROM lab_member m
     WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.member_id = m.id)) AS no_order,  -- 30000 (예상)
  (SELECT count(*) FROM lab_order WHERE status = 'PAID')              AS paid_orders;     -- 약 900000
```

**`ANALYZE`를 다시 돌려야 하는 시점**: 시드 직후 1회. 4-4의 `CREATE INDEX`, 4-6의 `CREATE UNIQUE INDEX` 뒤에는 필요 없다(B-tree 일반 컬럼 인덱스는 테이블 통계를 그대로 사용). 데이터를 다시 넣었다면 다시 실행.

---

## 4. 문제 상황 재현

모든 측정은 psql에서 `\timing on`(psql 전용) 후 **같은 쿼리를 3회 실행해 마지막 값**을 기록한다. 1회차는 캐시 워밍 효과가 섞인다.

### 4-1. 회원별 주문 수 — `COUNT(*)` 함정

```sql
SELECT m.id,
       COUNT(*)    AS cnt_star,
       COUNT(o.id) AS cnt_order
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id BETWEEN 69996 AND 70005
GROUP BY m.id
ORDER BY m.id;
```

예상 결과(논리적으로 결정됨):

| id | cnt_star | cnt_order |
|---|---|---|
| 69996 ~ 70000 | n (≥1) | n (cnt_star와 동일) |
| 70001 ~ 70005 | **1** | **0** |

### 4-2. WHERE에 B 조건 → INNER JOIN 축소

```sql
-- (a) 기준: 조건 없음
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id;

-- (b) 흔한 실수: PAID만 붙이려고 WHERE에 작성
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE o.status = 'PAID';

-- 주문 없는 회원이 몇 명 남았는가
SELECT count(DISTINCT m.id) FILTER (WHERE o.id IS NULL) AS null_extended_members, count(*) AS total_rows
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE o.status = 'PAID';
```

기록할 값:

| 쿼리 | 최상위 조인 노드명 | actual rows | 실행 시간 | shared hit / read |
|---|---|---|---|---|
| (a) | `Hash Left Join` 또는 `Hash Right Join` | 1,030,000 | | |
| (b) | `Hash Join` (Left/Right 없음) | 약 900,000 | | |

(b)의 `null_extended_members`는 **0**이어야 한다.

### 4-3. ON에 A 조건 → 필터 안 됨

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.status, o.id
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id AND m.status = 'ACTIVE'
WHERE m.id BETWEEN 1 AND 1000;

SELECT m.status, count(*) AS rows, count(o.id) AS matched
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id AND m.status = 'ACTIVE'
WHERE m.id BETWEEN 1 AND 1000
GROUP BY m.status;
```

예상: `DORMANT` 200명이 `rows = 200`, `matched = 0`으로 남는다. 조인 노드에 `Join Filter: (m.status = 'ACTIVE'::text)`.

### 4-4. 단건 LEFT JOIN — B 조인 컬럼 인덱스 없음

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name, o.id, o.amount, o.status
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id = 12345;
```

예상 구조: `Nested Loop Left Join` → outer `Index Scan using lab_member_pkey`, inner `Seq Scan on lab_order` (`Filter: (member_id = 12345)`, `Rows Removed by Filter` ≈ 1,000,000 - n).
(플래너가 해시 조인을 고를 수도 있다. 어떤 노드든 **lab_order를 100만 행 읽는다**는 점이 핵심.)

### 4-5. 1:N LEFT JOIN + LIMIT 페이징

```sql
-- "회원 20명 페이지"를 의도한 쿼리
SELECT m.id, o.id AS order_id
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
ORDER BY m.id, o.id
LIMIT 20;

-- 실제 회원 수
SELECT count(DISTINCT id) AS members_in_page
FROM (
  SELECT m.id
  FROM lab_member m
  LEFT JOIN lab_order o ON o.member_id = m.id
  ORDER BY m.id, o.id
  LIMIT 20
) t;
```

예상: 회원 1명당 평균 14.3건이므로 `members_in_page`는 1~2.

### 4-6. 미사용 LEFT JOIN

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name
FROM lab_member m
LEFT JOIN lab_member_profile p ON p.member_id = m.id;
```

예상: `Hash Left Join`(또는 `Hash Right Join`)이 남아 `lab_member_profile`을 50,000행 스캔한다. p의 컬럼을 하나도 쓰지 않는데도.

### 4-7. "주문 없는 회원" 3가지 작성법

```sql
-- (a) LEFT JOIN + 조인 키 IS NULL
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE o.member_id IS NULL;

-- (b) LEFT JOIN + 조인 조건에 없는 컬럼 IS NULL
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE o.status IS NULL;

-- (c) NOT EXISTS
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m
WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.member_id = m.id);

-- (d) NOT IN
-- ⚠ 서브쿼리 결과(100만 값)의 해시 테이블이 work_mem × hash_mem_multiplier(8MB)에 안 들어간다고
--   추정되면 플래너는 hashed SubPlan 대신 일반 SubPlan을 고르고, 회원 1행마다 100만 행을 훑어
--   사실상 끝나지 않는다. 먼저 EXPLAIN(ANALYZE 없이)으로 "hashed" 여부를 확인하고,
--   hashed가 아니면 아래처럼 트랜잭션 한정으로 work_mem을 올린다.
EXPLAIN
SELECT m.id FROM lab_member m
WHERE m.id NOT IN (SELECT o.member_id FROM lab_order o);

BEGIN;
SET LOCAL work_mem = '128MB';   -- 트랜잭션 종료 시 자동 원복
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m
WHERE m.id NOT IN (SELECT o.member_id FROM lab_order o);
COMMIT;
```

| 쿼리 | 예상 노드 | 반환 행 |
|---|---|---|
| (a) | `Hash Anti Join` 또는 `Hash Right Anti Join`(16+) | 30,000 |
| (b) | `Hash Left/Right Join` + `Filter: (o.status IS NULL)` | 30,000 (행 수는 같지만 매칭 100만 행을 만든 뒤 버림) |
| (c) | (a)와 동일 계열 | 30,000 |
| (d) | `Seq Scan` + `Filter: (NOT (hashed SubPlan 1))` (버전에 따라 SubPlan 표기 다름). `work_mem`이 작으면 `hashed`가 빠진 일반 SubPlan | 30,000 |

`NOT IN`은 NULL 의미 문제(6-6) 외에도 **서브쿼리 크기가 메모리 한도를 넘는 순간 알고리즘이 O(N×M)으로 바뀐다**는 두 번째 함정이 있다. Anti Join은 해시 배치 분할로 메모리를 넘어도 선형에 가깝게 동작한다.

### 4-8. 조인 키 양쪽에 NULL이 있을 때

결과를 눈으로 검증할 수 있도록 **4행짜리 테이블**을 따로 쓴다. 행 수가 적어 플래너는 대부분 Nested Loop를 고르므로, 알고리즘 제약은 `enable_nestloop = off`로 확인한다. 아래 결과 행은 SQL 의미로 결정되는 값이다.

```sql
-- ⚠ DROP TABLE: ACCESS EXCLUSIVE
DROP TABLE IF EXISTS lab_null_a, lab_null_b;
CREATE TABLE lab_null_a (id int PRIMARY KEY, k int);
CREATE TABLE lab_null_b (id int PRIMARY KEY, k int);
INSERT INTO lab_null_a VALUES (1, 1), (2, 2), (3, NULL), (4, NULL);
INSERT INTO lab_null_b VALUES (10, 1), (20, NULL), (30, NULL), (40, 5);
ANALYZE lab_null_a, lab_null_b;

-- (a) INNER            → 1행: (1,10)
SELECT a.id AS a_id, a.k AS a_k, b.id AS b_id, b.k AS b_k
FROM lab_null_a a JOIN lab_null_b b ON a.k = b.k ORDER BY a.id, b.id;

-- (b) LEFT             → 4행: (1,10) (2,∅) (3,∅) (4,∅)
SELECT a.id AS a_id, a.k AS a_k, b.id AS b_id, b.k AS b_k
FROM lab_null_a a LEFT JOIN lab_null_b b ON a.k = b.k ORDER BY a.id, b.id;

-- (c) FULL             → 7행: (1,10) (2,∅) (3,∅) (4,∅) (∅,20) (∅,30) (∅,40)
SELECT a.id AS a_id, a.k AS a_k, b.id AS b_id, b.k AS b_k
FROM lab_null_a a FULL JOIN lab_null_b b ON a.k = b.k ORDER BY a.id NULLS LAST, b.id;

-- (d) IS NOT DISTINCT FROM → 6행: (1,10) (2,∅) (3,20) (3,30) (4,20) (4,30)
SELECT a.id AS a_id, a.k AS a_k, b.id AS b_id, b.k AS b_k
FROM lab_null_a a LEFT JOIN lab_null_b b ON a.k IS NOT DISTINCT FROM b.k ORDER BY a.id, b.id;

-- (e) "매칭 없음"과 "NULL과 매칭"을 구분: b.k 가 아니라 b.id(PK)로 판단
SELECT a.id, b.k IS NULL AS b_k_is_null, b.id IS NULL AS no_match
FROM lab_null_a a LEFT JOIN lab_null_b b ON a.k IS NOT DISTINCT FROM b.k ORDER BY a.id, b.id;
-- a.id=3,4 행: b_k_is_null = true, no_match = false  ← NULL과 매칭된 것
-- a.id=2  행: b_k_is_null = true, no_match = true   ← 매칭 없음

-- (f) 안티 조인 3종
SELECT a.id FROM lab_null_a a LEFT JOIN lab_null_b b ON a.k = b.k WHERE b.id IS NULL;           -- 2, 3, 4
SELECT a.id FROM lab_null_a a WHERE NOT EXISTS (SELECT 1 FROM lab_null_b b WHERE b.k = a.k);    -- 2, 3, 4
SELECT a.id FROM lab_null_a a WHERE a.k NOT IN (SELECT b.k FROM lab_null_b b);                  -- 0행 (b에 NULL 존재)
SELECT a.id FROM lab_null_a a WHERE a.k NOT IN (SELECT b.k FROM lab_null_b b WHERE b.k IS NOT NULL); -- 2 (a.k NULL인 3,4는 NULL 판정으로 제외)

-- (g) 알고리즘 제약: nestloop 를 꺼도 = 는 해시로 바뀌지만, IS NOT DISTINCT FROM 은 Nested Loop 가 남는다
SET enable_nestloop = off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM lab_null_a a LEFT JOIN lab_null_b b ON a.k = b.k;
-- 기대: Hash Left Join / Hash Right Join, Hash Cond: (a.k = b.k)

EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM lab_null_a a LEFT JOIN lab_null_b b ON a.k IS NOT DISTINCT FROM b.k;
-- 기대: Nested Loop Left Join, Join Filter: (NOT (a.k IS DISTINCT FROM b.k)), 비용이 비정상적으로 큼

EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM lab_null_a a LEFT JOIN lab_null_b b ON COALESCE(a.k, -1) = COALESCE(b.k, -1);
-- 기대: Hash Left/Right Join, Hash Cond: (COALESCE(a.k, '-1'::integer) = COALESCE(b.k, '-1'::integer)), 결과는 (d)와 같은 6행
RESET enable_nestloop;
```

| 확인 항목 | 기대값 |
|---|---|
| (b) LEFT JOIN에서 a의 NULL 키 행 | 남음 (B 컬럼 NULL) |
| (b)에서 b의 NULL 키 행(20, 30) | 결과에 없음 |
| (d) NULL 매칭 시 행 수 | 6 (NULL 2×2 곱집합) |
| (f) `NOT IN` + b에 NULL | 0행 |
| (g) `IS NOT DISTINCT FROM` + `enable_nestloop=off` | 여전히 Nested Loop |

---

## 5. 진단

결론이 아니라 **좁혀가는 순서**를 따라간다.

### 5-1. 1단계 — 노드 이름이 작성한 조인 타입과 일치하는가

`EXPLAIN`(ANALYZE 없이)만으로 확인 가능. 운영 쿼리라도 부담이 없다.

```sql
EXPLAIN SELECT m.id, o.id
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
WHERE o.status = 'PAID';
```

| 노드명에 포함된 단어 | 해석 |
|---|---|
| `Left Join` | 작성한 대로 유지 |
| `Right Join` | 좌우 반전(보존 쪽으로 해시/정렬). 의미는 동일 |
| `Anti Join` | `IS NULL` 패턴이 안티 조인으로 변환 |
| 아무것도 없음 (`Hash Join`) | **INNER로 축소** → WHERE에 널 허용 쪽 strict 조건이 있다 (4-2) |
| 조인 노드 자체가 없음 | 조인 제거 |

### 5-2. 2단계 — 조건이 어디에 붙었는가

`EXPLAIN (ANALYZE, BUFFERS)`에서 각 조건의 위치를 찾는다.

```
스캔 노드의 Filter          → push down 성공 (스캔에서 미리 걸러짐)
조인 노드의 Hash Cond/Merge Cond → 해시/머지 키
조인 노드의 Join Filter      → ON에서 온 나머지 조건 (4-3의 m.status)
조인 노드의 Filter           → WHERE에서 온 조건 중 조인 위에서만 평가 가능한 것 (4-7(b))
```

`Rows Removed by Join Filter` 또는 조인 노드의 `Rows Removed by Filter`가 actual rows보다 크면, 조건 위치를 먼저 의심한다.

### 5-3. 3단계 — 어느 자식 노드가 비싼가

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name, o.id, o.amount, o.status
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id = 12345;
```

- 자식 노드별 `actual time`의 두 번째 값(마지막 행 반환 시각) × `loops`로 누적 시간을 본다.
- inner 스캔의 `Buffers: shared hit + read`가 `lab_order` 전체 페이지 수와 비슷하면 전체 스캔이다.

```sql
-- lab_order 전체 페이지 수 (버퍼 수치와 비교할 기준)
SELECT relpages, reltuples::bigint, pg_size_pretty(pg_relation_size('lab_order')) AS size
FROM pg_class WHERE relname = 'lab_order';
```

### 5-4. 4단계 — 통계 뷰로 반복성 확인

EXPLAIN은 한 번의 실행이다. 애플리케이션에서 반복 호출될 때도 같은 일이 생기는지 통계 뷰로 확인한다.

```sql
-- (1) 스냅숏
SELECT relname, seq_scan, seq_tup_read, idx_scan
FROM pg_stat_user_tables WHERE relname = 'lab_order';

-- (2) 4-4 쿼리를 5회 실행 (EXPLAIN 없이)
SELECT m.id, o.id FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id WHERE m.id = 12345;
-- ... 5회 반복

-- (3) 통계 스냅숏 캐시 제거 후 재조회
--     PG15+ 에서 누적 통계는 공유 메모리에 수 초 간격으로 반영되므로 잠시 후 조회
SELECT pg_stat_clear_snapshot();
SELECT relname, seq_scan, seq_tup_read, idx_scan
FROM pg_stat_user_tables WHERE relname = 'lab_order';
```

판단: `seq_scan` 증가분 = 호출 횟수(5), `seq_tup_read` 증가분 ≈ 5 × 1,000,000 이면 호출마다 전체 스캔.

### 5-5. 5단계 — 원인 후보 확인

```sql
-- B 조인 컬럼에 인덱스가 있는가
SELECT indexrelid::regclass AS index_name, indisunique, indimmediate, pg_get_indexdef(indexrelid)
FROM pg_index
WHERE indrelid IN ('lab_order'::regclass, 'lab_member_profile'::regclass);

-- 조인 컬럼 통계가 실제와 맞는가 (1:N 증식 추정 근거)
SELECT tablename, attname, n_distinct, null_frac
FROM pg_stats
WHERE tablename IN ('lab_order', 'lab_member_profile') AND attname = 'member_id';

SELECT count(DISTINCT member_id) FROM lab_order;          -- 실측 약 70000
SELECT count(DISTINCT member_id) FROM lab_member_profile; -- 실측 50000
```

- `lab_order.member_id`: 인덱스 없음 → 4-4 원인
- `lab_member_profile.member_id`: 유니크 인덱스 없음 → 4-6 원인
- `pg_stats.n_distinct`가 음수면 "행 수 대비 비율"(`-1` = 전부 유일). `lab_member_profile`은 -1에 가깝게 나와도, **통계상 유일은 조인 제거 근거가 되지 않는다** (인덱스만 인정).

---

## 6. 개선

### 6-1. (4-2 개선) 조건을 ON으로

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id AND o.status = 'PAID';
```

노드 변화:

| 위치 | 4-2 (b) | 6-1 |
|---|---|---|
| 조인 노드 | `Hash Join` | `Hash Left Join` / `Hash Right Join` 복귀 |
| `lab_order` 스캔 | `Filter: (status = 'PAID')` | `Filter: (status = 'PAID')` — **동일하게 push down** |
| actual rows | 약 900,000 | 약 930,000 (PAID 약 900,000 + 주문 없는 회원 30,000 + PAID 주문이 없는 회원 ≈ 0) |

ON에 둔 널 허용 쪽 조건도 스캔으로 내려가므로, 스캔 비용은 거의 같고 결과 의미만 달라진다.

### 6-2. (4-3 개선) A 조건을 WHERE로

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.status, o.id
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id BETWEEN 1 AND 1000
  AND m.status = 'ACTIVE';
```

노드 변화: 조인 노드의 `Join Filter` 소멸 → `lab_member` 스캔 노드에 `Filter: (status = 'ACTIVE')` 추가. outer 행 수 1,000 → 800.

### 6-3. (4-4 개선) B 조인 컬럼 인덱스

```sql
-- ⚠ CREATE INDEX: lab_order에 SHARE 락 → 인덱스 생성 동안 INSERT/UPDATE/DELETE 전부 대기.
--   운영에서는 CREATE INDEX CONCURRENTLY 사용 (SHARE UPDATE EXCLUSIVE, 트랜잭션 블록 안에서 실행 불가)
CREATE INDEX lab_order_member_id_idx ON lab_order (member_id);

EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name, o.id, o.amount, o.status
FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id = 12345;
```

노드 변화:

| 위치 | 4-4 | 6-3 |
|---|---|---|
| 조인 노드 | `Nested Loop Left Join` (또는 해시) | `Nested Loop Left Join` |
| inner | `Seq Scan on lab_order` + `Rows Removed by Filter` ≈ 999,986 | `Bitmap Heap Scan` + `Bitmap Index Scan on lab_order_member_id_idx` 또는 `Index Scan` |
| inner `Buffers` | lab_order 전체 페이지 수 수준 | 인덱스 2~3페이지 + 힙 최대 n페이지 (n = 해당 회원 주문 수) |

### 6-4. (4-5 개선) 보존 쪽을 먼저 페이징

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, o.id AS order_id
FROM (
  SELECT id FROM lab_member ORDER BY id LIMIT 20
) p
LEFT JOIN lab_order o ON o.member_id = p.id
ORDER BY p.id, o.id;

-- 회원 수 확인 → 20
SELECT count(DISTINCT id) FROM (
  SELECT p.id FROM (SELECT id FROM lab_member ORDER BY id LIMIT 20) p
  LEFT JOIN lab_order o ON o.member_id = p.id
) t;
```

노드 변화: outer가 `Limit → Index Only Scan using lab_member_pkey`(20행)로 줄고, 조인은 `Nested Loop Left Join`(loops=20) + inner 인덱스 스캔. 4-5에서는 전체 조인(또는 정렬) 후 Limit이었다.

"회원당 최근 주문 3건"처럼 B 쪽에도 행 제한이 필요하면 `LATERAL`:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, o.id AS order_id, o.ordered_at
FROM (SELECT id FROM lab_member ORDER BY id LIMIT 20) p
LEFT JOIN LATERAL (
  SELECT id, ordered_at FROM lab_order
  WHERE member_id = p.id
  ORDER BY ordered_at DESC
  LIMIT 3
) o ON true
ORDER BY p.id, o.ordered_at DESC;
```

(`(member_id, ordered_at)` 복합 인덱스가 있으면 inner의 정렬이 사라진다. 인덱스 컬럼 순서 원리는 인덱스 문서에서 다룬다.)

### 6-5. (4-6 개선) 유니크 인덱스로 조인 제거

```sql
-- ⚠ CREATE UNIQUE INDEX: SHARE 락. 운영에서는 CONCURRENTLY.
--   중복 데이터가 있으면 실패하므로 먼저 확인: SELECT member_id FROM lab_member_profile GROUP BY 1 HAVING count(*) > 1;
CREATE UNIQUE INDEX lab_member_profile_member_id_uk ON lab_member_profile (member_id);

EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name
FROM lab_member m
LEFT JOIN lab_member_profile p ON p.member_id = m.id;
```

노드 변화: 조인 노드와 `lab_member_profile` 스캔이 **계획에서 사라지고** `Seq Scan on lab_member` 하나만 남는다.

반례 확인 — p 컬럼을 하나라도 쓰면 제거되지 않는다:

```sql
EXPLAIN SELECT m.id, m.name, p.nickname
FROM lab_member m LEFT JOIN lab_member_profile p ON p.member_id = m.id;
```

### 6-6. (4-7 개선) 조인 키 `IS NULL` 또는 `NOT EXISTS`

4-7 (b) → (a) 또는 (c)로 바꾸면 조인 노드명이 `Anti`로 바뀌고, 조인 노드 아래의 `Rows Removed by Filter`(약 1,000,000)가 사라진다.

`NOT IN`의 NULL 함정 재현:

```sql
BEGIN;
-- ⚠ ALTER TABLE ... DROP NOT NULL: ACCESS EXCLUSIVE 락
ALTER TABLE lab_order ALTER COLUMN member_id DROP NOT NULL;
INSERT INTO lab_order (id, member_id, amount, status, ordered_at)
VALUES (1000001, NULL, 0, 'PAID', now());
SET LOCAL work_mem = '128MB';  -- hashed SubPlan 유지용 (4-7 (d) 경고 참고)

SELECT count(*) FROM lab_member m WHERE m.id NOT IN (SELECT o.member_id FROM lab_order o);           -- 0
SELECT count(*) FROM lab_member m WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.member_id = m.id); -- 30000
ROLLBACK;  -- 컬럼 제약과 삽입 행 모두 원복
```

---

## 7. 전체 스크립트

```sql
-- ============================================================
-- LEFT JOIN 실습 전체 스크립트 (psql 에서 실행)
-- 실행 순서:
--   0. 세션 설정
--   1. 시드 생성 + VACUUM ANALYZE
--   2. 문제 재현 4-1 ~ 4-7
--   3. 개선 6-1 ~ 6-6
--   4. 정리 (12장 스크립트)
-- ⚠ 실습 전용 DB에서만 실행. DROP/CREATE INDEX/ALTER TABLE 포함.
-- ============================================================

-- 0. 세션 설정 --------------------------------------------------
\timing on
SET max_parallel_workers_per_gather = 0;
SET jit = off;
SET work_mem = '4MB';

-- 1. 시드 ------------------------------------------------------
-- ⚠ DROP TABLE: ACCESS EXCLUSIVE
DROP TABLE IF EXISTS lab_order, lab_member_profile, lab_member;

CREATE TABLE lab_member (
    id          bigint      PRIMARY KEY,
    name        text        NOT NULL,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE TABLE lab_order (
    id          bigint        PRIMARY KEY,
    member_id   bigint        NOT NULL REFERENCES lab_member(id),
    amount      numeric(12,2) NOT NULL,
    status      text          NOT NULL,
    ordered_at  timestamptz   NOT NULL
);

CREATE TABLE lab_member_profile (
    member_id   bigint NOT NULL,
    nickname    text,
    bio         text
);

SELECT setseed(0.42);

INSERT INTO lab_member (id, name, status, created_at)
SELECT g, 'member_' || g,
       CASE WHEN g % 10 < 8 THEN 'ACTIVE' ELSE 'DORMANT' END,
       now() - make_interval(mins => g)
FROM generate_series(1, 100000) AS g;

INSERT INTO lab_order (id, member_id, amount, status, ordered_at)
SELECT g, 1 + floor(random() * 70000)::bigint,
       round((random() * 100000)::numeric, 2),
       CASE WHEN random() < 0.9 THEN 'PAID' ELSE 'CANCELLED' END,
       now() - make_interval(secs => random() * 365 * 86400)
FROM generate_series(1, 1000000) AS g;

INSERT INTO lab_member_profile (member_id, nickname, bio)
SELECT g, 'nick_' || g, repeat('x', 200)
FROM generate_series(2, 100000, 2) AS g;

-- ⚠ SHARE UPDATE EXCLUSIVE
VACUUM (ANALYZE) lab_member, lab_order, lab_member_profile;

SELECT
  (SELECT count(*) FROM lab_member) AS members,
  (SELECT count(*) FROM lab_order)  AS orders,
  (SELECT count(*) FROM lab_member m
     WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.member_id = m.id)) AS no_order;

-- 2. 문제 재현 ---------------------------------------------------
-- 4-1
SELECT m.id, COUNT(*) AS cnt_star, COUNT(o.id) AS cnt_order
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id BETWEEN 69996 AND 70005
GROUP BY m.id ORDER BY m.id;

-- 4-2
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id;

EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
WHERE o.status = 'PAID';

SELECT count(DISTINCT m.id) FILTER (WHERE o.id IS NULL) AS null_extended_members, count(*) AS total_rows
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
WHERE o.status = 'PAID';

-- 4-3
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.status, o.id
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id AND m.status = 'ACTIVE'
WHERE m.id BETWEEN 1 AND 1000;

SELECT m.status, count(*) AS rows, count(o.id) AS matched
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id AND m.status = 'ACTIVE'
WHERE m.id BETWEEN 1 AND 1000
GROUP BY m.status;

-- 4-4
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name, o.id, o.amount, o.status
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id = 12345;

-- 4-5
SELECT m.id, o.id AS order_id
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
ORDER BY m.id, o.id LIMIT 20;

SELECT count(DISTINCT id) AS members_in_page FROM (
  SELECT m.id FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
  ORDER BY m.id, o.id LIMIT 20) t;

-- 4-6
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name FROM lab_member m LEFT JOIN lab_member_profile p ON p.member_id = m.id;

-- 4-7
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id WHERE o.member_id IS NULL;

EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id WHERE o.status IS NULL;

EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.member_id = m.id);

BEGIN;
SET LOCAL work_mem = '128MB';  -- 작으면 일반 SubPlan으로 바뀌어 끝나지 않을 수 있음
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id FROM lab_member m WHERE m.id NOT IN (SELECT o.member_id FROM lab_order o);
COMMIT;

-- 3. 개선 ------------------------------------------------------
-- 6-1
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id AND o.status = 'PAID';

-- 6-2
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.status, o.id FROM lab_member m
LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id BETWEEN 1 AND 1000 AND m.status = 'ACTIVE';

-- 6-3  ⚠ SHARE 락 (운영: CONCURRENTLY)
CREATE INDEX lab_order_member_id_idx ON lab_order (member_id);

EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name, o.id, o.amount, o.status
FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id
WHERE m.id = 12345;

-- 6-4
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, o.id AS order_id
FROM (SELECT id FROM lab_member ORDER BY id LIMIT 20) p
LEFT JOIN lab_order o ON o.member_id = p.id
ORDER BY p.id, o.id;

EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, o.id AS order_id, o.ordered_at
FROM (SELECT id FROM lab_member ORDER BY id LIMIT 20) p
LEFT JOIN LATERAL (
  SELECT id, ordered_at FROM lab_order WHERE member_id = p.id
  ORDER BY ordered_at DESC LIMIT 3
) o ON true
ORDER BY p.id, o.ordered_at DESC;

-- 6-5  ⚠ SHARE 락 (운영: CONCURRENTLY)
CREATE UNIQUE INDEX lab_member_profile_member_id_uk ON lab_member_profile (member_id);

EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, m.name FROM lab_member m LEFT JOIN lab_member_profile p ON p.member_id = m.id;

EXPLAIN
SELECT m.id, m.name, p.nickname FROM lab_member m LEFT JOIN lab_member_profile p ON p.member_id = m.id;

-- 6-6 NOT IN 함정 (트랜잭션으로 감싸 ROLLBACK)
BEGIN;
-- ⚠ ACCESS EXCLUSIVE
ALTER TABLE lab_order ALTER COLUMN member_id DROP NOT NULL;
INSERT INTO lab_order (id, member_id, amount, status, ordered_at) VALUES (1000001, NULL, 0, 'PAID', now());
SET LOCAL work_mem = '128MB';
SELECT count(*) FROM lab_member m WHERE m.id NOT IN (SELECT o.member_id FROM lab_order o);
SELECT count(*) FROM lab_member m WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.member_id = m.id);
ROLLBACK;
```

---

## 8. 실행 결과

> 작성 환경에서 PostgreSQL을 실행하지 않았다. 아래 블록은 **기대되는 노드 구조**이며, `...`로 둔 시간·비용·버퍼 수치는 직접 실행한 원문으로 교체해 기록한다. 노드 이름이 기대와 다르게 나오면 그 차이 자체가 분석 대상이다(예: Hash Left Join 대신 Hash Right Join).

### 기록 환경

```
PostgreSQL 버전   : (SELECT version();)
shared_buffers    : (SHOW shared_buffers;)
work_mem          : 4MB
hash_mem_multiplier: (SHOW hash_mem_multiplier;)
디스크            : 
lab_order 크기    : (SELECT pg_size_pretty(pg_total_relation_size('lab_order'));)
```

### 4-2 (b) — INNER 축소

```
Hash Join  (cost=... rows=... width=16) (actual time=... rows=~900000 loops=1)
  Hash Cond: (o.member_id = m.id)
  Buffers: shared hit=... read=...
  ->  Seq Scan on lab_order o  (... rows=~900000 loops=1)
        Filter: (status = 'PAID'::text)
        Rows Removed by Filter: ~100000
        Buffers: shared hit=... read=...
  ->  Hash  (... rows=100000 loops=1)
        Buckets: ...  Batches: ...  Memory Usage: ...kB
        ->  Seq Scan on lab_member m  (... rows=100000 loops=1)
Planning Time: ... ms
Execution Time: ... ms
```

### 4-3 — Join Filter

```
Nested Loop Left Join  또는  Hash Left Join  (... rows=... loops=1)
  Join Filter: (m.status = 'ACTIVE'::text)     ← 해시 조인이면 Hash Cond 아래에 표시
  Rows Removed by Join Filter: ...
  ->  Index Scan using lab_member_pkey on lab_member m  (... rows=1000 loops=1)
        Index Cond: ((id >= 1) AND (id <= 1000))
  ->  ...
```

### 4-4 — 인덱스 없는 inner

```
Nested Loop Left Join  (... rows=... loops=1)
  Buffers: shared hit=... read=...
  ->  Index Scan using lab_member_pkey on lab_member m  (... rows=1 loops=1)
        Index Cond: (id = 12345)
  ->  Seq Scan on lab_order o  (... rows=~14 loops=1)
        Filter: (member_id = 12345)
        Rows Removed by Filter: ~999986
        Buffers: shared hit=... read=...
Execution Time: ... ms
```

### 6-3 — 인덱스 추가 후

```
Nested Loop Left Join  (... rows=... loops=1)
  ->  Index Scan using lab_member_pkey on lab_member m  (... rows=1 loops=1)
        Index Cond: (id = 12345)
  ->  Bitmap Heap Scan on lab_order o  (... rows=~14 loops=1)
        Recheck Cond: (member_id = 12345)
        Heap Blocks: exact=...
        ->  Bitmap Index Scan on lab_order_member_id_idx  (... rows=~14 loops=1)
              Index Cond: (member_id = 12345)
Execution Time: ... ms
```

### 6-5 — 조인 제거

```
Seq Scan on lab_member m  (... rows=100000 loops=1)
  Buffers: shared hit=...
Planning Time: ... ms
Execution Time: ... ms
```

### 4-7 (a) vs (b)

```
-- (a)
Hash Right Anti Join (16+)  또는  Hash Anti Join   (... rows=30000 loops=1)
  Hash Cond: (o.member_id = m.id)
  ...

-- (b)
Hash Right Join  또는  Hash Left Join  (... rows=30000 loops=1)
  Hash Cond: (o.member_id = m.id)
  Filter: (o.status IS NULL)
  Rows Removed by Filter: 1000000
  ...
```

---

## 9. 전후 비교표

직접 측정 후 빈칸을 채운다. 행 수·노드명은 논리적으로 결정되는 값을 미리 적었다.

### 4-4 → 6-3 (단건 LEFT JOIN, 조인 컬럼 인덱스)

| 지표 | 개선 전 (4-4) | 개선 후 (6-3) |
|---|---|---|
| inner 노드 | `Seq Scan on lab_order` | `Bitmap Heap Scan` / `Index Scan` |
| inner `Rows Removed by Filter` | ≈ 999,986 | 없음 |
| 실행 시간 (ms) | | |
| shared hit / read (조인 노드 합계) | | |
| 반환 행수 | 해당 회원 주문 수 (동일) | 동일 |
| 인덱스 크기 | — | `SELECT pg_size_pretty(pg_relation_size('lab_order_member_id_idx'));` |

### 4-6 → 6-5 (조인 제거)

| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| 계획 노드 수 | 조인 + 스캔 2 + Hash | `Seq Scan` 1 |
| `lab_member_profile` 읽은 버퍼 | | 0 |
| 실행 시간 (ms) | | |
| 반환 행수 | 100,000 | 100,000 |

### 4-5 → 6-4 (페이징)

| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| 페이지 내 회원 수 | 1~2 | 20 |
| 반환 행수 | 20 | 20명의 주문 합계 (약 286) |
| 실행 시간 (ms) | | |
| shared hit / read | | |

### 4-7 (b) → (a)

| 지표 | (b) `o.status IS NULL` | (a) `o.member_id IS NULL` |
|---|---|---|
| 조인 노드 | `Hash Left/Right Join` + `Filter` | `Hash (Right) Anti Join` |
| `Rows Removed by Filter` | 1,000,000 | 없음 |
| 실행 시간 (ms) | | |
| 반환 행수 | 30,000 | 30,000 |

---

## 10. 애플리케이션 연동

### 10-1. JPA 엔티티

아래 코드는 Spring Boot 3.x / Hibernate 6.x / Java 17+ 기준(`jakarta.persistence`).
**Java 7~8 + Spring Boot 1.x/2.x 코드베이스**에서는 import를 `javax.persistence.*`로 바꾸면 그대로 동작한다(람다·`var`·record 미사용). JPQL의 `ON`은 JPA 2.1+(Hibernate 5.1+)이며, 그 이전 Hibernate 4.x에서는 `WITH`를 쓴다.

```java
package com.example.lab.join;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "lab_member")
public class LabMember {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status;

    @OneToMany(mappedBy = "member", fetch = FetchType.LAZY)
    private List<LabOrder> orders = new ArrayList<LabOrder>();

    protected LabMember() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStatus() {
        return status;
    }

    public List<LabOrder> getOrders() {
        return orders;
    }
}
```

```java
package com.example.lab.join;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "lab_order")
public class LabOrder {

    @Id
    private Long id;

    // optional = false → Hibernate가 이 연관을 조인할 때 INNER JOIN 사용
    // optional = true(기본값) → LEFT OUTER JOIN 사용
    // 컬럼이 NOT NULL이면 optional = false로 맞춰야 불필요한 외부 조인이 생기지 않는다
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private LabMember member;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    protected LabOrder() {
    }

    public Long getId() {
        return id;
    }

    public LabMember getMember() {
        return member;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }
}
```

JPA 엔티티 설계에서 다뤘던 `@ManyToOne`의 `optional`·`nullable` 설정이 여기서 **SQL 조인 타입**으로 이어진다. 스키마의 `NOT NULL`과 엔티티의 `optional`이 어긋나면, 결과는 같아도 플래너가 외부 조인 제약(4.6장 이론)을 떠안는다.

### 10-2. Repository — 함정과 개선을 나란히

```java
package com.example.lab.join;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LabMemberRepository extends JpaRepository<LabMember, Long> {

    // [함정 4-5] 컬렉션 left join fetch + 페이징
    // Hibernate 6: HHH90003004 "firstResult/maxResults specified with collection fetch; applying in memory"
    // → SQL에 LIMIT 없이 전체 조인 결과를 읽어 메모리에서 자름
    // hibernate.query.fail_on_pagination_over_collection_fetch=true 로 두면 예외로 막을 수 있다
    @Query(value = "select m from LabMember m left join fetch m.orders where m.status = :status",
           countQuery = "select count(m) from LabMember m where m.status = :status")
    Page<LabMember> findWithOrdersPagedWrong(@Param("status") String status, Pageable pageable);

    // [함정 4-2] WHERE에 연관 엔티티 조건 → SQL에서 INNER JOIN으로 축소됨
    @Query("select m.id, count(o.id) from LabMember m left join m.orders o "
         + "where o.status = 'PAID' group by m.id")
    List<Object[]> countPaidOrdersWrong();

    // [개선 6-1] 조건을 ON으로 (JPA 2.1+). 주문 없는 회원도 count 0으로 포함
    @Query("select m.id, count(o.id) from LabMember m left join m.orders o on o.status = 'PAID' "
         + "group by m.id")
    List<Object[]> countPaidOrders();

    // [개선 6-4] 1단계: 보존 쪽 id만 페이징 (SQL에 LIMIT/OFFSET 적용)
    @Query(value = "select m.id from LabMember m where m.status = :status order by m.id",
           countQuery = "select count(m) from LabMember m where m.status = :status")
    Page<Long> findIdsByStatus(@Param("status") String status, Pageable pageable);

    // [개선 6-4] 2단계: 해당 id들만 fetch join (페이징 없음)
    // Hibernate 6은 fetch join 결과의 루트 엔티티 중복을 자동 제거하므로 distinct 불필요
    @Query("select m from LabMember m left join fetch m.orders where m.id in :ids order by m.id")
    List<LabMember> findWithOrdersByIds(@Param("ids") List<Long> ids);
}
```

### 10-3. Service — `@Transactional` 경계

```java
package com.example.lab.join;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class LabMemberQueryService {

    private final LabMemberRepository labMemberRepository;

    public LabMemberQueryService(LabMemberRepository labMemberRepository) {
        this.labMemberRepository = labMemberRepository;
    }

    // 두 쿼리가 같은 트랜잭션(같은 커넥션, READ COMMITTED에서는 문장마다 다른 스냅숏)에서 실행된다.
    // 1단계와 2단계 사이에 주문이 추가되면 2단계 결과에 반영될 수 있다.
    // 완전히 같은 스냅숏이 필요하면 isolation = Isolation.REPEATABLE_READ 로 지정한다.
    @Transactional(readOnly = true)
    public Page<LabMember> findActiveMembersWithOrders(Pageable pageable) {
        Page<Long> idPage = labMemberRepository.findIdsByStatus("ACTIVE", pageable);
        if (idPage.getContent().isEmpty()) {
            return new PageImpl<LabMember>(Collections.<LabMember>emptyList(), pageable, idPage.getTotalElements());
        }
        List<LabMember> members = labMemberRepository.findWithOrdersByIds(idPage.getContent());
        return new PageImpl<LabMember>(members, pageable, idPage.getTotalElements());
    }
}
```

생성되는 SQL 형태(Hibernate 6, 별칭은 버전에 따라 다름 — 형태 예시):

```sql
-- 1단계
select m1_0.id from lab_member m1_0 where m1_0.status=? order by m1_0.id offset ? rows fetch first ? rows only
-- 2단계
select m1_0.id, m1_0.name, o1_0.member_id, o1_0.id, o1_0.amount, o1_0.status, m1_0.status
from lab_member m1_0 left join lab_order o1_0 on m1_0.id=o1_0.member_id
where m1_0.id in (?,?,...) order by m1_0.id
```

**대안**: fetch join 대신 `spring.jpa.properties.hibernate.default_batch_fetch_size=100`을 두면, 회원 페이지 조회 후 `orders` 접근 시 `where member_id in (...)` 형태로 묶어 조회한다(PostgreSQL에서 Hibernate 6.2+는 `= any(?)` 배열 바인딩을 쓰는 것으로 알려져 있음 — 확인 필요). 두 경우 모두 `lab_order.member_id` 인덱스(6-3)가 전제다.

SQL 로그 확인:

```properties
spring.jpa.properties.hibernate.format_sql=true
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.orm.jdbc.bind=TRACE
```

### 10-4. MyBatis

```java
package com.example.lab.join.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LabMemberMapper {

    List<MemberWithOrders> selectMembersWithOrders(@Param("status") String status,
                                                   @Param("orderStatus") String orderStatus,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);
}
```

```java
package com.example.lab.join.mybatis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class MemberWithOrders {

    private Long id;
    private String name;
    private List<OrderRow> orders = new ArrayList<OrderRow>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<OrderRow> getOrders() { return orders; }
    public void setOrders(List<OrderRow> orders) { this.orders = orders; }

    public static class OrderRow {
        private Long id;
        private BigDecimal amount;
        private String status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.lab.join.mybatis.LabMemberMapper">

    <!-- <id>가 있어야 MyBatis가 조인 결과 여러 행을 회원 1건으로 묶는다.
         주문이 없는 NULL 확장 행은 o_id가 NULL이므로 orders가 빈 리스트가 된다. -->
    <resultMap id="memberWithOrders" type="com.example.lab.join.mybatis.MemberWithOrders">
        <id     property="id"   column="m_id"/>
        <result property="name" column="m_name"/>
        <collection property="orders" ofType="com.example.lab.join.mybatis.MemberWithOrders$OrderRow">
            <id     property="id"     column="o_id"/>
            <result property="amount" column="o_amount"/>
            <result property="status" column="o_status"/>
        </collection>
    </resultMap>

    <!-- [함정] 이렇게 쓰면 LIMIT이 조인 결과 행에 적용되어 회원 수가 limit보다 적어지고,
         <if>로 붙인 o.status 조건은 WHERE에 들어가 LEFT JOIN이 INNER로 축소된다.

    SELECT m.id AS m_id, m.name AS m_name, o.id AS o_id, o.amount AS o_amount, o.status AS o_status
    FROM lab_member m
    LEFT JOIN lab_order o ON o.member_id = m.id
    WHERE m.status = #{status}
      <if test="orderStatus != null">AND o.status = #{orderStatus}</if>
    ORDER BY m.id, o.id
    LIMIT #{limit} OFFSET #{offset}
    -->

    <!-- [개선] 보존 쪽 먼저 페이징 + 널 허용 쪽 조건은 ON 절 안에 -->
    <select id="selectMembersWithOrders" resultMap="memberWithOrders">
        SELECT p.id AS m_id, p.name AS m_name,
               o.id AS o_id, o.amount AS o_amount, o.status AS o_status
        FROM (
            SELECT id, name
            FROM lab_member
            WHERE status = #{status}
            ORDER BY id
            LIMIT #{limit} OFFSET #{offset}
        ) p
        LEFT JOIN lab_order o
               ON o.member_id = p.id
              <if test="orderStatus != null">AND o.status = #{orderStatus}</if>
        ORDER BY p.id, o.id
    </select>
</mapper>
```

PageHelper 같은 페이징 플러그인은 SQL 바깥에 LIMIT을 덧붙이므로 `<collection>` 매핑과 함께 쓰면 4-5와 같은 문제가 그대로 생긴다.

---

## 11. 변형 실습

### 변형 1. Nested Loop을 강제하면 드라이빙 방향은?

```sql
SET enable_hashjoin = off;
SET enable_mergejoin = off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id FROM lab_member m LEFT JOIN lab_order o ON o.member_id = m.id;
RESET enable_hashjoin;
RESET enable_mergejoin;
```

<details><summary>예상 답</summary>

`Nested Loop Left Join`의 outer는 **반드시 `lab_member`** 다. Nested Loop은 Right Join을 지원하지 않으므로 행 수가 많은 `lab_order`를 outer로 돌며 `lab_member_pkey`를 찍는 계획(INNER JOIN이라면 가능)은 선택지에 없다. inner는 `lab_order_member_id_idx`(6-3 이후) 인덱스 스캔이고 `loops=100000`. 인덱스가 없는 상태라면 `Materialize`나 `Seq Scan` loops=100000으로 사실상 끝나지 않는다. `enable_* = off`는 금지가 아니라 비용 페널티이므로, 불가능한 경우엔 다른 알고리즘이 그대로 나올 수 있다는 점도 확인한다.
</details>

### 변형 2. `lab_member_profile`의 유니크 인덱스를 일반 인덱스로 바꾸면?

```sql
-- ⚠ DROP INDEX: ACCESS EXCLUSIVE (운영: DROP INDEX CONCURRENTLY)
DROP INDEX lab_member_profile_member_id_uk;
CREATE INDEX lab_member_profile_member_id_idx ON lab_member_profile (member_id);
EXPLAIN SELECT m.id, m.name FROM lab_member m LEFT JOIN lab_member_profile p ON p.member_id = m.id;
```

<details><summary>예상 답</summary>

조인이 **다시 나타난다.** 실제 데이터는 여전히 member_id당 1행이지만, 일반 인덱스는 유일성을 증명하지 못하므로 `join_is_removable`이 false를 반환한다. `pg_stats.n_distinct = -1`이어도 결과는 같다(통계는 증명 근거가 아님). 추가로 `ALTER TABLE lab_member_profile ADD CONSTRAINT ... UNIQUE ... DEFERRABLE`로 만든 유니크 제약도 `indimmediate = false`라서 제거되지 않는다.
</details>

### 변형 3. ON 조건에 A 쪽 조건과 B 쪽 조건을 모두 넣고 `WHERE`에 non-strict 조건을 추가하면?

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT m.id, o.id
FROM lab_member m
LEFT JOIN lab_order o
       ON o.member_id = m.id
      AND o.status = 'PAID'          -- B만 참조
      AND m.status = 'ACTIVE'        -- A만 참조
WHERE m.id BETWEEN 1 AND 1000
  AND COALESCE(o.amount, 0) < 50000; -- non-strict, B 참조
```

<details><summary>예상 답</summary>

- `o.status = 'PAID'` → `lab_order` 스캔(또는 인덱스 스캔)의 `Filter`로 push down
- `m.status = 'ACTIVE'` → 조인 노드의 `Join Filter` (보존 쪽 조건은 스캔으로 못 내림)
- `COALESCE(o.amount, 0) < 50000` → non-strict이므로 INNER 축소가 일어나지 않고, NULL 확장 이후에만 평가 가능하므로 조인 노드의 `Filter`
- 조인 노드명은 `Left`가 유지된다. DORMANT 회원 200명은 `Join Filter`에서 매칭 실패 → NULL 확장 → `COALESCE(NULL,0)=0 < 50000` 통과 → 결과에 남는다.

`COALESCE(o.amount, 0) < 50000`을 `o.amount < 50000`으로 바꾸면 strict 조건이 되어 노드명에서 `Left`가 사라지고 DORMANT 회원도 사라진다.
</details>

---

## 12. 정리

```sql
-- ⚠ DROP TABLE: ACCESS EXCLUSIVE. 실습 테이블만 삭제
--   (인덱스는 테이블과 함께 삭제됨)
DROP TABLE IF EXISTS lab_order, lab_member_profile, lab_member;
DROP TABLE IF EXISTS lab_null_a, lab_null_b;

-- 세션 파라미터 원복
RESET enable_nestloop;
RESET max_parallel_workers_per_gather;
RESET jit;
RESET work_mem;
RESET enable_hashjoin;
RESET enable_mergejoin;
RESET join_collapse_limit;

-- pg_stat_statements를 이 실습 때문에 설치했다면
-- DROP EXTENSION IF EXISTS pg_stat_statements;
-- 그리고 postgresql.conf 의 shared_preload_libraries 에서 제거 후 서버 재시작
```
