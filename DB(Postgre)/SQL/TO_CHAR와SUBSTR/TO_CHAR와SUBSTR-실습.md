---
주제: TO_CHAR와 SUBSTR (날짜·숫자 → 문자열 변환과 문자열 자르기)
폴더: SQL/TO_CHAR와SUBSTR
분류: PostgreSQL / SQL 처리
난이도: 초급~중급
관련버전: PostgreSQL 12+ (FF6 포맷은 13+)
작성일: 2026-11-15
선행지식: [B-tree 인덱스 기본, EXPLAIN 기본]
깊이: 기본
---

# TO_CHAR와 SUBSTR — 실습

관련 문서: [이론](./TO_CHAR와SUBSTR-이론.md)

> **작성 환경에서 측정하지 않음.** 실행계획은 "기대 형태"이고, 시간은 직접 측정해 빈칸에 적는다.
> 전제: PostgreSQL 16. orafce가 없는 로컬 DB에서도 돌아가게 PG 기본 함수만 썼다 (시나리오 3의 orafce 비교 부분만 예외).

---

## 1. 무엇을 확인하나

| 시나리오 | 성공 기준 |
|---|---|
| 1. `SUBSTR(문자열날짜,1,6)` 월 조회 | `Seq Scan + Filter` → 범위 비교로 바꾸면 `Index Scan`(또는 `Bitmap Index Scan`)으로 바뀐다 |
| 2. `TO_CHAR(timestamp,'YYYYMMDD')` 일 조회 | 같은 변화 + `TO_CHAR` 표현식 인덱스가 **에러**로 막히는 것을 확인 |
| 3. 결과값 함정 | `SUBSTR(x,0,6)`, `HH`/`MM`, `FM999.99`가 예상과 다른 값을 내는 것을 눈으로 확인 |

---

## 2. 데이터 준비

100만 행: Seq Scan과 Index Scan의 시간 차이가 눈에 보이면서 로컬 PC에서 수십 초 안에 만들어지는 규모.
이 프로젝트처럼 **문자열 날짜(`cont_ymd`)와 timestamp(`reg_dt`)를 같이** 둔다.

```sql
DROP TABLE IF EXISTS lab_contract;  -- 실습용 테이블만. 운영 DB에서 실행 금지

CREATE TABLE lab_contract (
    cont_id   bigint PRIMARY KEY,
    cont_ymd  varchar(8)  NOT NULL,   -- 'YYYYMMDD' 문자열 날짜
    reg_dt    timestamp   NOT NULL,   -- 등록 시각
    amt       numeric(15,2) NOT NULL
);

-- 2020-01-01부터 약 5년(1,826일)에 고르게 분포
INSERT INTO lab_contract (cont_id, cont_ymd, reg_dt, amt)
SELECT g,
       TO_CHAR(d, 'YYYYMMDD'),
       d + (g % 86400) * INTERVAL '1 second',
       ROUND((random() * 10000000)::numeric, 2)
FROM generate_series(1, 1000000) AS g,
     LATERAL (SELECT DATE '2020-01-01' + (g % 1826) AS d) AS x;

CREATE INDEX lab_contract_cont_ymd_idx ON lab_contract (cont_ymd);
CREATE INDEX lab_contract_reg_dt_idx   ON lab_contract (reg_dt);

ANALYZE lab_contract;
```

한 달 ≈ 1.6만 행(전체의 1.6%), 하루 ≈ 550행. 인덱스가 확실히 이득인 비율이다.

psql 전용: 시간을 보려면 `\timing on`.

---

## 3. 시나리오

### 시나리오 1 — `SUBSTR`로 월 조회 (매퍼에서 가장 흔한 형태)

#### 1) 흔히 쓰는 방식

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*), SUM(amt)
FROM lab_contract
WHERE SUBSTR(cont_ymd, 1, 6) = '202401';
```

#### 2) 실행계획에서 볼 곳 (기대 형태)

```
->  Parallel Seq Scan on lab_contract  (cost=... rows=2083 ...) (actual ... rows=5473 loops=3)
      Filter: (substr((cont_ymd)::text, 1, 6) = '202401'::text)
      Rows Removed by Filter: 327860
      Buffers: shared hit=... read=...
```

- `Seq Scan` + `Filter: (substr(...))` → 인덱스를 못 탔다.
- `Rows Removed by Filter` → 약 98만 행을 읽고 버렸다.
- 추정 `rows`(대략 전체의 0.5%를 워커 수로 나눈 값)와 `actual rows`가 다르다 → 함수 결과에 통계가 없다.

#### 3) 개선한 방식 A — 범위 비교

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*), SUM(amt)
FROM lab_contract
WHERE cont_ymd >= '20240101' AND cont_ymd < '20240201';
```

```
->  Bitmap Heap Scan on lab_contract  (... rows=16900 ...) (actual ... rows=16926 ...)
      Recheck Cond: (((cont_ymd)::text >= '20240101'::text) AND ((cont_ymd)::text < '20240201'::text))
      ->  Bitmap Index Scan on lab_contract_cont_ymd_idx
            Index Cond: (...)
```

