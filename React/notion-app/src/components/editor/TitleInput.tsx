import { useRef, useEffect } from "react";

type TitleInputProps = {
    value: string;
    onChange: (value: string) => void;
    onEnter?: () => void;
}

/**
 * 제목 입력 컴포넌트
 * - 비어있으면 "제목 없음" 플레이스 홀더 표시
 * - 마운트 시 자동 포커스
 * - Enter 키 -> 본문으로 포커스 이동
 */
export function TitleInput({ value, onChange, onEnter }: TitleInputProps) {

    const inputRef = useRef<HTMLInputElement>(null);

    // 컴포넌트가 처음 나타날 때 자동 포커스
    // deps 배열이 [] -> 마운트 시 1회만 실행
    useEffect(() => {
        inputRef.current?.focus();
    }, []);

    const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
        // Enter 키를 누르면 본문 영역으로 자동 포커스
        if (e.key == "Enter") {
            e.preventDefault();
            onEnter?.();
        }
    };

    return (
        <input
            ref={inputRef}
            type="text"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="제목 없음"
            className="
        w-full bg-transparent border-none outline-none
        text-[40px] font-bold leading-tight tracking-tight
        text-gray-900 placeholder:text-gray-300
        cursor-text
      "
        />
    );
}