---
주제: CTE (WITH 절)
폴더: SQL/CTE
분류: PostgreSQL / SQL 처리
난이도: 중급
관련버전: PostgreSQL 12+ (시나리오 2의 CYCLE 절은 14+)
작성일: 2026-10-21
선행지식: [서브쿼리, JOIN, EXPLAIN 기본]
깊이: 기본
---

# CTE (WITH 절) — 실습

관련 문서: [이론](./CTE-이론.md)

> 전제: PostgreSQL 16, 로컬 환경, 기본 설정.
> **작성 환경에서 측정하지 않았다.** 실행계획은 "기대 형태"이며, 시간은 직접 실행해서 `___` 칸에 적는다.

---

## 1. 무엇을 확인하나

| 시나리오 | 성공 기준 |
|---|---|
| 1. CTE를 두 번 참조하면 느려진다 | 실행계획에 `CTE Scan` + `Rows Removed by Filter: 999990`이 보이고, 개선 후 사라진다 |
| 2. 재귀 CTE: 인덱스와 순환 | 인덱스 전후로 반복 부분이 `Seq Scan` → `Index Scan`으로 바뀐다. 순환 데이터가 `CYCLE`로 멈춘다 |
| 3. 데이터 변경 CTE | 84,939행이 한 문장으로 이동한다. 같은 문장에서 테이블을 다시 읽으면 옛날 값이 보인다 |

---

## 2. 데이터 준비

```sql
-- psql 전용: 실행 시간 표시
\timing on

DROP TABLE IF EXISTS lab_orders, lab_orders_archive, lab_employee, lab_graph;  -- 실습 DB에서만

-- 주문 100만 행: 고객 10만 명 × 1인당 정확히 10건
-- 100만 행이면 "인덱스로 10건 찾기"와 "전체 읽기"의 차이가 시간에서 확실히 보인다
CREATE TABLE lab_orders (
    id          bigint PRIMARY KEY,
    customer_id int    NOT NULL,
    status      text   NOT NULL,
    amount      int    NOT NULL,
    created_at  date   NOT NULL
);
INSERT INTO lab_orders
SELECT g,
       (g % 100000) + 1,
       CASE WHEN g % 10 < 3 THEN 'PENDING' ELSE 'PAID' END,
       (g % 1000) * 100,
       date '2025-01-01' + (g % 365)
FROM generate_series(1, 1000000) g;
CREATE INDEX lab_orders_customer_id_idx ON lab_orders (customer_id);

CREATE TABLE lab_orders_archive (LIKE lab_orders INCLUDING ALL);

-- 직원 10만 명: 한 사람당 부하 10명인 트리. id=2 아래에 정확히 11,111명 (자신 포함)
CREATE TABLE lab_employee (
    id         int PRIMARY KEY,
    manager_id int,
    name       text NOT NULL
);
INSERT INTO lab_employee
SELECT g, CASE WHEN g = 1 THEN NULL ELSE (g - 2) / 10 + 1 END, 'emp_' || g
FROM generate_series(1, 100000) g;
-- manager_id 인덱스는 시나리오 2에서 일부러 나중에 만든다

-- 순환 그래프: 1 → 2 → 3 → 1
CREATE TABLE lab_graph (src int, dst int);
INSERT INTO lab_graph VALUES (1, 2), (2, 3), (3, 1);

ANALYZE lab_orders, lab_orders_archive, lab_employee, lab_graph;  -- 통계 수집. 이걸 빼면 실행계획이 엉뚱해진다
```

---

## 3. 시나리오

각 쿼리는 **두 번 실행해서 두 번째 시간을 적는다.** 첫 번째는 데이터를 메모리에 올리는 시간이 섞인다.

### 시나리오 1. CTE를 두 번 참조하면 느려진다

#### 1-1. 흔히 쓰는 방식

```sql
-- 비교 기준: 한 번만 참조 → 인라인
EXPLAIN (ANALYZE, BUFFERS)
WITH c AS (SELECT * FROM lab_orders)
SELECT * FROM c WHERE customer_id = 42;

-- 문제: 두 번 참조 → 물리화
EXPLAIN (ANALYZE, BUFFERS)
WITH c AS (SELECT * FROM lab_orders)
SELECT (SELECT count(*)    FROM c WHERE customer_id = 42) AS cnt,
       (SELECT sum(amount) FROM c WHERE customer_id = 42) AS total;
```