- `Index Cond`에 조건이 들어갔다 → 인덱스로 범위만 읽었다.
- 추정 `rows`가 실제와 거의 같다 → 원본 컬럼의 통계를 썼다.
- `Buffers` 숫자가 크게 줄었는지 비교한다.

#### 3') 개선한 방식 B — 쿼리를 못 고칠 때: 표현식 인덱스

```sql
CREATE INDEX lab_contract_ym_idx ON lab_contract (SUBSTR(cont_ymd, 1, 6));
ANALYZE lab_contract;   -- 표현식 인덱스도 통계를 따로 모은다

EXPLAIN (ANALYZE, BUFFERS)
SELECT COUNT(*), SUM(amt)
FROM lab_contract
WHERE SUBSTR(cont_ymd, 1, 6) = '202401';
-- 기대: Bitmap Index Scan on lab_contract_ym_idx
--       Index Cond: (substr((cont_ymd)::text, 1, 6) = '202401'::text)
```

- 쿼리의 식이 인덱스 식과 **글자 그대로 같아야** 탄다. `SUBSTR(cont_ymd, 0, 6)`이나 `LEFT(cont_ymd, 6)`으로 쓰면 못 탄다.
- `ANALYZE` 뒤에는 추정 `rows`도 정확해진다.

```
실행 시간: ___ ms (SUBSTR) → ___ ms (범위) / ___ ms (표현식 인덱스)
```

---

### 시나리오 2 — `TO_CHAR`로 일 조회

#### 1) 흔히 쓰는 방식

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM lab_contract
WHERE TO_CHAR(reg_dt, 'YYYYMMDD') = '20240115';
```

#### 2) 실행계획에서 볼 곳 (기대 형태)

```
Parallel Seq Scan on lab_contract
  Filter: (to_char(reg_dt, 'YYYYMMDD'::text) = '20240115'::text)
  Rows Removed by Filter: ...
```

`reg_dt` 인덱스가 있는데도 쓰지 못한다. 100만 번 `TO_CHAR`를 실행하므로 `SUBSTR`보다 CPU를 더 쓴다 (날짜 포맷 처리가 문자열 자르기보다 무겁다 — 시간을 직접 비교해 확인할 것).

#### 3) 개선한 방식 — 컬럼은 그대로, 상수 쪽을 변환

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM lab_contract
WHERE reg_dt >= TO_DATE('20240115', 'YYYYMMDD')
  AND reg_dt <  TO_DATE('20240115', 'YYYYMMDD') + 1;
-- 기대: Index Scan using lab_contract_reg_dt_idx
--       Index Cond: ((reg_dt >= ...) AND (reg_dt < ...))
```

`TO_DATE(...)`는 상수에만 걸려 있어서 한 번만 계산되고, 그 결과로 인덱스 범위를 찾는다.

#### 4) 표현식 인덱스는 안 된다

```sql
CREATE INDEX lab_contract_bad_idx ON lab_contract (TO_CHAR(reg_dt, 'YYYYMMDD'));
-- 기대: ERROR:  functions in index expression must be marked IMMUTABLE
```

`TO_CHAR`는 세션 설정(시간대·로케일)에 따라 결과가 달라질 수 있어서 막힌다. 꼭 필요하면 `reg_dt::date`(timestamp → date 캐스트는 IMMUTABLE)로 인덱스를 만든다.

```sql
CREATE INDEX lab_contract_reg_date_idx ON lab_contract ((reg_dt::date));
ANALYZE lab_contract;
EXPLAIN SELECT * FROM lab_contract WHERE reg_dt::date = DATE '2024-01-15';
-- 기대: Index Cond: ((reg_dt)::date = '2024-01-15'::date)
```

> `timestamptz` 컬럼이라면 `::date`도 시간대에 따라 달라지므로 IMMUTABLE이 아니다. 범위 비교가 정답이다.

```
실행 시간: ___ ms (TO_CHAR) → ___ ms (범위)
```

---

### 시나리오 3 — 결과값 함정 맞히기

실행 전에 결과를 예상해 적고, 실행해서 비교한다. 성능이 아니라 **값**이 틀리는 함정이다.

```sql
SELECT
  pg_catalog.substr('20240115', 0, 6)   AS s0,      -- 예상: ______
  pg_catalog.substr('20240115', 1, 6)   AS s1,      -- 예상: ______
  pg_catalog.substr('20240115', -2)     AS sneg,    -- 예상: ______  (Oracle이면 '15')
  RIGHT('20240115', 2)                  AS r2,      -- 예상: ______
  substr('abc', 5, 2) IS NULL           AS is_null; -- 예상: ______  (Oracle이면 true)
```

<details>
<summary>기대 결과</summary>

