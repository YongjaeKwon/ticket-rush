import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 에이전트 규칙은 루트 CLAUDE.md 하나만 쓴다 — 자동 생성 비활성
  agentRules: false,
  // 개발 모드 좌하단 N 배지 — 모바일 화면의 하단 버튼을 가리고 캡처·GIF에 찍힌다
  devIndicators: false,
};

export default nextConfig;
