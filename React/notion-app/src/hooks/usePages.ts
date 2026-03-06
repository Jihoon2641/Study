import { useReducer, useEffect, useCallback } from "react";
import { pageReducer, initialPageState } from "../store/pageReducer";
import { useLocalStorage } from "./useLocalStorage";
import type { PageState } from "../types/Index";

/**
 * usePages
 *
 * 다중 페이지 상태를 관리하는 Custom Hook입니다.
 * useReducer로 상태 변화 로직을 처리하고,
 * useLocalStorage로 브라우저에 영구 저장합니다.
 *
 * 컴포넌트는 이 Hook만 사용하면 되고,
 * reducer 로직이나 localStorage는 신경 쓸 필요 없습니다.
 */
export function usePages() {

    // localStorage에서 초기 상태 불러오기, 저장된 값이 있으면 사용, 없으면 initialPageState 사용
    const [savedState, setSavedState] = useLocalStorage<PageState>(
        "notion-pages",
        initialPageState
    )

    // useReducer: 모든 상태 변화는 dispatch -? reducer를 통해서만
    const [state, dispatch] = useReducer(pageReducer, savedState);

    // state가 바뀔 때마다 localStorage에 저장
    useEffect(() => {
        setSavedState(state);
    }, [state, setSavedState]);

    // 현재 페이지 객체 (편의를 위해 미리 계산)
    const currentPage = state.pages.find((p) => p.id === state.currentPageId) ?? null;

    // ─── dispatch를 감싼 편의 함수들 ─────────────────────────
    // 컴포넌트에서 dispatch를 직접 쓰지 않고 이 함수들을 사용하면
    // action 타입을 외울 필요 없고, 자동완성도 잘 됩니다.

    const addPage = useCallback(() => {
        dispatch({ type: "ADD_PAGE" });
    }, []);

    const deletePage = useCallback((id: string) => {
        dispatch({ type: "DELETE_PAGE", payload: { id } });
    }, []);

    const updatePage = useCallback((id: string, updates: { title?: string; content?: string; emoji?: string; }) => {
        dispatch({ type: "UPDATE_PAGE", payload: { id, ...updates } });
    }, []);

    const setCurrentPage = useCallback((id: string) => {
        dispatch({ type: "SET_CURRENT_PAGE", payload: { id } });
    }, []);

    return {
        pages: state.pages,
        currentPageId: state.currentPageId,
        currentPage,
        addPage,
        deletePage,
        updatePage,
        setCurrentPage,
    };

}