#### 1-2. 실행계획에서 볼 곳

한 번 참조 (기대 형태, 핵심 줄만):

```
Bitmap Heap Scan on lab_orders  (actual rows=10)
  ->  Bitmap Index Scan on lab_orders_customer_id_idx
        Index Cond: (customer_id = 42)
```

- **CTE라는 단어가 없다.** 서브쿼리처럼 펼쳐져서 인덱스를 썼다.

두 번 참조 (기대 형태, 핵심 줄만):

```
CTE c
  ->  Seq Scan on lab_orders  (actual rows=1000000)       ← ① 100만 행 전부 읽어서 저장
InitPlan ...
  ->  CTE Scan on c  (rows=5000) (actual rows=10)
        Filter: (customer_id = 42)
        Rows Removed by Filter: 999990                    ← ② 그중 10행만 쓰고 버림
InitPlan ...
  ->  CTE Scan on c c_1  (actual rows=10)
        Rows Removed by Filter: 999990                    ← ③ 저장본을 한 번 더 전부 훑음
```

- ①: 바깥 조건 `customer_id = 42`가 CTE 안으로 들어가지 못해 전체를 읽었다.
- ②③: 저장본에는 인덱스가 없어서 매번 100만 행을 훑는다.
- `rows=5000`(추정) vs `actual rows=10`(실제): 저장본에는 통계가 없어서 대충 추정한 값이다.
- `Buffers: temp read/written`이 보이면 저장본이 메모리(`work_mem` 4MB)를 넘어 임시 파일로 간 것이다.

#### 1-3. 개선

```sql
-- 개선 A: 저장하지 말고 펼쳐라
EXPLAIN (ANALYZE, BUFFERS)
WITH c AS NOT MATERIALIZED (SELECT * FROM lab_orders)
SELECT (SELECT count(*)    FROM c WHERE customer_id = 42) AS cnt,
       (SELECT sum(amount) FROM c WHERE customer_id = 42) AS total;

-- 개선 B (더 좋음): 조건을 CTE 안으로
EXPLAIN (ANALYZE, BUFFERS)
WITH c AS (SELECT * FROM lab_orders WHERE customer_id = 42)
SELECT (SELECT count(*) FROM c) AS cnt,
       (SELECT sum(amount) FROM c) AS total;
```

- A: `CTE Scan`이 사라지고 `Index Cond: (customer_id = 42)`가 두 번 나온다.
- B: CTE는 여전히 물리화되지만 **10행만** 저장한다. `CTE c` 아래가 `Bitmap Index Scan`으로 바뀐다.

> 실행 시간: 한 번 참조 ___ ms / 두 번 참조 ___ ms → 개선 A ___ ms / 개선 B ___ ms

#### 1-4. 반대 사례: 저장이 이득인 경우

```sql
-- 기본(물리화): 집계 1번
EXPLAIN (ANALYZE, BUFFERS)
WITH agg AS (SELECT customer_id, sum(amount) AS s FROM lab_orders GROUP BY customer_id)
SELECT count(*) FROM agg WHERE s > (SELECT avg(s) FROM agg);

-- NOT MATERIALIZED: 집계 2번
EXPLAIN (ANALYZE, BUFFERS)
WITH agg AS NOT MATERIALIZED (SELECT customer_id, sum(amount) AS s FROM lab_orders GROUP BY customer_id)
SELECT count(*) FROM agg WHERE s > (SELECT avg(s) FROM agg);
```

- 볼 곳: `HashAggregate`(GROUP BY를 처리하는 단계)가 기본에서는 **1번**, `NOT MATERIALIZED`에서는 **2번** 나온다. `Seq Scan on lab_orders`도 2번이 된다.
- 결론: "두 번 참조 = 무조건 NOT MATERIALIZED"가 아니다. **바깥 조건이 많이 걸러내면 펼치고, 중간 결과가 작고 비싸면 저장한다.**

> 실행 시간: 기본 ___ ms / NOT MATERIALIZED ___ ms

---

### 시나리오 2. 재귀 CTE: 인덱스와 순환

#### 2-1. 흔히 쓰는 방식 (manager_id 인덱스 없음)

