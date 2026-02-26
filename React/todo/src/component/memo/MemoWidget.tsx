import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useLocalStorage } from '../../hooks/useLocalStorage';

type SaveStatus = 'idle' | 'saving' | 'saved';

const MAX_LENGTH = 500;

export function MemoWidget() {
    // useLocalStorage: 새로고침해도 내용 유지
    const [content, setContent] = useLocalStorage<string>('memo-content', '');

    // useState: 저장 상태 표시
    const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle');

    // useRef: textarea DOM에 직접 접근 (자동 포커스용)
    const textareaRef = useRef<HTMLTextAreaElement>(null);

    // 마운트 시 textarea에 자동 포커스
    useEffect(() => {
        textareaRef.current?.focus();
    }, []);

    // 내용이 바뀔 때마다 '저장 중...' -> '저장됨' 표시
    // 디바운스 패턴 : 타이핑 멈춘 후 0.5초 뒤에 '저장됨'으로 변경
    useEffect(() => {
        if (!content) return;

        setSaveStatus('saving');
        const timer = setTimeout(() => setSaveStatus('saved'), 500);

        // 클린업 함수
        return () => clearTimeout(timer);
    }, [content]);

    // 입력 핸들러
    const handleChange = useCallback((e: React.ChangeEvent<HTMLTextAreaElement>) => {
        if (e.target.value.length <= MAX_LENGTH) {
            setContent(e.target.value);
        }
    }, [setContent]);

    // 초기화 핸들러
    const handleClear = useCallback(() => {
        if (content && window.confirm('메모를 초기화할까요?')) {
            setContent('');
            setSaveStatus('idle');
            textareaRef.current?.focus();
        }
    }, [content, setContent]);

    const statusText: Record<SaveStatus, string> = {
        idle: '',
        saving: '저장 중...',
        saved: '저장됨 ✓',
    };

    const statusColor: Record<SaveStatus, string> = {
        idle: 'text-gray-300',
        saving: 'text-yellow-400',
        saved: 'text-green-400',
    };

    const isNearLimit = content.length > MAX_LENGTH * 0.8;

    return (
        <div className="rounded-2xl bg-white border border-gray-200 shadow-sm overflow-hidden">
            {/* 헤더 */}
            <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
                <h3 className="text-sm font-semibold text-gray-700">📝 메모장</h3>
                <span className={`text-xs transition-colors ${statusColor[saveStatus]}`}>
                    {statusText[saveStatus]}
                </span>
            </div>

            {/* 텍스트 영역 */}
            <textarea
                ref={textareaRef}
                value={content}
                onChange={handleChange}
                placeholder="자유롭게 메모하세요..."
                className="w-full h-40 px-4 py-3 text-sm text-gray-700 resize-none focus:outline-none placeholder-gray-300"
            />

            {/* 푸터 */}
            <div className="flex items-center justify-between px-4 py-2 border-t border-gray-100 bg-gray-50">
                <span className={`text-xs ${isNearLimit ? 'text-red-400 font-medium' : 'text-gray-400'}`}>
                    {content.length} / {MAX_LENGTH}자
                </span>
                <button
                    onClick={handleClear}
                    disabled={!content}
                    className="text-xs text-gray-400 hover:text-red-400 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
                >
                    초기화
                </button>
            </div>
        </div>
    );

}