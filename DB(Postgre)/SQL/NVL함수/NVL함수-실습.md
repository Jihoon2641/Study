---
주제: NVL() 함수 (Oracle NULL 대체 함수와 PostgreSQL 대응)
폴더: SQL/NVL함수
분류: PostgreSQL / SQL 처리·표현식 평가
난이도: 중급
관련버전: PostgreSQL 13+ (anycompatible 사용. 12 이하는 2.4절 대체 정의 사용)
작성일: 2026-09-28
선행지식: [COALESCE 동작 원리, 함수 휘발성, EXPLAIN 읽기]
---

# NVL() — 실습

관련 문서: [이론](./NVL함수-이론.md) · [COALESCE 실습](../COALESCE함수/COALESCE함수-실습.md)

> 전제: PostgreSQL 16, 로컬 단일 인스턴스, `shared_buffers = 128MB`(기본값), SSD. 측정 시 병렬 쿼리와 JIT은 끈다.
>
> **이 문서의 수치는 작성 환경에서 측정하지 않았다.** 8장에는 논리적으로 확정되는 행 수·노드 구조만 채우고, 시간·버퍼는 직접 실행 후 채우는 칸으로 비워 두었다. 9장 비교표도 같다.

---

## 1. 실습 개요

같은 "NULL이면 기본값" 로직을 **네 가지 구현**으로 만들고, 구현마다 플래너·실행기 동작이 어떻게 갈리는지 수치로 확인한다.

| 구현 | 이름 | 언어 | 휘발성 | STRICT |
|---|---|---|---|---|
| A | `COALESCE` | 구문 구조 | — | — |
| B | `lab_nvl_sql` | SQL | IMMUTABLE | X |
| C | `lab_nvl_plpgsql` | PL/pgSQL | VOLATILE(기본값) → 6장에서 IMMUTABLE | X |
| D | `lab_nvl_strict` | SQL | IMMUTABLE | **O** (함정) |
| E | `lab_nvl_elem` | SQL | IMMUTABLE | X, `anyelement` (함정) |
| (선택) | orafce `nvl` | C | 설치 후 확인 | 설치 후 확인 |

| # | 재현 | 수치로 증명하는 것 |
|---|---|---|
| 4-1 | 의미론: STRICT·`''` | `lab_nvl_strict(NULL, 0)` = NULL, `''` 행 수 = 50,000 이 대체되지 않음 |
| 4-2 | 타입 해석 | `anyelement` 버전만 `function ... does not exist` |
| 4-3 | 단축 평가 | 두 번째 인자 호출 횟수: A·B = **200,000** (NULL 행 수), C = **1,000,000** |
| 4-4 | 인덱스 조건 | `customer_id = C(NULL, 777)` → Seq Scan + Filter, A·B → Index Scan |
| 4-5 | 선택도 | C의 추정 rows = **5,000** (1,000,000 × 0.005), 실제 200,000 |
| 4-6 | 행당 호출 비용 | 같은 집계를 A/B/C로 돌렸을 때 `Execution Time`, `pg_stat_user_functions.calls` |
| 6-x | 개선 | C를 IMMUTABLE로 → 상수 접힘 / SQL 함수 인라인 확인 / 표현식 인덱스 |

---

## 2. 환경

### 2.1 버전 확인

```sql
SELECT version();
SHOW server_version_num;   -- 130000 미만이면 anycompatible 대신 2.4절 정의 사용
```

### 2.2 확장

```sql
-- 선택: 쿼리 누적 통계. shared_preload_libraries = 'pg_stat_statements' 설정 후 재시작 필요
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 선택: orafce. 설치 가능 여부부터 확인
SELECT name, default_version, installed_version
FROM   pg_available_extensions
WHERE  name = 'orafce';
```

orafce 설치 방법:

- Linux 패키지: PGDG 저장소의 `orafce_16`(RHEL 계열) / `postgresql-16-orafce`(Debian 계열) — 패키지명 `확인 필요`
- Windows 로컬 설치에는 기본 포함되지 않는다. **Docker로 실습하는 편이 간단하다.**
  ```bash
  docker run -d --name lab-pg16 -e POSTGRES_PASSWORD=lab -p 5432:5432 postgres:16
  docker exec -it lab-pg16 bash -c "apt-get update && apt-get install -y postgresql-16-orafce"
  ```
- 관리형 DB(RDS 등)는 지원 확장 목록에서 확인. 없으면 orafce 부분(5-3)은 건너뛴다. 나머지 실습은 orafce 없이 완결된다.

### 2.3 측정용 세션 파라미터

```sql
SET jit = off;                            -- 기본 on. C 구현은 비용이 커져 JIT이 켜질 수 있어 변수 제거
SET max_parallel_workers_per_gather = 0;  -- 기본 2. Gather 제거
SET track_functions = 'all';              -- 기본 none. 슈퍼유저 필요(SUSET). 함수 호출 횟수 집계
SET track_io_timing = on;                 -- 기본 off. 슈퍼유저 필요
```

원복:

```sql
RESET jit;
RESET max_parallel_workers_per_gather;
RESET track_functions;
RESET track_io_timing;
```

psql에서 `\timing on`을 켠다. (psql 전용 메타 명령)

### 2.4 PG12 이하용 대체 정의

`anycompatible`이 없으므로 타입별 오버로드를 만든다. 이하 실습의 `anycompatible` 자리를 이것으로 바꿔 읽는다.

```sql
CREATE FUNCTION lab_nvl_sql(text, text)       RETURNS text    LANGUAGE sql IMMUTABLE AS 'SELECT COALESCE($1, $2)';
CREATE FUNCTION lab_nvl_sql(integer, integer) RETURNS integer LANGUAGE sql IMMUTABLE AS 'SELECT COALESCE($1, $2)';
CREATE FUNCTION lab_nvl_sql(numeric, numeric) RETURNS numeric LANGUAGE sql IMMUTABLE AS 'SELECT COALESCE($1, $2)';
```

---

## 3. 재현 데이터 생성

### 3.1 규모와 이유

