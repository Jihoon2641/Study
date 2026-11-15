---
주제: EXISTS / NOT EXISTS (존재 여부로 거르기, 세미 조인·안티 조인)
폴더: SQL/EXISTS
분류: PostgreSQL / SQL 처리
난이도: 중급
관련버전: PostgreSQL 12+
작성일: 2026-11-15
선행지식: [서브쿼리, LEFT JOIN, EXPLAIN 기본]
깊이: 기본
---

# EXISTS / NOT EXISTS — 실습

관련 문서: [이론](./EXISTS-이론.md)

> **작성 환경에서 측정하지 않음.** 실행계획은 "기대 형태"이고, 시간은 직접 측정해 빈칸에 적는다.
> 전제: PostgreSQL 16, 기본 `work_mem`(4MB).

---

## 1. 무엇을 확인하나

| 시나리오 | 성공 기준 |
|---|---|
| 1. `NOT IN` vs `NOT EXISTS` | NULL 한 건에 `NOT IN`이 0건이 되는 것, `work_mem`이 작으면 `hashed SubPlan` → `SubPlan`으로 바뀌는 것, `NOT EXISTS`는 항상 `Hash Anti Join`인 것 |
| 2. `OR EXISTS` | `SubPlan` + `loops=100000`이 보이고, `UNION`으로 쪼개면 `Semi Join`으로 바뀌는 것 |
| 3. `COUNT(*)` vs `EXISTS` 존재 확인 | `COUNT`는 수십만 행을 읽고, `EXISTS`는 몇 행만 읽고 멈추는 것 (`Buffers`·`rows` 비교) |

---

## 2. 데이터 준비

고객 10만 / 주문 100만. 주문 쪽이 커서 `NOT IN` 해시가 메모리 한도를 넘는 상황을 만들 수 있는 규모.
고객 90,001~100,000번(1만 명)은 주문이 없다.

```sql
DROP TABLE IF EXISTS lab_order;      -- 실습용 테이블만. 운영 DB에서 실행 금지
DROP TABLE IF EXISTS lab_customer;

CREATE TABLE lab_customer (
    id     bigint PRIMARY KEY,
    name   text   NOT NULL,
    grade  text   NOT NULL
);

CREATE TABLE lab_order (
    id          bigint PRIMARY KEY,
    customer_id bigint,                 -- NULL 허용 (비회원 주문을 흉내 내기 위해)
    amount      numeric(12,0) NOT NULL,
    status      text   NOT NULL,
    ordered_at  timestamp NOT NULL
);

INSERT INTO lab_customer (id, name, grade)
SELECT g, 'cust_' || g, CASE WHEN g % 100 = 0 THEN 'VIP' ELSE 'NORMAL' END
FROM generate_series(1, 100000) AS g;

INSERT INTO lab_order (id, customer_id, amount, status, ordered_at)
SELECT g,
       1 + (random() * 89999)::int,                         -- 1 ~ 90,000번 고객만 주문
       (random() * 2000000)::int,
       CASE WHEN random() < 0.9 THEN 'DONE' ELSE 'WAIT' END, -- DONE 90%
       TIMESTAMP '2024-01-01' + (random() * 365) * INTERVAL '1 day'
FROM generate_series(1, 1000000) AS g;

CREATE INDEX lab_order_customer_id_idx ON lab_order (customer_id);
CREATE INDEX lab_order_status_idx      ON lab_order (status);

ANALYZE lab_customer;
ANALYZE lab_order;
```

psql 전용: 시간을 보려면 `\timing on`.

---

## 3. 시나리오

### 시나리오 1 — `NOT IN` vs `NOT EXISTS` (주문 없는 고객)

#### 1) 흔히 쓰는 방식

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM lab_customer c
WHERE c.id NOT IN (SELECT o.customer_id FROM lab_order o);
-- 기대 결과: 10000 (전후로 약간 차이 가능 — random 분포)
```

#### 2) 실행계획에서 볼 곳 (기대 형태)

```
Seq Scan on lab_customer c  (actual rows=10000 loops=1)
  Filter: (NOT (hashed SubPlan 1))
  Rows Removed by Filter: 90000
  SubPlan 1
    ->  Seq Scan on lab_order o  (actual rows=1000000 loops=1)
```

- `hashed SubPlan` → 주문 100만 건의 `customer_id`로 해시를 한 번 만들고 고객마다 조회. 지금은 빠르다.
- `Anti Join`이 **없다** → PG가 `NOT IN`을 조인으로 바꾸지 않았다.

**메모리가 모자라면** (데이터가 커진 운영 상황을 흉내):

```sql
SET work_mem = '64kB';               -- 이 세션에서만
EXPLAIN                               -- ANALYZE 없이 계획만 본다 (실제로 돌리면 매우 오래 걸림)
SELECT count(*) FROM lab_customer c
WHERE c.id NOT IN (SELECT o.customer_id FROM lab_order o);
-- 기대: Filter: (NOT (SubPlan 1))       ← hashed가 사라짐
--       SubPlan 1 -> Materialize -> Seq Scan on lab_order o
```

정말 돌려 보고 싶으면 `SET statement_timeout = '10s';` 후 `EXPLAIN ANALYZE` — 끝나지 않고 취소되는 것이 정상이다.
고객 1명마다 주문 100만 건을 처음부터 훑기 때문이다.

**NULL 한 건이 들어오면**:

```sql
BEGIN;
INSERT INTO lab_order VALUES (1000001, NULL, 5000, 'DONE', now());   -- 비회원 주문 1건

