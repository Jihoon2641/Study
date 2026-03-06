import { useState, useRef, useEffect, useCallback } from "react";
import type { Page } from "../../types/Index";

type SidebarItemProps = {
    page: Page;
    isActive: boolean;
    onClick: () => void;
    onDelete: () => void;
    onRename: (title: string) => void;
}

/**
 * SidebarItem
 *
 * 사이드바의 개별 페이지 항목입니다.
 * - 클릭: 해당 페이지로 이동
 * - 더블클릭: 이름 인라인 편집
 * - 호버 시 삭제 버튼 표시
 */
export function SidebarItem({
    page,
    isActive,
    onClick,
    onDelete,
    onRename,
}: SidebarItemProps) {

    // 이름 편집 모드 여부
    const [isEditing, setIsEditing] = useState(false);

    // 편집 중인 임시 제목값 (확정 전까지 원본과 분리)
    const [editValue, setEditValue] = useState(page.title);
    const inputRef = useRef<HTMLInputElement>(null);

    // 편집 모드 진입 시 input에 자동 포커스 + 전체 선택
    useEffect(() => {
        if (isEditing) {
            inputRef.current?.focus();
            inputRef.current?.select();
        }
    }, [isEditing]);

    // 편집 확정 (Enter 또는 blur)
    const handleRenameConfirm = useCallback(() => {
        setIsEditing(false);

        const trimmed = editValue.trim();
        // 빈 값이면 원래 제목으로 복원
        if (trimmed && trimmed !== page.title) {
            onRename(trimmed);
        } else {
            setEditValue(page.title);
        }
    }, [editValue, page.title, onRename]);

    // 편집 취소 (Escape)
    const handleRenameCancel = useCallback(() => {
        setIsEditing(false);
        setEditValue(page.title);
    }, [page.title]);

    const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
        if (e.key === "Enter") handleRenameConfirm();
        if (e.key === "Escape") handleRenameCancel();
    }, [handleRenameConfirm, handleRenameCancel]);

    // 삭제 버튼 클릭 - 이벤트 버블링 차단 (부모 onClick 방지)
    const handleDelete = useCallback((e: React.MouseEvent) => {
        e.stopPropagation();
        onDelete();
    }, [onDelete]);

    return (
        <div
            onClick={onClick}
            onDoubleClick={() => setIsEditing(true)}
            className={`
        group flex items-center gap-2 px-3 py-1.5 rounded-md cursor-pointer
        transition-colors text-sm select-none
        ${isActive
                    ? "bg-gray-200 text-gray-900"
                    : "text-gray-600 hover:bg-gray-100"
                }
      `}
        >
            {/* 이모지 아이콘 */}
            <span className="text-base flex-shrink-0">{page.emoji}</span>

            {/* 제목 or 인라인 편집 input */}
            {isEditing ? (
                <input
                    ref={inputRef}
                    value={editValue}
                    onChange={(e) => setEditValue(e.target.value)}
                    onBlur={handleRenameConfirm}
                    onKeyDown={handleKeyDown}
                    onClick={(e) => e.stopPropagation()} // 편집 중 클릭이 페이지 이동으로 처리되지 않도록
                    className="flex-1 bg-white border border-blue-400 rounded px-1 outline-none text-sm text-gray-900"
                />
            ) : (
                <span className="flex-1 truncate">
                    {page.title || "제목 없음"}
                </span>
            )}

            {/* 삭제 버튼 — hover 시에만 표시 */}
            {!isEditing && (
                <button
                    onClick={handleDelete}
                    className="
            opacity-0 group-hover:opacity-100
            text-gray-400 hover:text-red-400
            transition-opacity text-xs px-1
          "
                    title="페이지 삭제"
                >
                    ✕
                </button>
            )}
        </div>
    );

}