- `lab_customer` **10,000행** — 스칼라 서브쿼리 대상
- `lab_order` **1,000,000행**
  - `status` NULL **20% = 200,000행** → 단축 평가 차이가 20만 vs 100만으로 선명하게 갈린다
  - `memo` NULL 10% + `''` 5% → Oracle `''`=NULL 차이 재현
  - `customer_id` 인덱스 → 선택도 1/10,000 (약 100행)이라 인덱스가 확실히 이기는 조건. Seq Scan이 나오면 **플래너 판단이 아니라 인덱스 조건 불가** 때문임을 보장
  - 100만 행이면 행당 함수 호출 오버헤드가 수백 ms 단위로 드러난다 (환경 의존, 측정으로 확인)

### 3.2 시드 스크립트

```sql
DROP TABLE IF EXISTS lab_order;      -- 운영 주의: DROP은 ACCESS EXCLUSIVE
DROP TABLE IF EXISTS lab_customer;

CREATE TABLE lab_customer (
    id    integer PRIMARY KEY,
    name  text    NOT NULL
);

INSERT INTO lab_customer (id, name)
SELECT g, 'customer_' || g
FROM   generate_series(1, 10000) AS g;

CREATE TABLE lab_order (
    id           bigint  PRIMARY KEY,
    customer_id  integer NOT NULL,
    status       text,               -- 20% NULL
    memo         text,               -- 10% NULL, 5% ''
    price        integer NOT NULL,
    discount     integer             -- 30% NULL
);

INSERT INTO lab_order (id, customer_id, status, memo, price, discount)
SELECT g,
       (g % 10000) + 1,
       CASE WHEN g % 5 = 0 THEN NULL
            WHEN g % 5 = 1 THEN 'PAID'
            WHEN g % 5 = 2 THEN 'SHIPPED'
            WHEN g % 5 = 3 THEN 'DONE'
            ELSE 'CANCELED' END,
       CASE WHEN g % 20 IN (0, 1) THEN NULL     -- 10%
            WHEN g % 20 = 2       THEN ''       -- 5%
            ELSE 'memo_' || g END,
       (g % 1000) * 10,
       CASE WHEN g % 10 < 3 THEN NULL ELSE g % 50 END
FROM   generate_series(1, 1000000) AS g;

CREATE INDEX lab_order_customer_idx ON lab_order (customer_id);

-- 인덱스 생성 후, 모든 측정 전에 반드시 실행. 이 시점 이후 데이터 변경이 없으므로 1회면 충분
ANALYZE lab_customer;
ANALYZE lab_order;
```

검증:

```sql
SELECT count(*)                                  AS total,        -- 1,000,000
       count(*) FILTER (WHERE status IS NULL)    AS status_null,  -- 200,000
       count(*) FILTER (WHERE memo IS NULL)      AS memo_null,    -- 100,000
       count(*) FILTER (WHERE memo = '')         AS memo_empty,   -- 50,000
       count(*) FILTER (WHERE discount IS NULL)  AS disc_null     -- 300,000
FROM   lab_order;
```

### 3.3 NVL 구현 생성

```sql
-- B: 인라인 대상. PG13+
CREATE OR REPLACE FUNCTION lab_nvl_sql(a anycompatible, b anycompatible)
RETURNS anycompatible
LANGUAGE sql IMMUTABLE
AS $$ SELECT COALESCE(a, b) $$;

-- C: PL/pgSQL. 휘발성 생략 → VOLATILE (의도적으로 흔한 실수를 재현)
CREATE OR REPLACE FUNCTION lab_nvl_plpgsql(a anycompatible, b anycompatible)
RETURNS anycompatible
LANGUAGE plpgsql
AS $$
BEGIN
    RETURN COALESCE(a, b);
END;
$$;

-- D: STRICT 함정
CREATE OR REPLACE FUNCTION lab_nvl_strict(a anycompatible, b anycompatible)
RETURNS anycompatible
LANGUAGE sql IMMUTABLE STRICT
AS $$ SELECT COALESCE(a, b) $$;

-- E: anyelement 함정
CREATE OR REPLACE FUNCTION lab_nvl_elem(a anyelement, b anyelement)
RETURNS anyelement
LANGUAGE sql IMMUTABLE
AS $$ SELECT COALESCE(a, b) $$;

-- 두 번째 인자 호출 횟수 측정용: 부를 때마다 시퀀스를 1 증가시키는 VOLATILE 함수
CREATE SEQUENCE lab_touch_seq;
CREATE OR REPLACE FUNCTION lab_touch(v text)
RETURNS text
LANGUAGE sql VOLATILE
AS $$ SELECT v || (nextval('lab_touch_seq') * 0)::text $$;
```

`lab_touch`는 입력에 `'0'`을 붙여 반환하면서 시퀀스를 1 올린다. 시퀀스 증가분 = 호출 횟수.

---

## 4. 문제 상황 재현

### 4-1. 의미론: STRICT와 `''`

```sql
SELECT lab_nvl_sql(NULL::int, 0)    AS b_sql,      -- 0
       lab_nvl_strict(NULL::int, 0) AS d_strict,   -- NULL  ← 함정
       COALESCE(NULL::int, 0)       AS a_coalesce; -- 0
```

```sql
-- Oracle이었다면 memo NULL 100,000 + '' 50,000 = 150,000행이 '-'로 바뀐다
SELECT count(*) FILTER (WHERE COALESCE(memo, '-') = '-')                AS pg_coalesce,       -- 100,000
       count(*) FILTER (WHERE COALESCE(NULLIF(memo, ''), '-') = '-')    AS oracle_semantics   -- 150,000
FROM   lab_order;
```

**기록**: `pg_coalesce = ______`, `oracle_semantics = ______`. 차이 50,000 = `''` 행 수.

### 4-2. 타입 해석

```sql
SELECT lab_nvl_elem(price, 0)   FROM lab_order LIMIT 1;   -- OK  (integer, integer)
SELECT lab_nvl_elem(price, 0.5) FROM lab_order LIMIT 1;   -- ERROR: function lab_nvl_elem(integer, numeric) does not exist
SELECT lab_nvl_sql (price, 0.5) FROM lab_order LIMIT 1;   -- OK  → numeric
SELECT pg_typeof(lab_nvl_sql(price, 0.5)) FROM lab_order LIMIT 1;  -- numeric

SELECT lab_nvl_elem(status, 'x'::varchar) FROM lab_order LIMIT 1;  -- ERROR: (text, character varying)
SELECT lab_nvl_sql (status, 'x'::varchar) FROM lab_order LIMIT 1;  -- OK → text
```