SELECT count(*) FROM lab_customer c
WHERE c.id NOT IN (SELECT o.customer_id FROM lab_order o);
-- 기대: 0   ← 1만 명이 한꺼번에 사라짐

SELECT count(*) FROM lab_customer c
WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.customer_id = c.id);
-- 기대: 10000

ROLLBACK;
```

#### 3) 개선한 방식 — `NOT EXISTS`

```sql
RESET work_mem;
SET work_mem = '64kB';    -- 메모리가 작아도 괜찮은지 같이 확인

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*)
FROM lab_customer c
WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.customer_id = c.id);
```

```
Hash Anti Join  (actual rows=10000 ...)
  Hash Cond: (c.id = o.customer_id)
  ->  Seq Scan on lab_customer c
  ->  Hash
        Buckets: ...  Batches: 32  Memory Usage: ...
        ->  Seq Scan on lab_order o
```

- `Hash Anti Join` → 안티 조인으로 바뀌었다.
- `Batches: 32` 같은 1보다 큰 값 → 메모리가 모자라 디스크에 나눠 처리했다. 느려지긴 해도 **행마다 재스캔하지는 않는다.**
- (계획에 따라 `Merge Anti Join`이나 인덱스를 쓰는 `Nested Loop Anti Join`이 나올 수도 있다. 핵심은 `Anti Join`이 있다는 것.)

```sql
RESET work_mem;
```

```
실행 시간: ___ ms (NOT IN, 기본 work_mem) / 타임아웃 (NOT IN, 64kB) → ___ ms (NOT EXISTS, 64kB)
```

---

### 시나리오 2 — `OR EXISTS` (VIP이거나 100만원 넘는 주문이 있는 고객)

#### 1) 흔히 쓰는 방식

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id, c.name
FROM lab_customer c
WHERE c.grade = 'VIP'
   OR EXISTS (SELECT 1 FROM lab_order o
              WHERE o.customer_id = c.id AND o.amount > 1900000);
```

#### 2) 실행계획에서 볼 곳 (기대 형태)

```
Seq Scan on lab_customer c  (actual rows=... loops=1)
  Filter: ((grade = 'VIP'::text) OR (alternatives: SubPlan 1 or hashed SubPlan 2))
  SubPlan 1
    ->  Index Scan using lab_order_customer_id_idx on lab_order o  (actual ... loops=99000)
          Index Cond: (customer_id = c.id)
          Filter: (amount > '1900000'::numeric)
```

- `Semi Join`이 없고 `SubPlan`이 남았다 → `OR` 때문에 조인으로 못 바꿨다.
- `loops=99000` → VIP가 아닌 고객마다 서브쿼리를 실행했다. **`loops` × 1회 시간**이 전체 비용이다.
- 버전·통계에 따라 `alternatives: ... or hashed SubPlan 2`가 보이고 해시 쪽이 선택될 수도 있다 (PG 16 이하). 어느 쪽이든 조인은 아니다.
- 인덱스(`lab_order_customer_id_idx`)가 있어서 그나마 견딜 만한 것이다. 인덱스를 지우면 고객마다 주문 100만 건을 훑는다.

#### 3) 개선한 방식 — `UNION`으로 쪼개기

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT c.id, c.name FROM lab_customer c WHERE c.grade = 'VIP'
UNION
SELECT c.id, c.name FROM lab_customer c
WHERE EXISTS (SELECT 1 FROM lab_order o
              WHERE o.customer_id = c.id AND o.amount > 1900000);
```

```
HashAggregate  (UNION 중복 제거)
  ->  Append
        ->  Seq Scan on lab_customer c  Filter: (grade = 'VIP')
        ->  Hash Semi Join
              Hash Cond: (c_1.id = o.customer_id)
              ->  Seq Scan on lab_customer c_1
              ->  Hash -> Seq Scan on lab_order o  Filter: (amount > ...)
```

- 아래쪽이 `Hash Semi Join`으로 바뀌었다 → 주문 테이블을 **한 번만** 읽는다.
- `UNION`(중복 제거)이 필요한 이유: VIP이면서 큰 주문이 있는 고객이 양쪽에 다 나오기 때문.

```
실행 시간: ___ ms (OR EXISTS) → ___ ms (UNION)
```

---

### 시나리오 3 — 존재 확인: `COUNT(*)` vs `EXISTS`

"처리 완료(DONE) 주문이 하나라도 있나?" — DONE은 전체의 90%.

#### 1) 흔히 쓰는 방식

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) AS cnt FROM lab_order WHERE status = 'DONE';
-- 애플리케이션에서 cnt > 0 으로 판단
```

