import type { Page, PageAction, PageState } from "../types/Index";

const EMOJIS = ["📄", "📝", "📌", "💡", "🗒️", "📋", "🔖", "✏️"];

function createPage(): Page {
    const now = Date.now();

    return {
        id: crypto.randomUUID(),
        title: "",
        content: "",
        emoji: EMOJIS[Math.floor(Math.random() * EMOJIS.length)],
        createdAt: now,
        updatedAt: now,
    };
}

// 초기 상태

const firstPage = createPage();

export const initialPageState: PageState = {
    pages: [firstPage],
    currentPageId: firstPage.id,
};

// ─────────────────────────────────────────────────────────
// Reducer
//
// Reducer는 순수 함수(Pure Function)여야 합니다.
// 순수 함수란: 같은 입력 → 항상 같은 출력, 외부 상태를 변경하지 않음
// 즉, 절대로 state를 직접 수정하면 안 되고 항상 새 객체를 반환해야 합니다.
// ─────────────────────────────────────────────────────────

export function pageReducer(state: PageState, action: PageAction): PageState {
    switch (action.type) {
        case "ADD_PAGE": {
            const newPage = createPage();

            return {
                // 기존 pages 배열은 건드리지 않고 새 배열 생성
                pages: [...state.pages, newPage],
                // 새로 만든 페이지를 바로 현재 페이지로 설정
                currentPageId: newPage.id,
            };
        }

        case "DELETE_PAGE": {
            const { id } = action.payload;
            const remainingPages = state.pages.filter((p) => p.id !== id);

            // 마지막 페이지는 삭제 불가 (항상 최소 1개 페이지 유지)
            if (remainingPages.length === 0) return state;

            // 삭제 된 페이지가 현재 페이지였다면, 다른 페이지 이동
            let nextCurrentId = state.currentPageId;
            if (state.currentPageId === id) {
                const deleteIndex = state.pages.findIndex((p) => p.id === id);
                nextCurrentId = remainingPages[deleteIndex - 1]?.id ?? remainingPages[0].id;
            }

            return {
                pages: remainingPages,
                currentPageId: nextCurrentId,
            };
        }

        case "UPDATE_PAGE": {
            const { id, ...updates } = action.payload;
            return {
                // ...state : state 객체의 다른 속성 유지
                ...state,
                pages: state.pages.map((p) =>
                    p.id === id
                        // ...p : 페이지 객체의 다른 속성 유지
                        // ...updates : 업데이트된 속성 추가
                        // 결국 {} 는 하나의 Page 객체
                        ? { ...p, ...updates, updatedAt: Date.now() } : p
                ),
            };
        }

        case "SET_CURRENT_PAGE": {
            return {
                ...state,
                currentPageId: action.payload.id,
            };
        }

        default:
            return state;
    }
}