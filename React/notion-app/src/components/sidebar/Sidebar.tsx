import { useCallback } from "react";
import type { Page } from "../../types/Index";
import { SidebarItem } from "./SidebarItem";

type SidebarProps = {
    pages: Page[];
    currentPageId: string | null;
    onSelectPage: (id: string) => void;
    onAddPage: () => void;
    onDeletePage: (id: string) => void;
    onRenamePage: (id: string, title: string) => void;
};

/**
 * Sidebar
 *
 * 왼쪽 사이드바 전체 컴포넌트입니다.
 * 페이지 목록 표시, 새 페이지 추가 버튼을 포함합니다.
 */
export function Sidebar({
    pages,
    currentPageId,
    onSelectPage,
    onAddPage,
    onDeletePage,
    onRenamePage,
}: SidebarProps) {

    const handleRename = useCallback(
        (id: string, title: string) => {
            onRenamePage(id, title);
        },
        [onRenamePage]
    );

    return (
        <aside className="w-60 h-screen flex flex-col bg-gray-50 border-r border-gray-200 flex-shrink-0">

            {/* 워크스페이스 헤더 */}
            <div className="flex items-center gap-2 px-4 py-3 border-b border-gray-200">
                <span className="text-lg">📋</span>
                <span className="text-sm font-semibold text-gray-800">내 워크스페이스</span>
            </div>

            {/* 페이지 목록 */}
            <nav className="flex-1 overflow-y-auto px-2 py-2 space-y-0.5">
                <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-wider px-2 mb-2">
                    페이지
                </p>
                {pages.map((page) => (
                    <SidebarItem
                        key={page.id}
                        page={page}
                        isActive={page.id === currentPageId}
                        onClick={() => onSelectPage(page.id)}
                        onDelete={() => onDeletePage(page.id)}
                        onRename={(title) => handleRename(page.id, title)}
                    />
                ))}
            </nav>

            {/* 새 페이지 버튼 */}
            <div className="p-2 border-t border-gray-200">
                <button
                    onClick={onAddPage}
                    className="
            w-full flex items-center gap-2 px-3 py-2 rounded-md
            text-sm text-gray-500 hover:bg-gray-200
            transition-colors
          "
                >
                    <span className="text-base">+</span>
                    <span>새 페이지</span>
                </button>
            </div>
        </aside>
    );
}