#### 2) 실행계획에서 볼 곳 (기대 형태)

```
Finalize Aggregate
  ->  Parallel Seq Scan on lab_order  (actual rows=300000 loops=3)
        Filter: (status = 'DONE'::text)
        Buffers: shared hit=... read=...
```

- `actual rows` × `loops` ≈ 90만 → 답은 "있다/없다"인데 90만 건을 다 셌다.

#### 3) 개선한 방식

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT EXISTS (SELECT 1 FROM lab_order WHERE status = 'DONE') AS has_done;
```

```
Result
  InitPlan 1
    ->  Seq Scan on lab_order  (actual rows=1 loops=1)
          Filter: (status = 'DONE'::text)
          Buffers: shared hit=1
```

- `actual rows=1` → 첫 행을 찾자마자 멈췄다.
- `Buffers`가 수천 → 한 자릿수로 줄었다.
- 바깥과 무관한 서브쿼리라 `InitPlan`(한 번만 실행)이 됐다.

> 반대로 'WAIT'처럼 드문 값이나 **아예 없는 값**이면 `EXISTS`도 끝까지 찾아야 한다. 그때는 인덱스(`lab_order_status_idx`)가 있는지가 속도를 정한다. `status = 'NONE'`으로 바꿔서 실행계획을 비교해 보자.

```
실행 시간: ___ ms (COUNT) → ___ ms (EXISTS)
```

---

## 4. 애플리케이션 연동

### MyBatis 3 매퍼

```xml
<!-- LabOrderMapper.xml -->
<mapper namespace="com.example.lab.LabOrderMapper">

  <!-- Before: SELECT COUNT(*) FROM lab_order WHERE customer_id = #{customerId} → Java에서 > 0 -->
  <select id="existsByCustomerId" resultType="boolean">
    SELECT EXISTS (SELECT 1 FROM lab_order WHERE customer_id = #{customerId})
  </select>

  <!-- 주문 없는 고객: NOT IN 대신 NOT EXISTS -->
  <select id="findCustomersWithoutOrder" resultType="map">
    SELECT c.id, c.name
    FROM lab_customer c
    WHERE NOT EXISTS (SELECT 1 FROM lab_order o WHERE o.customer_id = c.id)
    ORDER BY c.id
    LIMIT #{limit}
  </select>
</mapper>
```

### iBatis 2 (이 프로젝트 형식)

```xml
<select id="LabOrder.existsByCustomerId" parameterClass="hmap" resultClass="java.lang.Boolean">
  SELECT EXISTS (SELECT 1 FROM lab_order WHERE customer_id = #customerId#::bigint)
</select>
```

### Java (Java 8+ 호환)

```java
package com.example.lab;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Mapper
interface LabOrderMapper {
    boolean existsByCustomerId(@Param("customerId") long customerId);
    List<Map<String, Object>> findCustomersWithoutOrder(@Param("limit") int limit);
    int insertFavorite(@Param("customerId") long customerId, @Param("productId") long productId);
}

@Service
public class LabOrderService {

    private final LabOrderMapper mapper;

    public LabOrderService(LabOrderMapper mapper) {
        this.mapper = mapper;
    }

    public boolean hasOrder(long customerId) {
        return mapper.existsByCustomerId(customerId);   // COUNT(*) > 0 대신
    }

    /**
     * 중복 방지는 DB 유니크 제약(customer_id, product_id)이 담당한다.
     * "있으면 거절" 확인만으로는 동시 요청 두 개가 둘 다 통과할 수 있다 (READ COMMITTED).
     */
    @Transactional
    public boolean addFavorite(long customerId, long productId) {
        try {
            mapper.insertFavorite(customerId, productId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;   // 이미 등록됨
        }
    }
}
```

포인트:
- `resultType="boolean"` — PG `boolean`이 Java `boolean`으로 그대로 매핑된다.
- `addFavorite`는 `EXISTS` 확인 없이 바로 넣고 제약 위반을 잡는다. `INSERT ... ON CONFLICT DO NOTHING`을 쓰고 영향받은 행 수(0/1)로 판단해도 된다.
- `@Transactional` 안에서 `DuplicateKeyException`이 나면 PG는 그 트랜잭션을 실패 상태로 만든다. 같은 트랜잭션에서 뒤에 다른 SQL을 더 실행해야 한다면 `ON CONFLICT DO NOTHING` 방식이 안전하다.

---

## 5. 정리

```sql
RESET work_mem;
RESET statement_timeout;
-- 실습 객체만 삭제. 운영 DB에서 이름을 확인하지 않고 실행 금지
DROP TABLE IF EXISTS lab_order;      -- 인덱스도 함께 삭제됨
DROP TABLE IF EXISTS lab_customer;
```
