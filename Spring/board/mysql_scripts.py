import csv
import ast
import mysql.connector

# DB 연결 설정 (docker-compose.yaml 기준)
conn = mysql.connector.connect(
    host="localhost",
    port=3307,
    database="board",
    user="user1234",
    password="board1234"
)
cursor = conn.cursor(prepared=True)

CSV_PATH = "/Users/jihoon/Documents/Study/Spring/medium_articles.csv"

with open(CSV_PATH, encoding="utf-8") as f:
    reader = csv.DictReader(f)

    for i, row in enumerate(reader, start=1):
        title     = row["title"][:500]       # length = 500 제한
        text      = row["text"]              # LONGTEXT
        url       = row["url"]               # LONGTEXT
        authors   = row["authors"]
        timestamp = row["timestamp"]

        # tags 파싱: "['tag1', 'tag2']" → ['tag1', 'tag2']
        try:
            tags = ast.literal_eval(row["tags"])
        except Exception:
            tags = []

        # 1. article 삽입
        article_sql = """
            INSERT INTO article (title, article_text, url, authors, article_timestamp)
            VALUES (%s, %s, %s, %s, %s)
        """
        cursor.execute(article_sql, (title, text, url, authors, timestamp))
        article_id = cursor.lastrowid  # 방금 삽입된 article의 PK

        # 2. article_tag 삽입
        tag_sql = """
            INSERT INTO article_tag (tag_name, article_id)
            VALUES (%s, %s)
        """
        for tag in tags:
            cursor.execute(tag_sql, (tag.strip(), article_id))

        if i % 1000 == 0:
            print(f"{i}개 삽입 완료...")
            conn.commit()

conn.commit()
cursor.close()
conn.close()
print("삽입 완료!")
