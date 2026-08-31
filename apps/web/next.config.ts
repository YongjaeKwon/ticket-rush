import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // 에이전트 규칙은 루트 CLAUDE.md 하나만 쓴다 — 자동 생성 비활성
  agentRules: false,
};

export default nextConfig;