에러 메시지가 "타입 불일치"가 아니라 **"함수가 없다"**인 것을 확인한다.

### 4-3. 단축 평가 — 두 번째 인자가 몇 번 불리는가

`status` NULL = 200,000행. 두 번째 인자 `lab_touch('NONE')` 호출 횟수를 시퀀스로 잰다.

```sql
-- A: COALESCE
SELECT setval('lab_touch_seq', 1, false);
SELECT count(COALESCE(status, lab_touch('NONE'))) FROM lab_order;
SELECT nextval('lab_touch_seq') - 1 AS a_calls;          -- 기대: 200,000

-- B: SQL 함수 (인라인 기대)
SELECT setval('lab_touch_seq', 1, false);
SELECT count(lab_nvl_sql(status, lab_touch('NONE'))) FROM lab_order;
SELECT nextval('lab_touch_seq') - 1 AS b_calls;          -- 기대: 200,000

-- C: PL/pgSQL
SELECT setval('lab_touch_seq', 1, false);
SELECT count(lab_nvl_plpgsql(status, lab_touch('NONE'))) FROM lab_order;
SELECT nextval('lab_touch_seq') - 1 AS c_calls;          -- 기대: 1,000,000
```

`setval(seq, 1, false)`는 "다음 nextval이 1을 반환"하게 만든다. 측정 쿼리 뒤 `nextval - 1`이 호출 횟수다.

**기록**: `a_calls = ______`, `b_calls = ______`, `c_calls = ______`

> B가 1,000,000으로 나오면 인라인이 안 된 것이다. 5-2에서 원인을 찾는다.

### 4-4. 인덱스 조건 — 바인드 파라미터 NVL

애플리케이션의 `WHERE customer_id = NVL(#{customerId}, 777)`을 흉내 낸다. 파라미터가 NULL로 들어온 경우.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM lab_order WHERE customer_id = COALESCE(NULL::int, 777);        -- A

EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM lab_order WHERE customer_id = lab_nvl_sql(NULL::int, 777);     -- B

EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM lab_order WHERE customer_id = lab_nvl_plpgsql(NULL::int, 777); -- C
```

실제 바인드 파라미터 경로도 본다 (JDBC `PreparedStatement`와 같은 경로):

```sql
PREPARE q_c(int) AS SELECT * FROM lab_order WHERE customer_id = lab_nvl_plpgsql($1, 777);
EXPLAIN (ANALYZE, BUFFERS) EXECUTE q_c(NULL);
DEALLOCATE q_c;
```

**기대**: A·B는 `Index Cond: (customer_id = 777)` 또는 Bitmap Index Scan, 100행. C는 `Seq Scan` + `Filter: (customer_id = lab_nvl_plpgsql(NULL::integer, 777))`, `Rows Removed by Filter: 999900`.

### 4-5. 선택도 추정

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM lab_order WHERE lab_nvl_plpgsql(status, 'NONE') = 'NONE';   -- C

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM lab_order WHERE COALESCE(status, 'NONE') = 'NONE';          -- A
```

**기대**: 둘 다 추정 `rows=5000`, 실제 200,000. A도 표현식 통계가 없으면 같은 기본값이다. 차이는 6-3에서 인덱스를 만든 뒤에 벌어진다.

### 4-6. 행당 호출 비용

```sql
SELECT pg_stat_reset();   -- 슈퍼유저. 함수 통계 초기화 (다른 통계도 초기화되므로 실습 DB에서만)

EXPLAIN (ANALYZE, BUFFERS) SELECT sum(COALESCE(discount, 0))        FROM lab_order;  -- A
EXPLAIN (ANALYZE, BUFFERS) SELECT sum(lab_nvl_sql(discount, 0))     FROM lab_order;  -- B
EXPLAIN (ANALYZE, BUFFERS) SELECT sum(lab_nvl_plpgsql(discount, 0)) FROM lab_order;  -- C
```

각각 3회 실행해 **두 번째·세 번째의 중앙값**을 기록한다(첫 회는 캐시 워밍).

