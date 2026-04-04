"""
대용량 CSV를 MySQL로 마이그레이션할 때, CRUD/검색에 필요한 핵심 컬럼만 적재하는 스크립트.

- 원본 파일은 유지하고, `users/posts/comments` 테이블에 핵심 컬럼만 적재
- 대용량 적재 성능을 위해 `LOAD DATA LOCAL INFILE` 사용
- CSV 헤더 검증으로 잘못된 파일(예: comments 파일명인데 users 헤더)을 자동 스킵
- FK 제약이 있는 스키마에서도 적재되도록 실행 중 FOREIGN_KEY_CHECKS를 제어
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

import mysql.connector
from mysql.connector import errorcode
from mysql.connector.constants import ClientFlag


BASE_DIR = Path(__file__).resolve().parent

DB_CONFIG = {
    "host": "localhost",
    "port": 3307,
    "user": "user1234",
    "password": "board1234",
    "database": "board",
    "allow_local_infile": True,
    "client_flags": [ClientFlag.LOCAL_FILES],
}


@dataclass(frozen=True)
class DatasetConfig:
    name: str
    target_table: str
    files: list[str]
    expected_header: list[str]
    load_sql: str


def _parse_datetime_expr(var_name: str) -> str:
    # "2022-06-30 08:21:14.893 UTC" 형태를 DATETIME(3)로 파싱
    cleaned = f"NULLIF(TRIM(TRAILING '\\r' FROM {var_name}), '')"
    return (
        f"IF({cleaned} IS NULL, NULL, "
        f"STR_TO_DATE(REPLACE({cleaned}, ' UTC', ''), '%Y-%m-%d %H:%i:%s.%f'))"
    )


COMMENTS_LOAD_SQL = f"""
LOAD DATA LOCAL INFILE '{{csv_path}}'
INTO TABLE comments
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\\n'
IGNORE 1 ROWS
(@id, @text, @creation_date, @post_id, @user_id, @user_display_name, @score)
SET
    id = NULLIF(TRIM(TRAILING '\\r' FROM @id), ''),
    text = NULLIF(TRIM(TRAILING '\\r' FROM @text), ''),
    creation_date = {_parse_datetime_expr('@creation_date')},
    post_id = NULLIF(TRIM(TRAILING '\\r' FROM @post_id), ''),
    user_id = NULLIF(TRIM(TRAILING '\\r' FROM @user_id), ''),
    user_display_name = NULLIF(TRIM(TRAILING '\\r' FROM @user_display_name), ''),
    score = NULLIF(TRIM(TRAILING '\\r' FROM @score), '');
""".strip()


POSTS_LOAD_SQL = f"""
LOAD DATA LOCAL INFILE '{{csv_path}}'
INTO TABLE posts
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\\n'
IGNORE 1 ROWS
(
    @id, @title, @body, @accepted_answer_id, @answer_count,
    @comment_count, @community_owned_date, @creation_date, @favorite_count,
    @last_activity_date, @last_edit_date, @last_editor_display_name,
    @last_editor_user_id, @owner_display_name, @owner_user_id, @parent_id,
    @post_type_id, @score, @tags, @view_count
)
SET
    id = NULLIF(TRIM(TRAILING '\\r' FROM @id), ''),
    title = NULLIF(TRIM(TRAILING '\\r' FROM @title), ''),
    body = NULLIF(TRIM(TRAILING '\\r' FROM @body), ''),
    accepted_answer_id = NULLIF(TRIM(TRAILING '\\r' FROM @accepted_answer_id), ''),
    answer_count = NULLIF(TRIM(TRAILING '\\r' FROM @answer_count), ''),
    comment_count = NULLIF(TRIM(TRAILING '\\r' FROM @comment_count), ''),
    creation_date = {_parse_datetime_expr('@creation_date')},
    last_activity_date = {_parse_datetime_expr('@last_activity_date')},
    owner_user_id = NULLIF(TRIM(TRAILING '\\r' FROM @owner_user_id), ''),
    parent_id = NULLIF(TRIM(TRAILING '\\r' FROM @parent_id), ''),
    post_type_id = NULLIF(TRIM(TRAILING '\\r' FROM @post_type_id), ''),
    score = NULLIF(TRIM(TRAILING '\\r' FROM @score), ''),
    tags = NULLIF(TRIM(TRAILING '\\r' FROM @tags), ''),
    view_count = NULLIF(TRIM(TRAILING '\\r' FROM @view_count), '');