```sql
EXPLAIN (ANALYZE, BUFFERS)
WITH RECURSIVE sub AS (
    SELECT id, manager_id, 1 AS depth FROM lab_employee WHERE id = 2
  UNION ALL
    SELECT e.id, e.manager_id, s.depth + 1
    FROM lab_employee e JOIN sub s ON e.manager_id = s.id
)
SELECT depth, count(*) FROM sub GROUP BY depth ORDER BY depth;
```

결과 (데이터 규칙상 확정):

```
 depth | count
-------+-------
     1 |     1
     2 |    10
     3 |   100
     4 |  1000
     5 | 10000
```

#### 2-2. 실행계획에서 볼 곳

```
Recursive Union  (actual rows=11111)
  ->  Index Scan using lab_employee_pkey  (actual rows=1)       ← 시작점
        Index Cond: (id = 2)
  ->  Hash Join  (loops=5)                                      ← 반복 부분, 5번 실행
        ->  Seq Scan on lab_employee e  (actual rows=100000 loops=5)   ← 매번 10만 행 전체 읽기
        ->  Hash
              ->  WorkTable Scan on sub s                       ← "직전 단계에서 찾은 행"
```

- `loops=5`: 반복 부분이 5번 돌았다. (정확한 값은 실측으로 확인)
- `Seq Scan ... rows=100000 loops=5`: **반복할 때마다 테이블 전체**를 읽었다. 트리가 커질수록 느려지는 이유다.

#### 2-3. 개선: 조인 컬럼에 인덱스

```sql
CREATE INDEX lab_employee_manager_id_idx ON lab_employee (manager_id);  -- 운영에서는 CONCURRENTLY로
ANALYZE lab_employee;
-- 2-1 쿼리 다시 실행
```

기대 변화:

```
  ->  Nested Loop  (loops=5)
        ->  WorkTable Scan on sub s
        ->  Index Scan using lab_employee_manager_id_idx on lab_employee e
              Index Cond: (manager_id = s.id)                   ← 부하 직원만 인덱스로 찾음
```

> 실행 시간: 인덱스 없음 ___ ms → 인덱스 있음 ___ ms

#### 2-4. 순환 데이터에서 끝나지 않는 재귀

```sql
SET statement_timeout = '5s';   -- 안전장치. 세션에만 적용

-- 끝나지 않는다 → 5초 후 ERROR: canceling statement due to statement timeout
WITH RECURSIVE walk AS (
    SELECT 1 AS node, 0 AS depth
  UNION ALL
    SELECT g.dst, w.depth + 1 FROM lab_graph g JOIN walk w ON g.src = w.node
)
SELECT count(*) FROM walk;
```

개선 (PG14+): `CYCLE` 절

```sql
WITH RECURSIVE walk AS (
    SELECT 1 AS node, 0 AS depth
  UNION ALL
    SELECT g.dst, w.depth + 1 FROM lab_graph g JOIN walk w ON g.src = w.node
) CYCLE node SET is_cycle USING path
SELECT node, depth, is_cycle FROM walk;
```

기대 결과:

```
 node | depth | is_cycle
------+-------+----------
    1 |     0 | f
    2 |     1 | f
    3 |     2 | f
    1 |     3 | t        ← 1로 돌아온 걸 감지하고 여기서 멈춤
```

개선 (PG12~13): 지나온 경로를 배열로

```sql
WITH RECURSIVE walk AS (
    SELECT 1 AS node, 0 AS depth, ARRAY[1] AS path
  UNION ALL
    SELECT g.dst, w.depth + 1, w.path || g.dst
    FROM lab_graph g JOIN walk w ON g.src = w.node
    WHERE NOT g.dst = ANY (w.path)   -- 이미 지나온 노드면 더 가지 않음
      AND w.depth < 50               -- 깊이 상한
)
SELECT * FROM walk;                   -- 3행 (1, 2, 3)

RESET statement_timeout;
```

---

### 시나리오 3. 데이터 변경 CTE

모두 `BEGIN ... ROLLBACK`으로 감싸서 원본 데이터를 유지한다.

#### 3-1. 아카이빙을 한 문장으로

```sql
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
WITH moved AS (
    DELETE FROM lab_orders WHERE created_at < '2025-02-01' RETURNING *
)
INSERT INTO lab_orders_archive SELECT * FROM moved;

SELECT (SELECT count(*) FROM lab_orders)         AS remain,     -- 915061
       (SELECT count(*) FROM lab_orders_archive) AS archived;   -- 84939
ROLLBACK;
```