| 컬럼 | PG 기본 | Oracle |
|---|---|---|
| `s0` | `20240` (5자) | `202401` |
| `s1` | `202401` | `202401` |
| `sneg` | `20240115` (음수 위치부터 끝까지 → 전체) | `15` |
| `r2` | `15` | (Oracle에 RIGHT 없음) |
| `is_null` | `false` (빈 문자열 `''`) | `true` |

</details>

```sql
SELECT
  TO_CHAR(TIMESTAMP '2024-11-05 14:07:09', 'HH24:MI:SS') AS ok,     -- 14:07:09
  TO_CHAR(TIMESTAMP '2024-11-05 14:07:09', 'HH24:MM:SS') AS bad_mm, -- 예상: ______
  TO_CHAR(TIMESTAMP '2024-11-05 14:07:09', 'HHMISS')     AS bad_hh, -- 예상: ______
  TO_CHAR(0.5,   'FM999.99')     AS f1,                             -- 예상: ______
  TO_CHAR(0.5,   'FM999,990.00') AS f2,                             -- 예상: ______
  TO_CHAR(12345, 'FM999')        AS f3;                             -- 예상: ______
```

<details>
<summary>기대 결과</summary>

`bad_mm` = `14:11:09` (분 자리에 11월) · `bad_hh` = `020709` (오후 정보 사라짐) · `f1` = `.5` · `f2` = `0.50` · `f3` = `###`

</details>

**orafce가 설치된 프로젝트 DB에서** (실제 접속 계정으로):

```sql
SHOW search_path;
SELECT substr('20240115', 0, 6)          AS 기본경로,    -- 6자면 oracle.substr이 잡히는 중
       pg_catalog.substr('20240115', 0, 6) AS pg기본;
SHOW TimeZone;
SELECT NOW(), SYSDATE, TO_CHAR(NOW(),'YYYYMMDD') AS now_ymd, TO_CHAR(SYSDATE,'YYYYMMDD') AS sysdate_ymd;
```

배치 계정·WAS 계정 둘 다 돌려 보고 결과가 같은지 확인한다.

---

## 4. 애플리케이션 연동

**목표**: SQL에서 `SUBSTR`/`TO_CHAR`를 빼고, 범위 끝값을 Java에서 계산해 넘긴다.

### MyBatis 3 매퍼 (신규 코드 기준)

```xml
<!-- LabContractMapper.xml -->
<mapper namespace="com.example.lab.LabContractMapper">
  <!-- Before: WHERE SUBSTR(cont_ymd, 1, 6) = #{ym} -->
  <select id="sumByMonth" resultType="java.math.BigDecimal">
    SELECT COALESCE(SUM(amt), 0)
    FROM lab_contract
    WHERE cont_ymd &gt;= #{fromYmd}
      AND cont_ymd &lt;  #{toYmd}
  </select>
</mapper>
```

### iBatis 2 (이 프로젝트 형식)

```xml
<select id="LabContract.sumByMonth" parameterClass="hmap" resultClass="java.math.BigDecimal">
  <![CDATA[
    SELECT COALESCE(SUM(amt), 0)
    FROM lab_contract
    WHERE cont_ymd >= #fromYmd#
      AND cont_ymd <  #toYmd#
  ]]>
</select>
```

### Java (Java 8+ 호환)

```java
package com.example.lab;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Service;

@Mapper
interface LabContractMapper {
    BigDecimal sumByMonth(@Param("fromYmd") String fromYmd, @Param("toYmd") String toYmd);
}

@Service
public class LabContractService {

    // SQL의 'YYYYMMDD'와 같은 의미. Java는 MM=월, mm=분 — SQL과 반대라서 혼동 주의
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LabContractMapper mapper;

    public LabContractService(LabContractMapper mapper) {
        this.mapper = mapper;
    }

    /** @param ym "202401" 형태 */
    public BigDecimal sumByMonth(String ym) {
        YearMonth month = YearMonth.parse(ym, DateTimeFormatter.ofPattern("yyyyMM"));
        String from = month.atDay(1).format(YMD);              // 20240101
        String to   = month.plusMonths(1).atDay(1).format(YMD); // 20240201 (반개구간 끝)
        return mapper.sumByMonth(from, to);
    }
}
```

포인트:
- 끝값을 "다음 달 1일 **미만**"으로 잡으면 월말 날짜(28~31일)를 계산할 필요가 없다.
- 파라미터를 `String`으로 넘기므로 `#ym#::varchar` 같은 캐스트가 필요 없다.
- 날짜 포맷 문자의 대소문자 규칙이 **Java와 SQL이 반대**다: Java `mm`=분 / SQL `MM`·`mm`=월, SQL 분은 `MI`.

---

## 5. 정리

```sql
-- 실습 객체만 삭제. 운영 DB에서 이름을 확인하지 않고 실행 금지
DROP INDEX IF EXISTS lab_contract_ym_idx;
DROP INDEX IF EXISTS lab_contract_reg_date_idx;
DROP TABLE IF EXISTS lab_contract;   -- 남은 인덱스도 함께 삭제됨
```
