import React, { useState, useRef, useEffect, useCallback } from "react";
import { SaveStatus } from "./Savestatus";
import { useDebounce } from "../../hooks/useDebounce";
import type { Page } from "../../types/Index";

type EditorProps = {
    page: Page;
    onUpdate: (updates: { title?: string; content?: string; emoji?: string; }) => void;
}

const EMOJIS = ["📄", "📝", "📌", "💡", "🗒️", "📋", "🔖", "✏️", "🌟", "🎯"];

/**
 * Editor
 *
 * Week 1과 다른 점:
 * - 이제 자체적으로 상태를 들고 있지 않습니다.
 * - 상위(App)에서 현재 페이지(page)를 받아서 표시하고,
 *   변경이 생기면 onUpdate로 알립니다.
 * - 페이지가 바뀔 때(page.id 변경) 에디터를 초기화합니다.
 */

export function Editor({ page, onUpdate }: EditorProps) {
    const [saveStatus, setSaveStatus] = useState<"idle" | "saving" | "saved">("idle");
    const [showEmojiPicker, setShowEmojiPicker] = useState(false);
    const titleRef = useRef<HTMLInputElement>(null);
    const contentRef = useRef<HTMLTextAreaElement>(null);

    // 디바운스 : 타이핑 멈춘 후 800ms에만 저장 트리거
    const debouncedPage = useDebounce(page, 800);

    // ─── 페이지가 바뀌면 에디터 초기화 ─────────────────────
    // page.id가 바뀌었다 = 사용자가 다른 페이지를 선택했다
    // 이 경우 저장 상태를 리셋하고 제목으로 포커스 이동
    useEffect(() => {
        setSaveStatus("idle");
        titleRef.current?.focus();
    }, [page.id]);

    // 저장 상태 표시
    useEffect(() => {
        document.title = page.title ? `${page.title} — Notion` : "제목 없음 — Notion";
    }, [page.title]);

    // 핸들러
    const handleTitleChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
        onUpdate({ title: e.target.value });
    }, [onUpdate]);


    const handleContentChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
        onUpdate({ content: e.target.value });
        // textarea 높이 자동 조절
        e.target.style.height = "auto";
        e.target.style.height = `${e.target.scrollHeight}px`;
    }, [onUpdate]);

    const handleTitleKeyDown = useCallback((e: React.KeyboardEvent) => {
        if (e.key === "Enter") {
            e.preventDefault();
            contentRef.current?.focus();
        }
    }, []);

    const handleEmojiSelect = useCallback((emoji: string) => {
        onUpdate({ emoji });
        setShowEmojiPicker(false);
    }, [onUpdate]);

    const wordCount = page.content.trim()
        ? page.content.trim().split(/\s+/).length : 0;

    return (
        <div className="flex-1 flex flex-col min-h-screen bg-white overflow-y-auto">

            {/* 상단 툴바 */}
            <div className="sticky top-0 flex items-center justify-between px-6 h-12 bg-white/80 backdrop-blur-sm border-b border-gray-100 z-10">
                <span className="text-sm text-gray-400 truncate">
                    {page.emoji} {page.title || "제목 없음"}
                </span>
                <SaveStatus status={saveStatus} updatedAt={page.updatedAt} />
            </div>

            {/* 에디터 본문 */}
            <div className="max-w-3xl mx-auto w-full px-16 pt-16 pb-40">

                {/* 이모지 선택 */}
                <div className="relative mb-4">
                    <button
                        onClick={() => setShowEmojiPicker((v) => !v)}
                        className="text-5xl hover:opacity-70 transition-opacity"
                        title="이모지 변경"
                    >
                        {page.emoji}
                    </button>

                    {showEmojiPicker && (
                        <div className="absolute top-14 left-0 bg-white border border-gray-200 rounded-xl shadow-lg p-3 grid grid-cols-5 gap-1 z-20">
                            {EMOJIS.map((emoji) => (
                                <button
                                    key={emoji}
                                    onClick={() => handleEmojiSelect(emoji)}
                                    className="text-2xl p-1.5 rounded-lg hover:bg-gray-100 transition-colors"
                                >
                                    {emoji}
                                </button>
                            ))}
                        </div>
                    )}
                </div>

                {/* 제목 */}
                <input
                    ref={titleRef}
                    type="text"
                    value={page.title}
                    onChange={handleTitleChange}
                    onKeyDown={handleTitleKeyDown}
                    placeholder="제목 없음"
                    className="
            w-full bg-transparent border-none outline-none
            text-[40px] font-bold leading-tight tracking-tight
            text-gray-900 placeholder:text-gray-300
          "
                />

                <div className="mt-4 mb-6 border-t border-gray-100" />

                {/* 본문 */}
                <textarea
                    ref={contentRef}
                    value={page.content}
                    onChange={handleContentChange}
                    placeholder="내용을 입력하세요..."
                    rows={1}
                    className="
            w-full bg-transparent border-none outline-none resize-none
            text-[16px] leading-8 text-gray-800
            placeholder:text-gray-300 min-h-[60vh]
          "
                />

                {/* 하단 메타 */}
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
    );
}