실행계획에서 볼 곳:

```
Insert on lab_orders_archive
  CTE moved
    ->  Delete on lab_orders  (actual rows=84939)    ← 지운 행 수
  ->  CTE Scan on moved  (actual rows=84939)         ← 그대로 INSERT로 전달
```

- 중간에 INSERT가 실패하면(예: 키 중복) **DELETE도 함께 취소**된다. 한 문장이기 때문이다.

#### 3-2. 같은 문장에서는 바뀐 값이 안 보인다

```sql
BEGIN;
WITH u AS (UPDATE lab_orders SET status = 'PAID' WHERE id = 1 RETURNING status)
SELECT 'table'     AS src, status FROM lab_orders WHERE id = 1
UNION ALL
SELECT 'returning' AS src, status FROM u;
-- 기대:
--  table     | PENDING   ← 테이블을 다시 읽으면 바꾸기 전 값
--  returning | PAID      ← RETURNING으로 읽어야 바뀐 값

SELECT status FROM lab_orders WHERE id = 1;   -- PAID (다음 문장부터는 보인다)
ROLLBACK;
```

#### 3-3. 실무형: 묶음 단위로 나눠서 옮기기

한 번에 수십만 행을 옮기면 락이 오래 잡히고 다른 요청이 기다린다. 1만 행씩 나눠서 여러 번 실행하고 매번 커밋한다.

```sql
BEGIN;
WITH target AS (
    SELECT id FROM lab_orders
    WHERE created_at < '2025-02-01'
    ORDER BY id
    LIMIT 10000                 -- DELETE에는 LIMIT이 없어서 여기서 개수를 정한다
    FOR UPDATE SKIP LOCKED      -- 다른 작업이 잡고 있는 행은 건너뜀
), moved AS (
    DELETE FROM lab_orders o USING target t WHERE o.id = t.id RETURNING o.*
)
INSERT INTO lab_orders_archive SELECT * FROM moved;
ROLLBACK;   -- 실습이라 롤백. 실제로는 COMMIT 후 0행이 될 때까지 반복
```

---

## 4. 애플리케이션 연동

### 4.1 재귀 CTE — Spring Data JPA 네이티브 쿼리

```java
package com.example.lab.cte;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LabEmployeeRepository extends JpaRepository<LabEmployee, Integer> {

    // Java 15+면 텍스트 블록(""")으로 쓸 수 있다. Java 8 호환을 위해 문자열 연결로 작성.
    @Query(value =
            "WITH RECURSIVE sub AS ( " +
            "    SELECT id, manager_id, 1 AS depth, ARRAY[id] AS path " +
            "    FROM lab_employee WHERE id = :rootId " +
            "  UNION ALL " +
            "    SELECT e.id, e.manager_id, s.depth + 1, s.path || e.id " +
            "    FROM lab_employee e JOIN sub s ON e.manager_id = s.id " +
            "    WHERE NOT e.id = ANY (s.path) AND s.depth < :maxDepth " +
            ") " +
            "SELECT id, manager_id AS managerId, depth FROM sub ORDER BY depth, id",
            nativeQuery = true)
    List<SubordinateRow> findSubordinates(@Param("rootId") int rootId,
                                          @Param("maxDepth") int maxDepth);
}
```

```java
package com.example.lab.cte;

/** 네이티브 쿼리 결과를 받는 인터페이스 프로젝션. 컬럼 별칭과 getter 이름이 맞아야 한다. */
public interface SubordinateRow {
    Integer getId();
    Integer getManagerId();
    Integer getDepth();
}
```

```java
package com.example.lab.cte;

import jakarta.persistence.Entity;   // Spring Boot 2.x / Java 8: javax.persistence.Entity
import jakarta.persistence.Id;       // Spring Boot 2.x / Java 8: javax.persistence.Id
import jakarta.persistence.Table;    // Spring Boot 2.x / Java 8: javax.persistence.Table

@Entity
@Table(name = "lab_employee")
public class LabEmployee {

    @Id
    private Integer id;

    private Integer managerId;

    private String name;

    protected LabEmployee() {
    }

    public Integer getId() {
        return id;
    }

    public Integer getManagerId() {
        return managerId;
    }

    public String getName() {
        return name;
    }
}
```

