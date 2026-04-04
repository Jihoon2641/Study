from pathlib import Path

import pandas as pd

BASE_DIR = Path(__file__).resolve().parent
CHUNK_SIZE = 100_000

# 같은 도메인 CSV(1,2)를 합쳐서 컬럼별 최대 길이를 계산
DATASETS = {
    "comments": ["bq_comments_1.csv", "bq_comments_2.csv"],
    "posts": ["bq_posts_questions_1.csv", "bq_posts_questions_2.csv"],
    "users": ["bq_users_1.csv", "bq_users_2.csv"],
}

EXPECTED_COLUMNS = {
    "comments": {"id", "text", "creation_date", "post_id", "user_id", "user_display_name", "score"},
    "posts": {
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
    },
    "users": {
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
    },
}


def _init_column_stats(columns: list[str]) -> dict[str, dict[str, object]]:
    return {
        col: {
            "max_length": 0,
            "source_file": "",
            "sample_value": "",
            "non_empty_count": 0,
        }
        for col in columns
    }


def calculate_max_lengths(table_name: str, file_names: list[str]) -> pd.DataFrame:
    stats: dict[str, dict[str, object]] | None = None
    print(f"\n=== [{table_name}] 시작 ===")

    for file_name in file_names:
        csv_path = BASE_DIR / file_name
        if not csv_path.exists():
            print(f"[WARN] 파일 없음: {csv_path}")
            continue

        header_df = pd.read_csv(csv_path, nrows=0, encoding="utf-8")
        current_columns = set(header_df.columns.tolist())
        expected = EXPECTED_COLUMNS[table_name]

        if current_columns != expected:
            print(f"[SKIP] {file_name} 컬럼이 {table_name} 스키마와 다릅니다.")
            print(f"       current={sorted(current_columns)}")
            print(f"       expected={sorted(expected)}")
            continue

        print(f"[READ] {file_name}")
        reader = pd.read_csv(
            csv_path,
            encoding="utf-8",
            dtype=str,
            chunksize=CHUNK_SIZE,
            keep_default_na=False,
            low_memory=False,
        )

        for chunk_index, chunk in enumerate(reader, start=1):
            if stats is None:
                stats = _init_column_stats(chunk.columns.tolist())

            for col in chunk.columns:
                if col not in stats:
                    stats[col] = {
                        "max_length": 0,
                        "source_file": "",
                        "sample_value": "",
                        "non_empty_count": 0,
                    }

                series = chunk[col].fillna("")
                lengths = series.str.len()

                current_max = int(lengths.max()) if not lengths.empty else 0
                prev_max = int(stats[col]["max_length"])

                if current_max > prev_max:
                    idx = lengths.idxmax()
                    sample = str(series.loc[idx]).replace("\n", "\\n").replace("\r", "\\r")
                    stats[col]["max_length"] = current_max
                    stats[col]["source_file"] = file_name
                    stats[col]["sample_value"] = sample[:120]

                stats[col]["non_empty_count"] = int(stats[col]["non_empty_count"]) + int((series != "").sum())

            if chunk_index % 10 == 0:
                print(f"  - {file_name}: {chunk_index} chunks 처리")

    if stats is None:
        return pd.DataFrame(columns=["table", "column", "max_length", "source_file", "non_empty_count", "sample_value"])

    report_rows = []
    for col, data in stats.items():
        report_rows.append(
            {
                "table": table_name,
                "column": col,
                "max_length": int(data["max_length"]),
                "source_file": data["source_file"],
                "non_empty_count": int(data["non_empty_count"]),
                "sample_value": data["sample_value"],
            }
        )

    report_df = pd.DataFrame(report_rows).sort_values(by="max_length", ascending=False).reset_index(drop=True)
    return report_df


def main() -> None:
    all_reports: list[pd.DataFrame] = []

    for table_name, file_names in DATASETS.items():
        report_df = calculate_max_lengths(table_name, file_names)
        all_reports.append(report_df)

        output_path = BASE_DIR / f"{table_name}_column_max_length_report.csv"
        report_df.to_csv(output_path, index=False, encoding="utf-8-sig")
        print(f"[DONE] {table_name} 리포트 저장: {output_path.name}")
        print(report_df[["column", "max_length", "source_file"]].to_string(index=False))

    combined_report = pd.concat(all_reports, ignore_index=True)
    combined_path = BASE_DIR / "all_column_max_length_report.csv"
    combined_report.to_csv(combined_path, index=False, encoding="utf-8-sig")
    print(f"\n[ALL DONE] 통합 리포트 저장: {combined_path.name}")


if __name__ == "__main__":
    main()
