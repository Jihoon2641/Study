export type Page = {
    id: string;
    title: string;
    content: string;
    emoji: string;
    createdAt: number; // Date.now()
    updatedAt: number; // Date.now()
}

export type PageState = {
    pages: Page[];
    currentPageId: string | null;
};

export type PageAction =
    | { type: "ADD_PAGE" }
    | { type: "DELETE_PAGE"; payload: { id: string } }
    | { type: "UPDATE_PAGE"; payload: { id: string; title?: string; content?: string; emoji?: string; } }
    | { type: "SET_CURRENT_PAGE"; payload: { id: string } };