- Hibernate 6.2+(Spring Boot 3.1+)면 HQL에서도 `WITH` 절을 쓸 수 있지만, 재귀·`ARRAY` 같은 PostgreSQL 문법은 네이티브 쿼리가 가장 확실하다.

### 4.2 데이터 변경 CTE — 묶음 단위 아카이빙

```java
package com.example.lab.cte;

import jakarta.persistence.EntityManager;        // Spring Boot 2.x: javax.persistence.EntityManager
import jakarta.persistence.PersistenceContext;   // Spring Boot 2.x: javax.persistence.PersistenceContext

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LabOrderArchiveService {

    private static final String ARCHIVE_SQL =
            "WITH target AS ( " +
            "    SELECT id FROM lab_orders " +
            "    WHERE created_at < CAST(:before AS date) " +
            "    ORDER BY id LIMIT :batchSize " +
            "    FOR UPDATE SKIP LOCKED " +
            "), moved AS ( " +
            "    DELETE FROM lab_orders o USING target t WHERE o.id = t.id RETURNING o.* " +
            "), ins AS ( " +
            "    INSERT INTO lab_orders_archive SELECT * FROM moved RETURNING id " +
            ") " +
            "SELECT count(*) FROM ins";

    @PersistenceContext
    private EntityManager em;

    /** 한 묶음만 옮기고 커밋한다. 결과를 SELECT로 돌려받으므로 executeUpdate가 아니라 getSingleResult. */
    @Transactional
    public int archiveOneBatch(String before, int batchSize) {
        Object result = em.createNativeQuery(ARCHIVE_SQL)
                .setParameter("before", before)
                .setParameter("batchSize", batchSize)
                .getSingleResult();
        return ((Number) result).intValue();
    }
}
```

```java
package com.example.lab.cte;

import org.springframework.stereotype.Component;

@Component
public class LabOrderArchiveJob {

    private final LabOrderArchiveService archiveService;

    public LabOrderArchiveJob(LabOrderArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    /** 반복은 트랜잭션 바깥에서. 호출마다 별도 트랜잭션으로 커밋된다. */
    public long run(String before, int batchSize) {
        long total = 0;
        int moved;
        do {
            moved = archiveService.archiveOneBatch(before, batchSize);
            total += moved;
        } while (moved > 0);
        return total;
    }
}
```

포인트:

- **반복 루프를 `@Transactional` 메서드 안에 넣지 않는다.** 그러면 전체가 한 트랜잭션이 되어 묶음으로 나눈 의미가 없다. 같은 클래스 안에서 호출하면 프록시를 거치지 않아 `@Transactional`이 적용되지 않으므로, 루프는 **다른 빈**(`LabOrderArchiveJob`)에 둔다.
- 네이티브 DML은 영속성 컨텍스트를 갱신하지 않는다. 같은 트랜잭션에서 `LabOrder` 엔티티를 이미 읽어 뒀다면 지워진 행이 1차 캐시에 남는다. 필요하면 `em.clear()`.

### 4.3 MyBatis

```xml
<select id="findSubordinates" resultType="com.example.lab.cte.SubordinateDto">
    WITH RECURSIVE sub AS (
        SELECT id, manager_id, 1 AS depth
        FROM lab_employee
        WHERE id = #{rootId}
      UNION ALL
        SELECT e.id, e.manager_id, s.depth + 1
        FROM lab_employee e
        JOIN sub s ON e.manager_id = s.id
        WHERE s.depth &lt; #{maxDepth}
    )
    SELECT id, manager_id, depth FROM sub ORDER BY depth, id
</select>
```

- XML에서 `<`는 `&lt;`로 쓰거나 `<![CDATA[ ... ]]>`로 감싼다.
- `<if>`로 붙이는 동적 조건은 **CTE 안쪽**에 넣는다. CTE를 2번 이상 참조하는 구조에서 바깥에 두면 시나리오 1처럼 된다.

---

## 5. 정리

```sql
RESET statement_timeout;
\timing off                     -- psql 전용

-- DROP은 테이블을 완전히 잠근다. 운영 DB에서 실행 금지
DROP TABLE IF EXISTS lab_orders, lab_orders_archive, lab_employee, lab_graph;
```
