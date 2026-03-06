import { usePages } from "./hooks/usePages";
import { Sidebar } from "./components/sidebar/Sidebar";
import { Editor } from "./components/editor/Editor";

/**
 * App
 *
 * 전체 레이아웃을 조합합니다.
 * usePages Hook 하나로 모든 페이지 상태를 관리하고,
 * Sidebar와 Editor에 필요한 것만 내려줍니다.
 *
 * 상태 흐름:
 *   usePages (상태 + dispatch)
 *     ├→ Sidebar (pages 목록 표시, 선택/추가/삭제/이름변경)
 *     └→ Editor (currentPage 표시, 내용 업데이트)
 */
function App() {
  const {
    pages,
    currentPageId,
    currentPage,
    addPage,
    deletePage,
    updatePage,
    setCurrentPage,
  } = usePages();

  return (
    <div className="flex h-screen overflow-hidden">
      {/* 사이드바 */}
      <Sidebar
        pages={pages}
        currentPageId={currentPageId}
        onSelectPage={setCurrentPage}
        onAddPage={addPage}
        onDeletePage={deletePage}
        onRenamePage={(id, title) => updatePage(id, { title })}
      />

      {/* 에디터 */}
      {currentPage ? (
        <Editor
          page={currentPage}
          onUpdate={(updates) => updatePage(currentPage.id, updates)}
        />
      ) : (
        // 페이지가 없는 경우 (거의 발생하지 않음)
        <div className="flex-1 flex items-center justify-center text-gray-400">
          <div className="text-center">
            <p className="text-4xl mb-4">📄</p>
            <p className="text-sm">페이지를 선택하거나 새로 만들어보세요</p>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;