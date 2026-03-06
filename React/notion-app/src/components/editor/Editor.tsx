import { useState, useEffect, useRef, useCallback } from "react";
import { TitleInput } from "./TitleInput";
import { SaveStatus } from "./Savestatus";
import { useLocalStorage } from "../../hooks/useLocalStorage";
import { useDebounce } from "../../hooks/useDebounce";
import type { Page } from "../../types/Index";

// 새 페이지 초기값을 만드는 헬퍼 함수
function createEmptyPage(): Page {
    const now = Date.now();
    return {
        id: crypto.randomUUID(),
        title: "",
        content: "",
        createdAt: now,
        updatedAt: now,
    };
}

/**
 * useState -> 저장 상태(saveStatus) 관리
 * useRef -> 본문 textarea에 포커스 이동
 * useEffect -> 디바운스 저장 / 저장 상태 표시 / 탭 제목 업데이트
 * useLocalStorage (Custom Hook) -> 새로고침해도 내용 유지
 * useDebounce (Custom Hook) -> 타이핑 멈훈 후에만 저장 트리거
 */
export function Editor() {
    // useLocalStorage: 페이지 데이터를 브라우저에 영구 저장
    // 새로고침해도 작성한 내용 유지
    const [page, setPage] = useLocalStorage<Page>("notion-page", createEmptyPage());

    // useState: 저장 상태는 UI 전용 -> localStorage 불필요
    const [saveStauts, setSaveStatus] = useState<"idle" | "saving" | "saved">("idle");

    // useRef: 본문 영역 DOM에 직접 접근 (포커스 이동용)
    const contentRef = useRef<HTMLTextAreaElement>(null);

    // useDebounce: title/content가 바뀌고 800ms 후에만 debouncedPage가 업데이트
    // 타이핑 중 저장 트리거 안됨.
    const debouncedPage = useDebounce(page, 800);

    // 핸들러
    // 제목 변경 : page 객체의 title만 업데이트
    const handleTitleChange = useCallback((title: string) => {
        setPage((prev) => ({ ...prev, title, updatedAt: Date.now() }));
    }, [setPage]);

    // 본문 변경
    const handleContentChange = useCallback((content: string) => {
        setPage((prev) => ({ ...prev, content, updatedAt: Date.now() }));
    }, [setPage]);

    // 본문 영역으로 포커스 이동
    const handleTitleEnter = useCallback(() => {
        contentRef.current?.focus();
    }, []);

    // useEffect
    // debouncedPage가 바뀔 때 저장 중 -> 저장됨 표시
    // debouncedPage는 타이핑 멈춘 후에만 바뀌므로 타이핑 중에는 이 effect가 실행되지 않음
    useEffect(() => {
        // 앱 첫 로드 시엔 저장 표시 안함
        if (!page.title && !page.content) return;

        setSaveStatus("saving");

        const timer = setTimeout(() => {
            setSaveStatus("saved");
        }, 400);

        return () => clearTimeout(timer);
    }, [debouncedPage]);

    // 브라우저 탭 제목을 페이지 제목과 동기화
    useEffect(() => {
        document.title = page.title || "제목 없음 - Notion";
    }, [page.title]);

    // 렌더링

    const wordCount = page.content.trim()
        ? page.content.trim().split(/\s+/).length : 0;

    return (
        <div className="min-h-scrern bg-white">
            {/* 상단 툴바 */}
            <div className="fixed top-0 left-0 right-0 h-12 flex items-center justify-between px-6 bg-white/80 backdrop-blur-sm border-b border-gray-100 z-10">
                <span className="text-sm text-gray-400 truncate max-w-xs">
                    {page.title || "제목 없음"}
                </span>
                <SaveStatus status={saveStauts} updatedAt={page.updatedAt} />
            </div>

            {/* 에디터 본문 */}
            <div className="max-w-3xl mx-auto px-16 pt-28 pb-40">
                {/* 이모지 영역 (Notion 스타일) */}
                <div className="text-6xl mb-6 select-none">📄</div>

                {/* 제목 */}
                <TitleInput
                    value={page.title}
                    onChange={handleTitleChange}
                    onEnter={handleTitleEnter}
                />

                {/* 구분선 */}
                <div className="mt-4 mb-6 border-t border-gray-100" />

                {/* 본문 */}
                <textarea
                    ref={contentRef}
                    value={page.content}
                    onChange={(e) => {
                        handleContentChange(e.target.value);
                        // 높이 자동 조절
                        e.target.style.height = "auto";
                        e.target.style.height = `${e.target.scrollHeight}px`;
                    }}
                    placeholder="내용을 입력하세요..."
                    rows={1}
                    className="
                        w-full bg-transparent border-none outline-none resize-none
                        text-[16px] leading-8 text-gray-800
                        placeholder:text-gray-300 min-h-[60vh]
                    "
                />

                {/* 하단 메타 정보 */}
                <div className="mt-8 pt-4 border-t border-gray-100 flex items-center justify-between text-xs text-gray-400">
                    <span>
                        마지막 수정:{" "}
                        {new Date(page.updatedAt).toLocaleDateString("ko-KR", {
                            year: "numeric", month: "long", day: "numeric",
                            hour: "2-digit", minute: "2-digit",
                        })}
                    </span>
                    <span>{wordCount > 0 ? `${wordCount}단어` : ""}</span>
                </div>
            </div>
        </div>
    )

}