""".strip()


USERS_LOAD_SQL = f"""
LOAD DATA LOCAL INFILE '{{csv_path}}'
INTO TABLE users
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ','
ENCLOSED BY '"'
LINES TERMINATED BY '\\n'
IGNORE 1 ROWS
(
    @id, @display_name, @about_me, @age, @creation_date, @last_access_date,
    @location, @reputation, @up_votes, @down_votes, @views, @profile_image_url, @website_url
)
SET
    id = NULLIF(TRIM(TRAILING '\\r' FROM @id), ''),
    display_name = NULLIF(TRIM(TRAILING '\\r' FROM @display_name), ''),
    creation_date = {_parse_datetime_expr('@creation_date')},
    last_access_date = {_parse_datetime_expr('@last_access_date')},
    reputation = NULLIF(TRIM(TRAILING '\\r' FROM @reputation), '');
""".strip()


DATASETS = [
    DatasetConfig(
        name="users",
        target_table="users",
        files=["bq_users_1.csv", "bq_users_2.csv"],
        expected_header=[
            "id",
            "display_name",
            "about_me",
            "age",
            "creation_date",
            "last_access_date",
            "location",
            "reputation",
            "up_votes",
            "down_votes",
            "views",
            "profile_image_url",
            "website_url",
        ],
        load_sql=USERS_LOAD_SQL,
    ),
    DatasetConfig(
        name="posts",
        target_table="posts",
        files=["bq_posts_questions_1.csv", "bq_posts_questions_2.csv"],
        expected_header=[
            "id",
            "title",
            "body",
            "accepted_answer_id",
            "answer_count",
            "comment_count",
            "community_owned_date",
            "creation_date",
            "favorite_count",
            "last_activity_date",
            "last_edit_date",
            "last_editor_display_name",
            "last_editor_user_id",
            "owner_display_name",
            "owner_user_id",
            "parent_id",
            "post_type_id",
            "score",
            "tags",
            "view_count",
        ],
        load_sql=POSTS_LOAD_SQL,
    ),
    DatasetConfig(
        name="comments",
        target_table="comments",
        files=["bq_comments_1.csv"],
        expected_header=[
            "id",
            "text",
            "creation_date",
            "post_id",
            "user_id",
            "user_display_name",
            "score",
        ],
        load_sql=COMMENTS_LOAD_SQL,
    ),
]


CREATE_TABLE_SQL = [
    """
    CREATE TABLE IF NOT EXISTS users (
        id BIGINT NOT NULL,
        display_name VARCHAR(128) NULL,
        creation_date DATETIME(3) NULL,
        last_access_date DATETIME(3) NULL,
        reputation INT NULL,
        PRIMARY KEY (id),
        KEY idx_users_reputation (reputation),
        KEY idx_users_last_access (last_access_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    """.strip(),
    """
    CREATE TABLE IF NOT EXISTS posts (
        id BIGINT NOT NULL,
        title VARCHAR(255) NOT NULL,
        body LONGTEXT NOT NULL,
        accepted_answer_id BIGINT NULL,
        answer_count INT NULL,
        comment_count INT NULL,
        creation_date DATETIME(3) NULL,
        last_activity_date DATETIME(3) NULL,
        owner_user_id BIGINT NULL,
        parent_id BIGINT NULL,
        post_type_id TINYINT NULL,
        score INT NULL,
        tags VARCHAR(512) NULL,
        view_count INT NULL,
        PRIMARY KEY (id),
        KEY idx_posts_owner_created (owner_user_id, creation_date),
        KEY idx_posts_type_created (post_type_id, creation_date),
        KEY idx_posts_parent (parent_id),
        KEY idx_posts_score (score),
        FULLTEXT KEY ft_posts_title_body (title, body)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    """.strip(),
    """
    CREATE TABLE IF NOT EXISTS comments (
        id BIGINT NOT NULL,
        text TEXT NOT NULL,
        creation_date DATETIME(3) NULL,
        post_id BIGINT NOT NULL,
        user_id BIGINT NULL,
        user_display_name VARCHAR(255) NULL,
        score INT NULL,
        PRIMARY KEY (id),
        KEY idx_comments_post_created (post_id, creation_date),
        KEY idx_comments_user (user_id),
        KEY idx_comments_score (score)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    """.strip(),
]

INDEX_DDL = [
    "ALTER TABLE users ADD INDEX idx_users_reputation (reputation)",
    "ALTER TABLE users ADD INDEX idx_users_last_access (last_access_date)",
    "ALTER TABLE posts ADD INDEX idx_posts_owner_created (owner_user_id, creation_date)",
    "ALTER TABLE posts ADD INDEX idx_posts_type_created (post_type_id, creation_date)",
    "ALTER TABLE posts ADD INDEX idx_posts_parent (parent_id)",
    "ALTER TABLE posts ADD INDEX idx_posts_score (score)",
    "ALTER TABLE posts ADD FULLTEXT INDEX ft_posts_title_body (title, body)",
    "ALTER TABLE comments ADD INDEX idx_comments_post_created (post_id, creation_date)",
    "ALTER TABLE comments ADD INDEX idx_comments_user (user_id)",
    "ALTER TABLE comments ADD INDEX idx_comments_score (score)",
]


def read_header(csv_path: Path) -> list[str]:
    with csv_path.open("r", encoding="utf-8", newline="") as f:
        return next(csv.reader(f))


def validate_header(csv_path: Path, expected_header: list[str]) -> bool:
    actual = read_header(csv_path)
    if actual == expected_header:
        return True

    print(f"[SKIP] 헤더 불일치: {csv_path.name}")
    print(f"       actual  : {actual}")
    print(f"       expected: {expected_header}")
    return False


def escape_for_sql(path: Path) -> str:
    # LOAD DATA 경로용 escape
    return str(path).replace("\\", "\\\\").replace("'", "\\'")


def create_tables(cur) -> None:
    for ddl in CREATE_TABLE_SQL:
        cur.execute(ddl)


def ensure_indexes(cur) -> None:
    for ddl in INDEX_DDL:
        try:
            cur.execute(ddl)
        except mysql.connector.Error as err:
            # 1061: duplicate key name (이미 같은 이름 인덱스 존재)
            if err.errno == errorcode.ER_DUP_KEYNAME:
                continue
            raise


def truncate_tables(cur) -> None:
    # 재실행 시 중복 적재를 피하고 싶을 때 사용
    cur.execute("TRUNCATE TABLE comments")
    cur.execute("TRUNCATE TABLE posts")
    cur.execute("TRUNCATE TABLE users")


def set_foreign_key_checks(cur, enabled: bool) -> None:
    value = 1 if enabled else 0
    cur.execute(f"SET FOREIGN_KEY_CHECKS = {value}")


def assert_local_infile_enabled(cur) -> None:
    cur.execute("SHOW VARIABLES LIKE 'local_infile'")
    row = cur.fetchone()
    if not row:
        raise RuntimeError("MySQL 변수(local_infile)를 조회할 수 없습니다.")

    # row 예시: ('local_infile', 'ON')
    status = str(row[1]).strip().lower()
    if status not in {"on", "1"}:
        raise RuntimeError(
            "MySQL 서버의 local_infile이 OFF입니다. "
            "docker-compose 설정(--local_infile=1) 반영 후 컨테이너를 재시작하세요."
        )


def load_dataset(cur, dataset: DatasetConfig) -> None:
    print(f"\n=== [{dataset.name}] -> {dataset.target_table} ===")
    for file_name in dataset.files:
        csv_path = BASE_DIR / file_name
        if not csv_path.exists():
            print(f"[WARN] 파일 없음: {file_name}")
            continue
        if not validate_header(csv_path, dataset.expected_header):
            continue

        query = dataset.load_sql.format(csv_path=escape_for_sql(csv_path))
        try:
            cur.execute(query)
        except mysql.connector.Error as err:
            if err.errno == 3948:
                raise RuntimeError(
                    "LOAD DATA LOCAL INFILE 비활성화 오류(3948). "
                    "클라이언트/서버 양쪽 local_infile 설정을 확인하세요."
                ) from err
            raise
        print(f"[OK] {file_name} 적재 완료 (rowcount={cur.rowcount})")


def main() -> None:
    conn = mysql.connector.connect(**DB_CONFIG)
    cur = conn.cursor()
    fk_checks_disabled = False
    try:
        create_tables(cur)
        ensure_indexes(cur)
        assert_local_infile_enabled(cur)
        set_foreign_key_checks(cur, enabled=False)
        fk_checks_disabled = True
        truncate_tables(cur)
        for dataset in DATASETS:
            load_dataset(cur, dataset)
        set_foreign_key_checks(cur, enabled=True)
        fk_checks_disabled = False
        conn.commit()
        print("\n[ALL DONE] 마이그레이션 완료")
    except Exception:
        if fk_checks_disabled:
            try:
                set_foreign_key_checks(cur, enabled=True)
            except Exception:
                pass
        conn.rollback()
        raise
    finally:
        cur.close()
        conn.close()


if __name__ == "__main__":
    main()