> 참고: `sum(COALESCE(discount, 0))`은 [COALESCE 이론 6.2](../COALESCE함수/COALESCE함수-이론.md#62-집계에서-위치에-따른-차이)대로 의미상 불필요한 중복이다. 여기서는 순수하게 표현식 평가 비용 비교용으로 쓴다.

---

## 5. 진단

### 5-1. 1단계 — 지금 쓰는 `nvl`이 무엇인가

```sql
SELECT p.oid::regprocedure AS signature,
       l.lanname           AS lang,
       p.provolatile       AS vol,
       p.proisstrict       AS strict,
       p.procost           AS cost,
       p.proconfig         AS config
FROM   pg_proc p
JOIN   pg_language l ON l.oid = p.prolang
WHERE  p.proname LIKE 'lab_nvl%' OR p.proname = 'nvl'
ORDER  BY 1;
```

기대:

| signature | lang | vol | strict | cost |
|---|---|---|---|---|
| lab_nvl_elem(anyelement,anyelement) | sql | i | f | 100 |
| lab_nvl_plpgsql(anycompatible,anycompatible) | plpgsql | **v** | f | 100 |
| lab_nvl_sql(anycompatible,anycompatible) | sql | i | f | 100 |
| lab_nvl_strict(anycompatible,anycompatible) | sql | i | **t** | 100 |

- `lang = plpgsql` → 인라인 불가 확정. 4-3의 1,000,000, 4-6의 시간 증가 원인.
- `vol = v` → 4-4의 Seq Scan 원인.
- `strict = t` → 4-1의 NULL 원인.
- SQL 함수도 `cost = 100`이지만 **인라인되면 이 값은 계획에 쓰이지 않는다.** (5-2에서 확인)

### 5-2. 2단계 — 인라인되었는가 (`EXPLAIN VERBOSE`)

```sql
EXPLAIN (VERBOSE, COSTS OFF) SELECT lab_nvl_sql(status, 'NONE')     FROM lab_order;
EXPLAIN (VERBOSE, COSTS OFF) SELECT lab_nvl_plpgsql(status, 'NONE') FROM lab_order;
EXPLAIN (VERBOSE, COSTS OFF) SELECT lab_nvl_strict(status, 'NONE')  FROM lab_order;
```

기대:

```
Seq Scan on public.lab_order
  Output: COALESCE(status, 'NONE'::text)                 ← B: 함수명이 사라짐 = 인라인

Seq Scan on public.lab_order
  Output: lab_nvl_plpgsql(status, 'NONE'::text)          ← C

Seq Scan on public.lab_order
  Output: lab_nvl_strict(status, 'NONE'::text)           ← D: SQL이지만 STRICT라 인라인 안 됨
```

### 5-3. 3단계 — 호출 횟수로 교차 확인 (`pg_stat_user_functions`)

4-6 실행 직후:

```sql
SELECT funcname, calls, round(total_time::numeric, 1) AS total_ms, round(self_time::numeric, 1) AS self_ms
FROM   pg_stat_user_functions
WHERE  funcname LIKE 'lab_nvl%'
ORDER  BY funcname;
```

기대: `lab_nvl_plpgsql`만 `calls = 3,000,000`(100만 × 3회). `lab_nvl_sql`은 **행이 없거나 calls가 늘지 않음** → 실제로 한 번도 호출되지 않았다는 증거.

> 통계는 트랜잭션 종료 시점에 반영되고 PG15+에서는 공유 메모리 누적 통계로 바뀌었다. 바로 안 보이면 `SELECT pg_stat_force_next_flush();`(PG15+) 후 다시 조회. 함수명 `확인 필요`.

orafce를 설치했다면:

```sql
CREATE EXTENSION orafce;
-- 5-1 쿼리를 다시 돌려 nvl의 lang/vol/strict/스키마를 기록
EXPLAIN (VERBOSE, COSTS OFF) SELECT nvl(status, 'NONE') FROM lab_order;
-- Output에 nvl(...)이 남으면 C 함수 → 단축 평가 없음. 4-3을 nvl로 돌려 calls를 확인
```

### 5-4. 4단계 — 인덱스 조건 불가의 원인 좁히기

4-4의 C가 Seq Scan인 이유가 "PL/pgSQL이라서"인지 "VOLATILE이라서"인지 분리한다.

```sql
BEGIN;
ALTER FUNCTION lab_nvl_plpgsql(anycompatible, anycompatible) STABLE;
EXPLAIN SELECT * FROM lab_order WHERE customer_id = lab_nvl_plpgsql(NULL::int, 777);
-- 기대: Index Scan / Bitmap Index Scan,  Index Cond: (customer_id = lab_nvl_plpgsql(NULL::integer, 777))
--        → STABLE이면 스캔 시작 시 1회 평가해 인덱스 탐색값으로 쓸 수 있다. 함수명은 남아 있다
ROLLBACK;
```

결론: **인덱스 사용 여부는 휘발성, 인라인 여부는 언어가 결정한다.** 두 축이 독립이다.

---

## 6. 개선

### 6-1. PL/pgSQL 함수를 IMMUTABLE로 (응급 처치)

```sql
ALTER FUNCTION lab_nvl_plpgsql(anycompatible, anycompatible) IMMUTABLE;

EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM lab_order WHERE customer_id = lab_nvl_plpgsql(NULL::int, 777);
```

변화:
- `Seq Scan` + `Filter` → `Index Cond: (customer_id = 777)` — 상수 인자라 **계획 시점에 함수가 실행되어 777로 접혔다**(`evaluate_function()`).
- 그러나 4-3·4-6(컬럼 인자)은 **그대로다.** 컬럼 인자는 계획 시점에 평가할 수 없으므로 여전히 행마다 PL/pgSQL 호출.

### 6-2. SQL 언어로 재정의 (브리지 함수)

C를 B와 같은 형태로 바꾼다. 4-3 / 4-6을 C 대신 B로 돌린 결과가 개선 후 수치다.

```sql
CREATE OR REPLACE FUNCTION lab_nvl_plpgsql(a anycompatible, b anycompatible)
RETURNS anycompatible
LANGUAGE sql IMMUTABLE
AS $$ SELECT COALESCE(a, b) $$;
-- 주의: CREATE OR REPLACE로 언어를 바꿀 수 있다. 반환 타입이 바뀌면 DROP 후 재생성 필요
```

재측정: 4-3의 C 쿼리 → `c_calls` 1,000,000 → 200,000. 5-2의 `Output`이 `COALESCE(...)`로 바뀐다.

### 6-3. 표현식 인덱스 — 함수로 만들고 COALESCE로 찾기

```sql
-- 운영 주의: CREATE INDEX는 SHARE 락 (INSERT/UPDATE/DELETE 대기). 운영에서는 CONCURRENTLY
CREATE INDEX lab_order_status_nvl_idx ON lab_order (lab_nvl_sql(status, 'NONE'));
ANALYZE lab_order;   -- 표현식 인덱스의 통계는 ANALYZE 때 수집된다

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM lab_order WHERE COALESCE(status, 'NONE') = 'NONE';

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM lab_order WHERE lab_nvl_sql(status, 'NONE') = 'NONE';
```

확인할 것:
1. 추정 `rows=5000` → **약 200,000**으로 이동했는가. (표현식 인덱스 통계 사용)
2. `COALESCE`로 쓴 쿼리도 `lab_order_status_nvl_idx`를 쓰는가. 인덱스 식도 플래너가 로드할 때 `eval_const_expressions()`를 거쳐 인라인되므로 매칭될 것으로 기대 — **`확인 필요`, 이 실습으로 직접 검증**.
3. 200,000행(20%)이면 인덱스보다 Seq Scan이 선택될 수 있다. 그래도 **추정 rows가 정확해진 것 자체가 개선**이다. 인덱스 사용을 보려면 조건을 `'CANCELED'`가 아닌 희소 값으로 바꿔 보라(변형 11-1).

```sql
SELECT pg_get_indexdef('lab_order_status_nvl_idx'::regclass);
-- 인덱스 정의에는 lab_nvl_sql(...)이 원문대로 남는다 (표시와 플래너 매칭은 별개)
```

### 6-4. 최종 — `COALESCE`로 교체

B는 과도기 브리지다. 최종적으로 SQL 원문을 `COALESCE`로 바꾸면 다음이 사라진다.
- 함수 의존성(`pg_depend`) — 함수 수정/삭제 시 인덱스 영향
- `search_path` 의존 — 스키마 설정이 다른 세션에서 "함수 없음"
- 상수 인자 조기 평가 함정(`lab_nvl_sql(1, 1/0)` 에러)

---

## 7. 전체 스크립트

```sql
-- =====================================================================
-- 실행 순서
--   0) psql로 실습 DB 접속. 슈퍼유저 권장(track_functions, pg_stat_reset)
--   1) 세션 설정 → 2) 시드 → 3) 함수 → 4) 재현 → 5) 진단 → 6) 개선
--   각 EXPLAIN 결과를 8장 칸에 기록
--   마지막에 12장 정리 스크립트 실행
-- =====================================================================

-- 1) 세션 설정
SET jit = off;
SET max_parallel_workers_per_gather = 0;
SET track_functions = 'all';
SET track_io_timing = on;
\timing on

-- 2) 시드
DROP TABLE IF EXISTS lab_order;
DROP TABLE IF EXISTS lab_customer;

CREATE TABLE lab_customer (id integer PRIMARY KEY, name text NOT NULL);
INSERT INTO lab_customer SELECT g, 'customer_' || g FROM generate_series(1, 10000) g;

CREATE TABLE lab_order (
    id bigint PRIMARY KEY, customer_id integer NOT NULL,
    status text, memo text, price integer NOT NULL, discount integer
);
INSERT INTO lab_order
SELECT g, (g % 10000) + 1,
       CASE WHEN g % 5 = 0 THEN NULL WHEN g % 5 = 1 THEN 'PAID' WHEN g % 5 = 2 THEN 'SHIPPED'
            WHEN g % 5 = 3 THEN 'DONE' ELSE 'CANCELED' END,
       CASE WHEN g % 20 IN (0, 1) THEN NULL WHEN g % 20 = 2 THEN '' ELSE 'memo_' || g END,
       (g % 1000) * 10,
       CASE WHEN g % 10 < 3 THEN NULL ELSE g % 50 END
FROM generate_series(1, 1000000) g;
CREATE INDEX lab_order_customer_idx ON lab_order (customer_id);
ANALYZE lab_customer;
ANALYZE lab_order;

-- 3) 함수
CREATE OR REPLACE FUNCTION lab_nvl_sql(a anycompatible, b anycompatible) RETURNS anycompatible
LANGUAGE sql IMMUTABLE AS $$ SELECT COALESCE(a, b) $$;

CREATE OR REPLACE FUNCTION lab_nvl_plpgsql(a anycompatible, b anycompatible) RETURNS anycompatible
LANGUAGE plpgsql AS $$ BEGIN RETURN COALESCE(a, b); END; $$;

CREATE OR REPLACE FUNCTION lab_nvl_strict(a anycompatible, b anycompatible) RETURNS anycompatible
LANGUAGE sql IMMUTABLE STRICT AS $$ SELECT COALESCE(a, b) $$;

CREATE OR REPLACE FUNCTION lab_nvl_elem(a anyelement, b anyelement) RETURNS anyelement
LANGUAGE sql IMMUTABLE AS $$ SELECT COALESCE(a, b) $$;

DROP SEQUENCE IF EXISTS lab_touch_seq;
CREATE SEQUENCE lab_touch_seq;
CREATE OR REPLACE FUNCTION lab_touch(v text) RETURNS text
LANGUAGE sql VOLATILE AS $$ SELECT v || (nextval('lab_touch_seq') * 0)::text $$;

-- 4-1) 의미론
SELECT lab_nvl_sql(NULL::int, 0), lab_nvl_strict(NULL::int, 0), COALESCE(NULL::int, 0);
SELECT count(*) FILTER (WHERE COALESCE(memo, '-') = '-'),
       count(*) FILTER (WHERE COALESCE(NULLIF(memo, ''), '-') = '-')
FROM lab_order;

-- 4-2) 타입 (에러가 나는 줄은 개별 실행)
SELECT pg_typeof(lab_nvl_sql(price, 0.5)) FROM lab_order LIMIT 1;
SELECT lab_nvl_elem(price, 0.5) FROM lab_order LIMIT 1;   -- ERROR 기대

-- 4-3) 단축 평가
SELECT setval('lab_touch_seq', 1, false);
SELECT count(COALESCE(status, lab_touch('NONE'))) FROM lab_order;
SELECT nextval('lab_touch_seq') - 1 AS a_calls;
SELECT setval('lab_touch_seq', 1, false);
SELECT count(lab_nvl_sql(status, lab_touch('NONE'))) FROM lab_order;
SELECT nextval('lab_touch_seq') - 1 AS b_calls;
SELECT setval('lab_touch_seq', 1, false);
SELECT count(lab_nvl_plpgsql(status, lab_touch('NONE'))) FROM lab_order;
SELECT nextval('lab_touch_seq') - 1 AS c_calls;

-- 4-4) 인덱스 조건
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM lab_order WHERE customer_id = COALESCE(NULL::int, 777);
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM lab_order WHERE customer_id = lab_nvl_sql(NULL::int, 777);
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM lab_order WHERE customer_id = lab_nvl_plpgsql(NULL::int, 777);

-- 4-5) 선택도
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM lab_order WHERE lab_nvl_plpgsql(status, 'NONE') = 'NONE';
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM lab_order WHERE COALESCE(status, 'NONE') = 'NONE';

-- 4-6) 호출 비용 (각 3회)
SELECT pg_stat_reset();
EXPLAIN (ANALYZE, BUFFERS) SELECT sum(COALESCE(discount, 0)) FROM lab_order;
EXPLAIN (ANALYZE, BUFFERS) SELECT sum(lab_nvl_sql(discount, 0)) FROM lab_order;
EXPLAIN (ANALYZE, BUFFERS) SELECT sum(lab_nvl_plpgsql(discount, 0)) FROM lab_order;

-- 5) 진단
SELECT p.oid::regprocedure, l.lanname, p.provolatile, p.proisstrict, p.procost
FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang
WHERE p.proname LIKE 'lab_nvl%' ORDER BY 1;
EXPLAIN (VERBOSE, COSTS OFF) SELECT lab_nvl_sql(status, 'NONE') FROM lab_order;
EXPLAIN (VERBOSE, COSTS OFF) SELECT lab_nvl_plpgsql(status, 'NONE') FROM lab_order;
EXPLAIN (VERBOSE, COSTS OFF) SELECT lab_nvl_strict(status, 'NONE') FROM lab_order;
SELECT funcname, calls, total_time, self_time FROM pg_stat_user_functions WHERE funcname LIKE 'lab_nvl%';

-- 6-1) IMMUTABLE
ALTER FUNCTION lab_nvl_plpgsql(anycompatible, anycompatible) IMMUTABLE;
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM lab_order WHERE customer_id = lab_nvl_plpgsql(NULL::int, 777);

-- 6-2) SQL 언어로 재정의
CREATE OR REPLACE FUNCTION lab_nvl_plpgsql(a anycompatible, b anycompatible) RETURNS anycompatible
LANGUAGE sql IMMUTABLE AS $$ SELECT COALESCE(a, b) $$;
SELECT setval('lab_touch_seq', 1, false);
SELECT count(lab_nvl_plpgsql(status, lab_touch('NONE'))) FROM lab_order;
SELECT nextval('lab_touch_seq') - 1 AS c_calls_after;

-- 6-3) 표현식 인덱스 (운영: SHARE 락 → CONCURRENTLY 사용)
CREATE INDEX lab_order_status_nvl_idx ON lab_order (lab_nvl_sql(status, 'NONE'));
ANALYZE lab_order;
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM lab_order WHERE COALESCE(status, 'NONE') = 'NONE';
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM lab_order WHERE lab_nvl_sql(status, 'NONE') = 'NONE';
```

---

## 8. 실행 결과

> 작성 환경에서 측정하지 않았다. 확정되는 행 수·노드 구조만 채웠다. `____`는 직접 실행 후 기록.
> 기록할 때 환경을 함께 적는다: PostgreSQL `____`, `shared_buffers = ____`, 디스크 `____`, 행 수 1,000,000.

### 8-1. 4-3 단축 평가

```
 a_calls              b_calls              c_calls
---------            ---------            ---------
 200000 (기대)        200000 (기대)        1000000 (기대)
 실측: ______         실측: ______         실측: ______
```

### 8-2. 4-4 C (개선 전)

```
Seq Scan on lab_order  (cost=0.00..____ rows=100 width=__) (actual time=... rows=100 loops=1)
  Filter: (customer_id = lab_nvl_plpgsql(NULL::integer, 777))
  Rows Removed by Filter: 999900
  Buffers: shared hit=____ read=____
Planning Time: ____ ms
Execution Time: ____ ms
```

- 추정 `rows=100`은 정확할 수 있다(`=` 비교에서 우변이 함수여도 좌변 컬럼 통계의 n_distinct를 쓸 수 있음 — `확인 필요`). 문제는 추정이 아니라 **인덱스 조건으로 못 내려가는 것**.
- `cost` 상한에 100만 × (0.25 + α)가 더해진다: `procost 100 × cpu_operator_cost 0.0025`.

### 8-3. 6-1 C (IMMUTABLE 후)

```
Bitmap Heap Scan on lab_order  (actual rows=100 loops=1)
  Recheck Cond: (customer_id = 777)
  Heap Blocks: exact=____
  Buffers: shared hit=____ read=____
  ->  Bitmap Index Scan on lab_order_customer_idx  (actual rows=100 loops=1)
        Index Cond: (customer_id = 777)
Execution Time: ____ ms
```

- `customer_id = g % 10000 + 1`이라 100행이 100개의 서로 다른 페이지에 흩어짐 → Bitmap이 선택될 가능성이 높다. `Index Scan`이 나와도 핵심은 같다: **`Filter` → `Index Cond`, 함수명 소멸(777로 접힘)**.

### 8-4. 4-5 → 6-3 선택도

```
-- 개선 전 (A, C 공통 형태)
Aggregate  (actual rows=1 loops=1)
  ->  Seq Scan on lab_order  (cost=... rows=5000 ...) (actual rows=200000 loops=1)
        Filter: (COALESCE(status, 'NONE'::text) = 'NONE'::text)
        Rows Removed by Filter: 800000

-- 개선 후 (6-3, ANALYZE 이후)
Aggregate  (actual rows=1 loops=1)
  ->  Seq Scan on lab_order  (cost=... rows=≈200000 ...) (actual rows=200000 loops=1)
        Filter: (COALESCE(status, 'NONE'::text) = 'NONE'::text)
```

- 추정 `5000 → ≈200000`. 20%라 Seq Scan 유지가 정상 판단일 수 있다.

### 8-5. 5-3 호출 통계

```
     funcname     |  calls  | total_ms | self_ms
------------------+---------+----------+---------
 lab_nvl_plpgsql  | 3000000 |   ____   |  ____
(lab_nvl_sql 행 없음 기대)
```

### 8-6. 4-6 행당 호출 비용 (각 3회 중 2·3회차 평균)

```
A COALESCE        Execution Time: ____ ms   Buffers: shared hit=____ read=____
B lab_nvl_sql     Execution Time: ____ ms   Buffers: shared hit=____ read=____
C lab_nvl_plpgsql Execution Time: ____ ms   Buffers: shared hit=____ read=____
```

- 버퍼 수치는 셋이 **같아야** 한다(같은 테이블 전체 스캔). 차이는 순수 CPU(함수 호출). 버퍼가 다르면 캐시 상태가 달랐던 것이므로 재측정.

---

## 9. 전후 비교표

**4-3 / 4-6 : 컬럼 인자 NVL** (C → 6-2 이후 C)

| 지표 | 개선 전 (PL/pgSQL) | 개선 후 (SQL 인라인) | 결정 근거 |
|---|---|---|---|
| 두 번째 인자 평가 횟수 | 1,000,000 (기대) | 200,000 (기대) | 데이터 분포로 확정 |
| `pg_stat_user_functions.calls` (1회 실행) | 1,000,000 | 0 / 행 없음 | 인라인 여부로 확정 |
| `EXPLAIN VERBOSE` Output | `lab_nvl_plpgsql(...)` | `COALESCE(...)` | |
| `Execution Time` (4-6) | ____ ms | ____ ms | 실측 |
| shared hit + read | ____ | ____ (같아야 함) | 실측 |

**4-4 : 바인드 파라미터 NVL** (C VOLATILE → 6-1 IMMUTABLE)

| 지표 | 개선 전 | 개선 후 | 결정 근거 |
|---|---|---|---|
| 스캔 노드 | Seq Scan | Bitmap/Index Scan | 휘발성 |
| 조건 위치 | `Filter` | `Index Cond` | |
| `Rows Removed by Filter` | 999,900 | 없음 | 데이터로 확정 |
| shared hit + read | ____ (테이블 전체 페이지 수 근처) | ____ (약 100 + 인덱스 몇 페이지) | 실측 |
| `Execution Time` | ____ ms | ____ ms | 실측 |

**4-5 : 선택도** (6-3 전후)

| 지표 | 개선 전 | 개선 후 |
|---|---|---|
| 추정 rows | 5,000 | ≈200,000 |
| 실제 rows | 200,000 | 200,000 |
| 추정/실제 비율 | 1/40 | ≈1 |
| 인덱스 크기 `pg_relation_size('lab_order_status_nvl_idx')` | — | ____ MB |

---

## 10. 애플리케이션 연동

### 10-1. MyBatis — Oracle 매퍼 이관

**Oracle 시절 매퍼 (그대로 두면 PostgreSQL에서 `function nvl does not exist`)**

```xml
<select id="findOrders" resultType="com.example.lab.order.OrderRow">
  SELECT id,
         NVL(status, 'NONE')   AS status,
         NVL(memo, '-')        AS memo,
         price - NVL(discount, 0) AS pay_amount
  FROM   lab_order
  WHERE  customer_id = NVL(#{customerId}, customer_id)
</select>
```

**PostgreSQL 이관 후**

```xml
<select id="findOrders" parameterType="com.example.lab.order.OrderSearchCondition"
        resultType="com.example.lab.order.OrderRow">
  SELECT id,
         COALESCE(status, 'NONE')             AS status,
         COALESCE(NULLIF(memo, ''), '-')      AS memo,      <!-- Oracle의 ''=NULL 의미 보존 -->
         price - COALESCE(discount, 0)        AS pay_amount
  FROM   lab_order
  <where>
    <if test="customerId != null">
      customer_id = #{customerId}
    </if>
  </where>
  ORDER BY id
  LIMIT #{limit}
</select>
```

- `NVL(#{customerId}, customer_id)`은 `COALESCE`로 바꿔도 여전히 안티패턴이다(파라미터 NULL 시 전 행 Seq Scan + `customer_id`가 NULL인 행 누락). `<if>`로 조건 자체를 뺀다. 자세한 근거는 [COALESCE 실습 10-1](../COALESCE함수/COALESCE함수-실습.md#10-1-mybatis--coalesceparam-col을-if로-대체).
- 전수 수정 전 과도기에는 3.3절 `lab_nvl_sql`과 같은 **SQL·IMMUTABLE·anycompatible** 함수를 `nvl` 이름으로 전용 스키마에 두고, `pg_stat_statements`에서 `nvl(`이 들어간 쿼리를 비싼 순으로 교체한다.

```sql
-- 과도기 브리지 (전용 스키마)
CREATE SCHEMA IF NOT EXISTS compat;
CREATE OR REPLACE FUNCTION compat.nvl(a anycompatible, b anycompatible)
RETURNS anycompatible LANGUAGE sql IMMUTABLE
AS $$ SELECT COALESCE(a, b) $$;
ALTER ROLE app_user SET search_path = "$user", public, compat;   -- 신규 세션부터 적용

-- 교체 우선순위
SELECT calls, round(total_exec_time) AS total_ms, left(query, 120)
FROM   pg_stat_statements
WHERE  query ILIKE '%nvl(%'
ORDER  BY total_exec_time DESC
LIMIT  20;
```

> `ALTER FUNCTION compat.nvl ... SET search_path = ...`처럼 **함수에 설정을 붙이면 인라인이 꺼진다**(이론 5.3). 보안상 `search_path` 고정이 필요하면 본문의 이름을 스키마 한정하는 쪽으로 해결한다.

**매퍼 인터페이스 / DTO (Java 7 호환, 전체)**

```java
package com.example.lab.order;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderMapper {

    List<OrderRow> findOrders(OrderSearchCondition condition);
}
```

```java
package com.example.lab.order;

public class OrderSearchCondition {

    private Integer customerId;
    private int limit = 20;

    public OrderSearchCondition() {
    }

    public OrderSearchCondition(Integer customerId, int limit) {
        this.customerId = customerId;
        this.limit = limit;
    }

    public Integer getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Integer customerId) {
        this.customerId = customerId;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
}
```

```java
package com.example.lab.order;

public class OrderRow {

    private Long id;
    private String status;
    private String memo;
    private Integer payAmount;

    public OrderRow() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public Integer getPayAmount() {
        return payAmount;
    }

    public void setPayAmount(Integer payAmount) {
        this.payAmount = payAmount;
    }
}
```

- `pay_amount` → `payAmount` 매핑은 `mybatis.configuration.map-underscore-to-camel-case=true` 전제.
- Java 16+라면 `OrderRow`를 `record`로 쓸 수 있으나, MyBatis 3.5.x의 record 생성자 매핑 지원 범위는 `확인 필요`. 7~8 코드베이스와 공유한다면 위 POJO 형태를 유지한다.

### 10-2. JPA / Hibernate — JPQL은 `COALESCE`가 표준

JPQL(JPA 2.0+)은 `COALESCE`와 `NULLIF`를 표준 함수로 정의한다. `NVL`은 JPQL 표준이 아니다.

- Hibernate 5: `NVL`을 HQL에 쓰면 Dialect에 등록된 경우만 동작. `PostgreSQLDialect`에 `nvl` 등록 여부 `확인 필요`.
- Hibernate 6: 등록되지 않은 함수도 **그대로 SQL로 통과**시킬 수 있어(`확인 필요` — 6.x 세부 버전별 동작 차이), 컴파일·기동 시점이 아니라 **쿼리 실행 시점**에 PostgreSQL 에러가 난다. Oracle→PG 전환 시 통합 테스트로만 잡힌다.

```java
package com.example.lab.order;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LabOrderRepository extends JpaRepository<LabOrder, Long> {

    // JPQL 표준 COALESCE → Hibernate가 PostgreSQL COALESCE로 그대로 번역 (인라인·단축 평가 모두 적용)
    @Query("select coalesce(o.status, 'NONE') from LabOrder o where o.customerId = :customerId")
    List<String> findStatusesByCustomer(@Param("customerId") Integer customerId);

    // 네이티브 쿼리에 NVL을 남기면 compat.nvl 같은 함수가 search_path에 없을 때 런타임 에러
    @Query(value = "select coalesce(nullif(memo, ''), '-') from lab_order where customer_id = :customerId",
           nativeQuery = true)
    List<String> findMemosByCustomer(@Param("customerId") Integer customerId);
}
```

```java
package com.example.lab.order;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

// Spring Boot 3.x(Jakarta EE 9+)라면 javax.persistence → jakarta.persistence 로 import만 바꾼다
@Entity
@Table(name = "lab_order")
public class LabOrder {

    @Id
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Integer customerId;

    @Column(name = "status")
    private String status;

    @Column(name = "memo")
    private String memo;

    @Column(name = "price", nullable = false)
    private Integer price;

    @Column(name = "discount")
    private Integer discount;

    protected LabOrder() {
    }

    public Long getId() {
        return id;
    }

    public Integer getCustomerId() {
        return customerId;
    }

    public String getStatus() {
        return status;
    }

    public String getMemo() {
        return memo;
    }

    public Integer getPrice() {
        return price;
    }

    public Integer getDiscount() {
        return discount;
    }
}
```

### 10-3. 트랜잭션 경계와 함수 교체

- `CREATE OR REPLACE FUNCTION compat.nvl`은 테이블 락이 없어 운영 중 교체 가능하지만, **커넥션 풀의 세션들이 캐시한 계획(prepared statement)**은 다음 실행 시 무효화·재계획된다. 교체 직후 잠깐 플래닝 시간이 튄다.
- `ALTER ROLE ... SET search_path`는 **이미 열린 커넥션에는 적용되지 않는다.** HikariCP라면 `maxLifetime`이 지나 커넥션이 교체될 때까지 섞여 있다. 즉시 반영이 필요하면 `connectionInitSql = SET search_path = ...`를 쓰거나 풀을 재시작한다.

---

## 11. 변형 실습

**11-1. 희소 값이면 6-3 인덱스가 쓰일까?**
`UPDATE lab_order SET status = 'HOLD' WHERE id % 10000 = 0;` (100행) 후 `ANALYZE`, `WHERE COALESCE(status,'NONE') = 'HOLD'`.
<details><summary>예상</summary>
추정 rows가 100 근처로 잡히고 `lab_order_status_nvl_idx`로 Bitmap/Index Scan. 인덱스를 `lab_nvl_sql`로 만들었어도 `COALESCE`로 쓴 쿼리와 매칭된다면 인덱스 식이 인라인되어 비교된다는 증거. 매칭되지 않는다면 인덱스 식은 원문 그대로 비교된다는 뜻이므로 이론 6.2 표의 `확인 필요` 항목을 갱신한다. UPDATE 락: 대상 행의 행 락 + 테이블 `ROW EXCLUSIVE`.
</details>

**11-2. `lab_nvl_sql`을 `CASE WHEN a IS NULL THEN b ELSE a END`로 바꾸고 4-3을 다시 돌리면?**
<details><summary>예상</summary>
본문에서 `a`가 두 번 쓰인다. 실제 인자 `status`는 단순 컬럼이라 싸고 non-volatile이므로 인라인은 유지될 것. 두 번째 인자 `b`는 1회 사용이므로 `lab_touch`가 volatile이어도 무방 → b_calls 200,000 유지 기대. 반대로 첫 인자에 `lab_touch(status)`를 넣으면 volatile 인자가 2회 사용되므로 인라인이 포기되고 `Output`에 함수명이 남을 것.
</details>

**11-3. PG14+ SQL 표준 본문이면 인라인될까?**

```sql
CREATE OR REPLACE FUNCTION lab_nvl_std(a anycompatible, b anycompatible)
RETURNS anycompatible LANGUAGE sql IMMUTABLE
RETURN COALESCE(a, b);
EXPLAIN (VERBOSE, COSTS OFF) SELECT lab_nvl_std(status, 'NONE') FROM lab_order;
```
<details><summary>예상</summary>
`Output: COALESCE(status, 'NONE'::text)`가 나오면 인라인된다. 이론 5.3·7장의 `확인 필요`를 이 결과로 확정한다. 추가로 `DROP TABLE`이 아니라 본문이 참조하는 객체가 있을 때 의존성이 기록되는지 `pg_depend`로 비교해 볼 수 있다(문자열 본문은 의존성 없음).
</details>

---

## 12. 정리

```sql
-- 운영 주의: DROP TABLE / DROP INDEX는 ACCESS EXCLUSIVE 락
DROP INDEX    IF EXISTS lab_order_status_nvl_idx;
DROP TABLE    IF EXISTS lab_order;
DROP TABLE    IF EXISTS lab_customer;

DROP FUNCTION IF EXISTS lab_nvl_sql(anycompatible, anycompatible);
DROP FUNCTION IF EXISTS lab_nvl_plpgsql(anycompatible, anycompatible);
DROP FUNCTION IF EXISTS lab_nvl_strict(anycompatible, anycompatible);
DROP FUNCTION IF EXISTS lab_nvl_elem(anyelement, anyelement);
DROP FUNCTION IF EXISTS lab_nvl_std(anycompatible, anycompatible);
DROP FUNCTION IF EXISTS lab_touch(text);
DROP SEQUENCE IF EXISTS lab_touch_seq;

-- 10-1 브리지를 만들었다면
DROP FUNCTION IF EXISTS compat.nvl(anycompatible, anycompatible);
DROP SCHEMA   IF EXISTS compat;          -- 비어 있을 때만 성공. CASCADE 금지
-- ALTER ROLE app_user RESET search_path;

-- orafce를 설치했다면 (의존 객체가 있으면 실패 → CASCADE 전에 pg_depend 확인)
DROP EXTENSION IF EXISTS orafce;

-- 세션 파라미터
RESET jit;
RESET max_parallel_workers_per_gather;
RESET track_functions;
RESET track_io_timing;
```
